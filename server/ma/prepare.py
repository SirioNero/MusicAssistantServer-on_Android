"""
Prepara il server di Music Assistant per Xonos: prende il sorgente di una versione (dal repository
di Music Assistant, o da una cartella), tiene il nucleo e i servizi che Xonos porta sul telefono e
applica le poche modifiche che servono su Android. Il risultato va in una cartella di sorgenti Python
che Chaquopy mette nell'APK.

    python3 prepare.py --version 2.10.8 --out <cartella> [--source <checkout di music-assistant/server>]

Le modifiche sono sostituzioni di testo controllate: se una versione nuova di Music Assistant cambia
quel codice, lo script si ferma e lo dice, invece di produrre un server rotto.
"""

from __future__ import annotations

import argparse
import ast
import io
import json
import shutil
import sys
import tarfile
import tokenize
import urllib.request
from pathlib import Path

# I servizi (provider) che il server di Xonos porta con sé. Quelli "builtin" che Music Assistant
# carica sempre ci sono tutti, tranne sendspin_source (vuole soxr, nativo, e serve a ricevere audio
# da altri apparecchi) e fanarttv e theaudiodb, che chiedono le immagini con una chiave di Music
# Assistant: le sue chiavi (helpers/app_vars.py) ci sono solo nelle sue build ufficiali, e chiede di
# non prenderle da lì. universal_group lo importa il nucleo.
PROVIDERS = [
    # sempre caricati
    "builtin",
    "coverartarchive",
    "itunes_artwork",
    "loudness_analysis",
    "lrclib",
    "musicbrainz",
    "playlist_metadata",
    "radio_playlist",
    "recommendations",
    "sendspin",
    "sync_group",
    "universal_group",
    "universal_player",
    "wikipedia",
    # musica: i file del telefono e i servizi le cui librerie ci sono per Android (vedi requirements.txt).
    # Fuori: Apple Music (DRM), Deezer (per ora), NFS e SMB (vogliono mount), YouTube Music (deno),
    # nicovideo (pydantic 2, in Rust), QQ Music (cryptography 47), BBC Sounds e iBroadcast (solo 3.14),
    # Qobuz e Tidal (entrano con una chiave dell'app di Music Assistant, vedi sopra). Spotify usa la
    # chiave dell'app Spotify della persona (vedi _spotify).
    "abc_radio_network",
    "ambient_sounds",
    "ard_audiothek",
    "audiobookshelf",
    "bandcamp",
    "digitally_incorporated",
    "emby",
    "filesystem_cloud",  # senza manifest: la base comune di Google Drive e OneDrive
    "filesystem_google_drive",
    "filesystem_local",
    "filesystem_onedrive",
    "gpodder",
    "internet_archive",
    "itunes_podcasts",
    "jellyfin",
    "musicme",
    "neteasecloudmusic",
    "nts",
    "nugs",
    "opensubsonic",
    "orf_radiothek",
    "overcast",
    "pandora",
    "phishin",
    "plex",
    "pocketcasts",
    "podcast_index",
    "podcastfeed",
    "radiobrowser",
    "radioparadise",
    "siriusxm",
    "somafm",
    "soundcloud",
    "spotify",  # con librespot compilato per Android (server/librespot); Soloist è solo per Linux
    "storytel",
    "sverigesradio",
    "tunein",
    "webdav",
    "zvuk_music",
    # metadati e altro
    "genius_lyrics",
    "lastfm_recommendations",
    "lastfm_scrobble",
    "listenbrainz_scrobble",
    "smart_playlist",
    "subsonic_scrobble",
]

# Cartelle che altri servizi importano, ma che sul telefono non sono servizi da aggiungere: si copiano
# senza manifest, così Music Assistant non le propone. Spotify Connect vuole go-librespot e l'mDNS.
LIBRARIES = ["spotify_connect"]

# Cartelle e file del sorgente che sul telefono non servono (bin: programmi per Linux e macOS).
SKIP_NAMES = {"__pycache__", "bin"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--source", help="Un checkout di music-assistant/server, invece di scaricarlo")
    args = parser.parse_args()

    out = Path(args.out)
    marker = out / "music_assistant" / ".xonos-version"
    if marker.is_file() and marker.read_text() == _fingerprint(args.version):
        return
    source = Path(args.source) if args.source else _download(args.version, out.parent / "ma-download")
    package = source / "music_assistant"
    if not package.is_dir():
        sys.exit(f"Non trovo music_assistant in {source}")

    target = out / "music_assistant"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(package, target, ignore=_ignore(package))
    for library in LIBRARIES:
        (target / "providers" / library / "manifest.json").unlink()
    _patch(target)
    rewritten = _python313(target)
    marker.write_text(_fingerprint(args.version))
    print(
        f"Music Assistant {args.version} per Xonos in {target} ({len(PROVIDERS)} servizi,"
        f" {rewritten} file riscritti per Python 3.13)"
    )


# I testi dei passi che Xonos aggiunge ai servizi, nelle lingue di Xonos (vedi _texts).
STRINGS = Path(__file__).with_name("strings.json")
MA_STRINGS = Path(__file__).with_name("ma-strings.json")


def _fingerprint(version: str) -> str:
    changed = f"{Path(__file__).stat().st_mtime_ns} {STRINGS.stat().st_mtime_ns} {MA_STRINGS.stat().st_mtime_ns}"
    return f"{version} {' '.join(PROVIDERS + LIBRARIES)} {changed}"


def _download(version: str, folder: Path) -> Path:
    unpacked = folder / f"server-{version}"
    if (unpacked / "music_assistant").is_dir():
        return unpacked
    url = f"https://github.com/music-assistant/server/archive/refs/tags/{version}.tar.gz"
    print(f"Scarico {url}")
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    folder.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = [m for m in archive.getmembers() if "/music_assistant/" in f"/{m.name}"]
        archive.extractall(folder, members=members, filter="data")
    return unpacked


def _ignore(package: Path):
    providers = package / "providers"

    def ignore(directory: str, names: list[str]) -> set[str]:
        skipped = {name for name in names if name in SKIP_NAMES}
        if Path(directory) == providers:
            kept = set(PROVIDERS) | set(LIBRARIES)
            skipped |= {name for name in names if (providers / name).is_dir() and name not in kept}
        return skipped

    return ignore


def _python313(root: Path) -> int:
    """
    Riscrive quello che Python 3.13 non legge e controlla che legga ogni file.

    Music Assistant è scritto per Python 3.14, ma i pacchetti con parti native (Pillow,
    cryptography) per Android ci sono compilati solo fino al 3.13. Del 3.14 il suo codice usa due
    cose: `except A, B:` senza parentesi (PEP 758), che qui diventa `except (A, B):`, e le
    annotazioni dei tipi lette solo quando servono (PEP 649), che nel 3.13 si hanno con
    `from __future__ import annotations` in testa al file.
    """
    rewritten = 0
    for path in sorted(root.rglob("*.py")):
        text = original = path.read_text()
        if "except" in text:
            text = _bracket_excepts(text)
        try:
            tree = ast.parse(text, str(path))
        except SyntaxError as err:
            sys.exit(f"{path}:{err.lineno}: Python {sys.version_info[0]}.{sys.version_info[1]} non lo legge: {err.msg}")
        if FUTURE_ANNOTATIONS not in text and _annotated(tree):
            text = _with_future_annotations(text, tree)
        if text != original:
            compile(text, str(path), "exec", dont_inherit=True)
            path.write_text(text)
            rewritten += 1
    return rewritten


FUTURE_ANNOTATIONS = "from __future__ import annotations"


def _annotated(tree: ast.Module) -> bool:
    for node in ast.walk(tree):
        if isinstance(node, ast.AnnAssign):
            return True
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            arguments = node.args
            every = [*arguments.posonlyargs, *arguments.args, *arguments.kwonlyargs, arguments.vararg, arguments.kwarg]
            if node.returns is not None or any(arg is not None and arg.annotation is not None for arg in every):
                return True
    return False


def _with_future_annotations(text: str, tree: ast.Module) -> str:
    # Dopo la docstring del modulo e prima di qualsiasi altra istruzione, come vuole Python.
    lines = text.splitlines(keepends=True)
    after = 0
    first = tree.body[0] if tree.body else None
    if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
        after = first.end_lineno or 0
    lines.insert(after, FUTURE_ANNOTATIONS + "\n")
    return "".join(lines)


def _bracket_excepts(text: str) -> str:
    tokens = list(tokenize.generate_tokens(io.StringIO(text).readline))
    spans: list[tuple[tuple[int, int], tuple[int, int]]] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        if token.type != tokenize.NAME or token.string != "except":
            continue
        if tokens[index].string == "*":
            index += 1
        first = index
        depth = 0
        comma = named = False
        while index < len(tokens):
            part = tokens[index]
            if part.type == tokenize.OP and part.string in "([{":
                depth += 1
            elif part.type == tokenize.OP and part.string in ")]}":
                depth -= 1
            elif depth == 0 and part.type == tokenize.OP and part.string == ",":
                comma = True
            elif depth == 0 and part.type == tokenize.NAME and part.string == "as":
                named = True
            elif depth == 0 and part.type == tokenize.OP and part.string == ":":
                break
            index += 1
        if comma and not named:
            spans.append((tokens[first].start, tokens[index - 1].end))
    lines = text.splitlines(keepends=True)
    for (start_row, start_col), (end_row, end_col) in reversed(spans):
        end = lines[end_row - 1]
        lines[end_row - 1] = end[:end_col] + ")" + end[end_col:]
        start = lines[start_row - 1]
        lines[start_row - 1] = start[:start_col] + "(" + start[start_col:]
    return "".join(lines)


def _replace(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        sys.exit(f"{path}: il testo da cambiare c'è {count} volte invece di una:\n{old}")
    path.write_text(text.replace(old, new))


def _patch(root: Path) -> None:
    util = root / "helpers" / "util.py"
    # Sul telefono non si installano pacchetti: quelli dei servizi sono già nell'APK, e un servizio
    # che vuole altro non si carica (lo dice l'errore dell'import).
    _replace(
        util,
        "        if installed_version != version:\n            await install_package(requirement)\n",
        "        # Xonos: i pacchetti sono quelli dentro l'app, la versione non si controlla.\n",
    )
    _replace(
        util,
        "    except ImportError:\n        # (re)install ALL requirements\n        for requirement in requirements:\n"
        "            await install_package(requirement)\n",
        "    except ImportError:\n        # Xonos: niente installazioni sul telefono.\n        raise\n",
    )
    # Su Android c-ares (aiodns) non trova i server DNS del telefono: si usa quello di sistema.
    client = root / "helpers" / "aiohttp_client.py"
    _replace(
        client,
        "from aiohttp_asyncmdnsresolver.api import AsyncDualMDNSResolver\n",
        "from aiohttp import ThreadedResolver as AsyncDualMDNSResolver  # Xonos: il DNS di Android\n",
    )
    _replace(
        client,
        "MassAsyncDNSResolver(async_zeroconf=mass.discovery.aiozc)",
        "MassAsyncDNSResolver()",
    )
    # Sul telefono il server risponde solo al telefono stesso (Xonos e myCar), mai alla rete in cui
    # il telefono si trova: API e flussi su 127.0.0.1, e i flussi pubblicati lì.
    _replace(
        root / "constants.py",
        'DEFAULT_HOST: Final[str] = "0.0.0.0"',
        'DEFAULT_HOST: Final[str] = "127.0.0.1"  # Xonos: solo il telefono stesso',
    )
    # E non si annuncia: niente mDNS (_mass._tcp) né ricerca UPnP nelle reti in cui il telefono entra.
    discovery = root / "controllers" / "discovery" / "controller.py"
    _replace(
        discovery,
        "        await self._setup_mdns_browser()\n        await self._register_mass_service()\n",
        "        # Xonos: il server sul telefono non si annuncia e non cerca casse in rete.\n",
    )
    _replace(
        discovery,
        "        self.mass.subscribe(self._on_core_state_updated, EventType.CORE_STATE_UPDATED)\n",
        "",
    )
    _replace(
        discovery,
        "            self._schedule_periodic_ha_announce()\n        self._schedule_periodic_upnp_discovery()\n",
        "            self._schedule_periodic_ha_announce()\n",
    )
    # Lo Zeroconf che i servizi condividono resta, ma sul telefono stesso: nessun socket nella rete.
    _replace(
        discovery,
        '        return AsyncZeroconf(\n            ip_version=zc_args["ip_version"],\n'
        '            interfaces=zc_args["interfaces"],\n        )\n',
        "        # Xonos: solo il telefono stesso, nessun socket nella rete.\n"
        '        return AsyncZeroconf(ip_version=IPVersion.V4Only, interfaces=["127.0.0.1"])\n',
    )
    _replace(discovery, "from zeroconf import (\n", "from zeroconf import (\n    IPVersion,\n")
    # Sendspin: Music Assistant prepara l'audio in float a 32 bit e aiosendspin lo converte per ogni
    # lettore con PyAV, che per Android non c'è. Sul telefono il lettore è Xonos, che prende PCM a
    # 16 bit: l'audio si prepara già così (ffmpeg converte) e aiosendspin lo passa com'è.
    playback = root / "providers" / "sendspin" / "playback.py"
    for indent, rate in (("", "48000"), ("        ", "sample_rate")):
        _replace(
            playback,
            f"{indent}    content_type=ContentType.PCM_F32LE,\n{indent}    sample_rate={rate},\n"
            f"{indent}    bit_depth=32,\n",
            f"{indent}    content_type=ContentType.PCM_S16LE,  # Xonos: PCM a 16 bit\n"
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=16,\n",
        )
        _replace(
            playback,
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=32,\n{indent}    channels=2,\n"
            f'{indent}    sample_type="float",\n',
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=16,\n{indent}    channels=2,\n"
            f'{indent}    sample_type="int",  # Xonos: PCM a 16 bit\n',
        )
    # Spotify: librespot per Android non sta nella cartella bin del servizio ma tra i programmi
    # del pacchetto, che Xonos mette nel PATH (vedi server/librespot e PhoneServer).
    spotify = root / "providers" / "spotify" / "helpers.py"
    _replace(
        spotify,
        '    msg = f"Unable to locate Librespot for {system}/{architecture}"\n',
        "    # Xonos: su Android librespot è tra i programmi dell'app, nel PATH.\n"
        '    if (on_path := shutil.which("librespot")) and (librespot_binary := await check_librespot(on_path)):\n'
        "        return librespot_binary\n\n"
        '    msg = f"Unable to locate Librespot for {system}/{architecture}"\n',
    )
    _replace(spotify, "import re\nimport tempfile\n", "import re\nimport shutil\nimport tempfile\n")
    # La cartella che propone il servizio dei file sul disco: sul telefono è quella della musica, che
    # Xonos chiede il permesso di leggere. Xonos dice quale (XONOS_MUSIC_DIR): in un profilo secondario o nello
    # Spazio privato di Android non è /storage/emulated/0/Music ma /storage/emulated/<profilo>/Music.
    constants = root / "providers" / "filesystem_local" / "constants.py"
    _replace(constants, "from dataclasses import replace\n", "import os\nfrom dataclasses import replace\n")
    _replace(
        constants,
        '    default_value="/media",\n',
        '    default_value=os.environ.get("XONOS_MUSIC_DIR", "/storage/emulated/0/Music"),  # Xonos: la musica\n',
    )
    # Il testo sulle copertine generate delle playlist: sul telefono nessuno dei caratteri cercati c'è, e si usa
    # quello di Pillow, che passa da FreeType. La libreria di FreeType di Chaquopy non è allineata a 16 KB: sui
    # telefoni con pagine da 16 KB non si carica, e allora si ripiega sul carattere a punti di Pillow invece di
    # perdere la copertina.
    _replace(
        root / "providers" / "playlist_metadata" / "__init__.py",
        "        return ImageFont.load_default(size=size)\n    except TypeError:\n",
        "        return ImageFont.load_default(size=size)\n    except (TypeError, ImportError, OSError):  # Xonos: senza FreeType\n",
    )
    # I consigli di Last.fm Music Assistant li aggiunge da solo, con la sua chiave: sul telefono si aggiungono
    # a mano, con la chiave di Last.fm della persona.
    _replace(root / "constants.py", '    ("lastfm_recommendations", False),\n', "")
    _reasons(root)
    _flows(root)
    _spotify(root)
    _soloist(root)
    _soloist_live(root)
    _soloist_connect(root)
    _phone_chain(root)
    _texts(root)


def _reasons(root: Path) -> None:
    """
    Quando non c'è niente da suonare Music Assistant dice solo "There is nothing to play here.", e il
    perché resta nel suo registro, che sul telefono nessuno vede. Qui lo dice: l'errore del servizio
    (anche uno di Xonos, già spiegato, come le playlist Spotify non proprie) o che il servizio non ha
    dato brani da suonare.
    """
    loader = root / "controllers" / "player_queues" / "queue_loader.py"
    _replace(
        loader,
        "        shuffle_settled = False\n        # resolve all media items\n",
        "        shuffle_settled = False\n"
        "        skipped: MusicAssistantError | None = None  # Xonos: perché un elemento non si suona\n"
        "        # resolve all media items\n",
    )
    _replace(
        loader,
        '            except (MusicAssistantError, ZeroDivisionError) as err:\n'
        '                self.logger.warning("Skipping %s: %s", item, err)\n',
        '            except (MusicAssistantError, ZeroDivisionError) as err:\n'
        '                self.logger.warning("Skipping %s: %s", item, err)\n'
        "                if isinstance(err, MusicAssistantError):\n"
        "                    skipped = err\n",
    )
    _replace(
        loader,
        "        if not queue_items:\n"
        '            raise MediaNotFoundError("No playable items found", translation_key="no_playable_items")\n',
        "        if not queue_items:\n"
        "            # Xonos: anche perché, invece del solo \"There is nothing to play here.\"\n"
        '            if skipped is not None and str(skipped.translation_key).startswith("xonos_"):\n'
        "                raise skipped\n"
        "            if skipped is not None:\n"
        "                reason = str(skipped) or type(skipped).__name__\n"
        "                raise MediaNotFoundError(\n"
        '                    f"No playable items found: {reason}",\n'
        '                    translation_key="xonos_no_playable_items",\n'
        "                    translation_args=[reason],\n"
        "                )\n"
        "            for found in media_items:\n"
        "                for mapping in getattr(found, \"provider_mappings\", None) or ():\n"
        "                    if self.mass.get_provider(mapping.provider_instance) is None:\n"
        "                        try:\n"
        "                            service = self.mass.get_provider_manifest(mapping.provider_domain).name\n"
        "                        except KeyError:\n"
        "                            service = mapping.provider_domain\n"
        "                        raise MediaNotFoundError(\n"
        '                            f"No playable items found: {service} is not loaded",\n'
        '                            translation_key="xonos_service_down",\n'
        "                            translation_args=[service],\n"
        "                        )\n"
        '            raise MediaNotFoundError("No playable items found", translation_key="xonos_not_available")\n',
    )


def _flows(root: Path) -> None:
    """
    I flussi di configurazione dei servizi (Spotify e gli altri) sul telefono. Si fanno passando da un'app all'altra
    (il browser, l'app Spotify, il Developer Dashboard), e intanto Xonos può perdere la connessione o restare indietro:
    - un flusso fermo da 15 minuti si chiudeva, e creare l'app Spotify o la chiave di Soloist sul telefono ci mette
      di più: sul server di una persona sola si aspettano 3 ore;
    - un flusso finito (o interrotto) mentre la persona era altrove spariva subito, e al ritorno Xonos chiedeva il
      passo e riceveva "Unknown (or finished) setup flow" al posto dell'esito: ora config/flows/get dà ancora
      l'ultimo passo degli ultimi flussi finiti.
    """
    flows = root / "controllers" / "config" / "flows.py"
    _replace(
        flows,
        "IDLE_FLOW_TTL = 15 * 60\n",
        "IDLE_FLOW_TTL = 3 * 60 * 60  # Xonos: sul telefono, tra un'app e l'altra, ci vuole di più\n"
        "# Xonos: gli ultimi flussi finiti, per chi torna a chiedere il passo dopo la fine\n"
        "_XONOS_FINISHED: dict = {}\n",
    )
    _replace(
        flows,
        "        self._setup_flows.pop(flow.session.flow_id, None)\n",
        "        self._setup_flows.pop(flow.session.flow_id, None)\n"
        "        _XONOS_FINISHED[flow.session.flow_id] = flow\n"
        "        while len(_XONOS_FINISHED) > 16:\n"
        "            _XONOS_FINISHED.pop(next(iter(_XONOS_FINISHED)))\n",
    )
    _replace(
        flows,
        "        flow = self._get_flow(flow_id)\n"
        "        self._check_flow_permission(flow)\n"
        "        flow.session.last_activity = time.monotonic()\n"
        "        if (step := flow.session.current_step) is None:\n",
        "        flow = self._setup_flows.get(flow_id) or _XONOS_FINISHED.get(flow_id) or self._get_flow(flow_id)\n"
        "        self._check_flow_permission(flow)\n"
        "        flow.session.last_activity = time.monotonic()\n"
        "        if (step := flow.session.current_step) is None:\n",
    )


def _spotify(root: Path) -> None:
    """
    Spotify sul telefono.

    Music Assistant entra in Spotify con un Client ID suo, che c'è solo nelle sue build ufficiali e che
    chiede di non usare altrove: qui si usa quello dell'app Spotify della persona (si crea gratis nel
    Developer Dashboard di Spotify), per entrare e per i rinnovi. Senza, Spotify rispondeva "client_id:
    not present". L'audio passa da librespot (Soloist è un programma per Linux) e si autorizza nel
    browser del telefono, che torna da solo a librespot su 127.0.0.1: l'abbinamento dall'app Spotify
    non trova Music Assistant, che sul telefono non si fa vedere nella rete.
    """
    folder = root / "providers" / "spotify"
    constants = folder / "constants.py"
    _replace(
        constants,
        'CONF_CLIENT_ID = "client_id"\n',
        'CONF_CLIENT_ID = "client_id"\n'
        'CONF_XONOS_CLIENT_ID = "xonos_client_id"  # Xonos: il Client ID dell\'app Spotify della persona\n',
    )
    _replace(
        constants,
        "LOOPBACK_WAIT_TIMEOUT = 30  # seconds before offering the manual paste instead\n",
        "LOOPBACK_WAIT_TIMEOUT = 5 * 60  # Xonos: il browser è sul telefono stesso e torna sempre qui\n",
    )
    flow = folder / "setup_flow.py"
    _replace(flow, "    CONF_CLIENT_ID,\n", "    CONF_CLIENT_ID,\n    CONF_XONOS_CLIENT_ID,\n")
    _replace(
        flow,
        "        token_result = await _pkce_authenticate(\n"
        '            session, app_var("spotify_client_id"), step_id="authenticate"\n'
        "        )\n",
        "        # Xonos: senza la chiave di Music Assistant, il Client ID dell'app Spotify della persona\n"
        '        client_id = app_var("spotify_client_id") or await _xonos_client_id(session, setup_data)\n'
        '        token_result = await _pkce_authenticate(session, client_id, step_id="authenticate")\n',
    )
    _replace(
        flow,
        '        client_id_default = str(session.context.setup_data.get(CONF_CLIENT_ID) or "")\n',
        '        if not app_var("spotify_client_id"):\n'
        "            # Xonos: la chiave è già quella della persona, il passo per aggiungerne una sua non serve\n"
        "            setup_data[CONF_CLIENT_ID] = None\n"
        "            setup_data[CONF_REFRESH_TOKEN_DEV] = None\n"
        "            # Se il servizio non parte con questi valori (la rete un attimo assente, PulseAudio lento) il\n"
        "            # passo si ripete con l'errore, come fa Music Assistant: chiudere il flusso butterebbe anche\n"
        "            # l'abbinamento di Soloist appena fatto\n"
        "            xonos_errors = None\n"
        "            xonos_reauth = False\n"
        "            while True:\n"
        "                if xonos_errors is not None:\n"
        '                    await session.form([], step_id="xonos_finish_retry", errors=xonos_errors, last_step=True)\n'
        "                    if xonos_reauth:\n"
        "                        # Il tentativo di prima ha già rinnovato (e invalidato) il token: un accesso nuovo nel\n"
        "                        # browser, l'abbinamento resta. Se non riesce (tempo scaduto, rete) si torna al riprova\n"
        "                        try:\n"
        '                            token_result = await _pkce_authenticate(session, client_id, step_id="authenticate")\n'
        "                        except (SetupFlowError, StepExpiredError, OSError, TimeoutError) as err:\n"
        '                            xonos_errors = {"base": getattr(err, "translation_key", None) or str(err) or "timed_out"}\n'
        "                            continue\n"
        '                        setup_data[CONF_REFRESH_TOKEN_GLOBAL] = str(token_result["refresh_token"])\n'
        "                # Mentre Music Assistant avvia Spotify (Soloist, PulseAudio, l'accesso) lo schermo lo dice\n"
        '                session.progress("xonos_finishing", text="xonos_finishing")\n'
        "                try:\n"
        "                    await session.finish(setup_data)\n"
        "                    return\n"
        "                except SetupFlowError as err:\n"
        '                    xonos_errors = {"base": err.translation_key or str(err)}\n'
        '                    xonos_reauth = any(word in f"{err.translation_key} {err}" for word in ("refresh_token_invalid", "invalid_grant", "revoked"))\n'
        '        client_id_default = str(session.context.setup_data.get(CONF_CLIENT_ID) or "")\n',
    )
    _replace(
        flow,
        "        selected = await _choose_playback_backend(session, preselect, errors)\n",
        "        # Xonos: Soloist è un programma per Linux, gira dentro proot (vedi xonos_server): si può scegliere\n"
        "        # solo se l'app ha proot e il pacchetto il sistema Linux\n"
        "        if os.environ.get(\"XONOS_PROOT\"):\n"
        "            selected = await _choose_playback_backend(session, preselect, errors)\n"
        "        else:\n"
        "            if os.environ.get(\"XONOS_PROOT_ERROR\") == \"ptrace\":\n"
        "                # Xonos: Soloist ci sarebbe, ma Android non lascia a Xonos seguire i suoi programmi\n"
        "                await session.form([], step_id=\"xonos_soloist_blocked\")\n"
        "            selected = BACKEND_LIBRESPOT\n",
    )
    _replace(flow, "import asyncio\nimport logging\n", "import asyncio\nimport logging\nimport os\n")
    _replace(
        flow,
        "        method_values = await session.form(\n"
        "            [CONF_ENTRY_PLAYBACK_AUTH_METHOD],\n"
        '            step_id="playback_auth",\n'
        "            errors=errors,\n"
        "        )\n"
        "        method = str(method_values.get(CONF_PLAYBACK_AUTH_METHOD) or PLAYBACK_AUTH_APP)\n",
        "        # Xonos: un passo che spiega la seconda approvazione (e mostra gli errori), poi il browser\n"
        '        await session.form([], step_id="xonos_playback", errors=errors)\n'
        "        method = PLAYBACK_AUTH_BROWSER\n",
    )
    _replace(
        flow,
        "async def _authorize_developer_key(\n",
        "async def _xonos_client_id(session: SetupSession, setup_data: dict[str, Any]) -> str:\n"
        '    """\n'
        "    Xonos: chiede il Client ID dell'app Spotify della persona e lo tiene con l'account.\n"
        "\n"
        "    :param session: The setup session driving the flow.\n"
        "    :param setup_data: The setup data collected so far, updated in place.\n"
        '    """\n'
        '    client_id = str(setup_data.get(CONF_XONOS_CLIENT_ID) or "")\n'
        "    errors: dict[str, str] | None = None\n"
        "    while True:\n"
        "        values = await session.form(\n"
        "            [\n"
        "                ConfigEntry(\n"
        "                    key=CONF_XONOS_CLIENT_ID,\n"
        "                    type=ConfigEntryType.STRING,\n"
        "                    required=True,\n"
        "                    value=client_id or None,\n"
        "                )\n"
        "            ],\n"
        '            step_id="xonos_client_id",\n'
        "            errors=errors,\n"
        "            translation_params=[HOSTED_CALLBACK_URL],\n"
        "        )\n"
        "        # incollato a volte con uno spazio o un a capo\n"
        '        client_id = "".join(str(values.get(CONF_XONOS_CLIENT_ID) or "").split())\n'
        '        if len(client_id) == 32 and all(char in "0123456789abcdefABCDEF" for char in client_id):\n'
        "            setup_data[CONF_XONOS_CLIENT_ID] = client_id\n"
        "            return client_id\n"
        '        errors = {CONF_XONOS_CLIENT_ID: "xonos_client_id_invalid"}\n'
        "\n\n"
        "async def _authorize_developer_key(\n",
    )
    # Un'app Spotify in Development Mode fa entrare solo gli account scritti nella sua User Management (il
    # proprietario e al massimo altri 4): con un altro account l'accesso riesce, ma poi Spotify risponde 403 "the
    # user may not be registered" a ogni richiesta. Music Assistant saltava il controllo e il servizio non andava,
    # senza dire perché: ora un passo spiega cosa fare e, dopo, controlla di nuovo con lo stesso accesso.
    _replace(
        flow,
        "            if response.status != 200:\n"
        '                LOGGER.warning("Account check skipped: Spotify replied HTTP %s", response.status)\n'
        "                return None\n",
        '            if response.status == 403 and "registered" in (await response.text()).lower():\n'
        "                # Xonos: l'account non è tra gli utenti dell'app Spotify della persona\n"
        "                raise _XonosNotRegistered\n"
        "            if response.status != 200:\n"
        '                LOGGER.warning("Account check skipped: Spotify replied HTTP %s", response.status)\n'
        "                return None\n",
    )
    _replace(
        flow,
        '        if account_id := await _verify_account(session, str(token_result["access_token"])):\n',
        '        if account_id := await _xonos_verify_account(session, str(token_result["access_token"])):\n',
    )
    _replace(
        flow,
        "async def _verify_account(session: SetupSession, access_token: str) -> str | None:\n",
        "class _XonosNotRegistered(Exception):\n"
        '    """Xonos: Spotify non fa entrare questo account nell\'app della persona (User Management)."""\n'
        "\n\n"
        "async def _xonos_verify_account(session: SetupSession, access_token: str) -> str | None:\n"
        '    """\n'
        "    Xonos: come _verify_account, ma se l'account non è tra gli utenti dell'app Spotify della persona\n"
        "    spiega come aggiungerlo e poi controlla di nuovo.\n"
        "\n"
        "    :param session: The setup session driving the flow.\n"
        "    :param access_token: The access token from the just-completed sign-in.\n"
        '    """\n'
        "    errors: dict[str, str] | None = None\n"
        "    while True:\n"
        "        try:\n"
        "            return await _verify_account(session, access_token)\n"
        "        except _XonosNotRegistered:\n"
        '            LOGGER.warning("Spotify: this account is not in the User Management of the Spotify app")\n'
        '            await session.form([], step_id="xonos_user_not_registered", errors=errors)\n'
        '            errors = {"base": "xonos_user_still_not_registered"}\n'
        "\n\n"
        "async def _verify_account(session: SetupSession, access_token: str) -> str | None:\n",
    )
    # Con l'app della persona Spotify può dire "non suonabile" (is_playable) anche dei brani che si suonano, e allora
    # non si suonava niente ("There is nothing to play here."). A suonare è librespot, che decide da sé: si prova
    # comunque, tranne per il filtro dei contenuti espliciti della persona, e lo si scrive nel registro.
    parsers = folder / "parsers.py"
    _replace(
        parsers,
        "from music_assistant.helpers.util import infer_album_type, parse_title_and_version\n",
        "from music_assistant.helpers.app_vars import app_var\n"
        "from music_assistant.helpers.util import infer_album_type, parse_title_and_version\n",
    )
    _replace(
        parsers,
        '                available=not track_obj["is_local"] and track_obj["is_playable"],\n',
        '                available=not track_obj["is_local"] and _xonos_playable(track_obj, provider),\n',
    )
    _replace(
        parsers,
        "def parse_playlist(playlist_obj: dict[str, Any], provider: SpotifyProvider) -> Playlist:\n",
        "_xonos_noted: list[str] = []\n\n\n"
        "def _xonos_playable(track_obj: dict[str, Any], provider: SpotifyProvider) -> bool:\n"
        '    """\n'
        "    Xonos: se un brano si può provare a suonare. Con l'app Spotify della persona Spotify può dire\n"
        "    \"non suonabile\" anche di brani che si suonano; decide librespot, che suona. Il filtro dei contenuti\n"
        "    espliciti della persona resta.\n"
        '    """\n'
        '    if track_obj["is_playable"] or app_var("spotify_client_id"):\n'
        '        return bool(track_obj["is_playable"])\n'
        '    restrictions = track_obj.get("restrictions") or {}\n'
        '    if restrictions.get("reason") == "explicit":\n'
        "        return False\n"
        "    if len(_xonos_noted) < 5:\n"
        '        _xonos_noted.append(str(track_obj.get("id")))\n'
        "        provider.logger.warning(\n"
        '            "Spotify says track %s is not playable (%s): trying it anyway",\n'
        '            track_obj.get("id"),\n'
        "            restrictions or \"no reason given\",\n"
        "        )\n"
        "    return True\n\n\n"
        "def parse_playlist(playlist_obj: dict[str, Any], provider: SpotifyProvider) -> Playlist:\n",
    )
    provider = folder / "provider.py"
    _replace(provider, "    CONF_CLIENT_ID,\n", "    CONF_CLIENT_ID,\n    CONF_XONOS_CLIENT_ID,\n")
    # I brani già salvati "non suonabili" (in libreria e nella cache del servizio) tornano da provare.
    _replace(
        provider,
        "            # try login which will raise if it fails (logs in global session)\n"
        "            await self.login()\n",
        "            # try login which will raise if it fails (logs in global session)\n"
        "            await self.login()\n"
        '            if not app_var("spotify_client_id"):\n'
        "                await self._xonos_playable_again()\n",
    )
    _replace(
        provider,
        "    async def unload(self, is_removed: bool = False) -> None:\n",
        "    async def _xonos_playable_again(self) -> None:\n"
        '        """Xonos: una volta, i brani salvati "non suonabili" prima di parsers._xonos_playable tornano da provare."""\n'
        '        marker = Path(self.mass.storage_path) / f".xonos-playable-{self.instance_id}"\n'
        "        if marker.exists():\n"
        "            return\n"
        "        # Gli espliciti no: possono essere fuori per il filtro della persona; li rivede la sincronizzazione.\n"
        "        try:\n"
        "            await self.mass.music.database.execute_write(\n"
        '                "UPDATE provider_mappings SET available = 1 "\n'
        "                \"WHERE provider_instance = :instance AND media_type = 'track' AND available = 0 \"\n"
        "                \"AND item_id NOT IN (SELECT item_id FROM tracks WHERE json_extract(metadata, '$.explicit') = 1)\",\n"
        '                {"instance": self.instance_id},\n'
        "            )\n"
        "            await self.mass.cache.clear(provider_filter=self.instance_id)\n"
        "        except Exception as err:\n"
        '            self.logger.warning("Could not make the saved tracks playable again: %s", err)\n'
        "            return\n"
        "        marker.touch()\n\n"
        "    async def unload(self, is_removed: bool = False) -> None:\n",
    )
    # Con un'app personale (Development Mode) Spotify dà i brani solo delle playlist della persona: per le
    # altre (seguite, o di Spotify come Daily Mix) Music Assistant passa alla sua chiave, che qui non c'è.
    # Si dice perché e cosa fare, invece di "There is nothing to play here.".
    _replace(
        provider,
        "            except MediaNotFoundError:\n"
        "                if use_global or not self.dev_session_active:\n"
        "                    raise\n",
        "            except MediaNotFoundError as err:\n"
        '                if not app_var("spotify_client_id") and not is_liked_songs:\n'
        "                    playlist = await self.get_playlist(prov_playlist_id)\n"
        "                    if not playlist.is_editable:\n"
        "                        raise MediaNotFoundError(\n"
        "                            str(err),\n"
        '                            translation_key="xonos_playlist_not_yours",\n'
        '                            translation_owner="provider.spotify",\n'
        "                        ) from err\n"
        "                if use_global or not self.dev_session_active:\n"
        "                    raise\n",
    )
    _replace(
        provider,
        "                app_var(\"spotify_client_id\"),  # Always use MA's global client ID\n",
        "                # Xonos: senza la chiave di Music Assistant, il Client ID dell'app Spotify della persona\n"
        '                app_var("spotify_client_id") or str(self.get_setup_value(CONF_XONOS_CLIENT_ID) or ""),\n',
    )
    _replace(
        provider,
        "        self.throttler.set_rate_limit(rate_limit=1, period=2)\n",
        '        if app_var("spotify_client_id"):\n'
        "            self.throttler.set_rate_limit(rate_limit=1, period=2)\n"
        "        else:\n"
        "            # Xonos: con la chiave della persona, i limiti di una chiave propria\n"
        "            self.throttler.set_rate_limit(rate_limit=30, period=30)\n",
    )


def _soloist(root: Path) -> None:
    """
    Soloist, il motore ufficiale di Spotify (tutti gli account Premium, anche quelli nuovi), sul telefono.

    È un programma per Linux con glibc, come PulseAudio a cui dà l'audio: girano dentro proot, nel piccolo sistema
    Linux del pacchetto (xonos_server.py li fa passare da lì). Music Assistant li comanda come su Linux; cambiano
    due cose: Soloist si accetta su Android quando c'è proot, e PulseAudio si comanda con pactl invece che con
    libpulse dentro Python (la libpulse del sistema Linux è di glibc, il Python del pacchetto no).
    """
    runtime = root / "providers" / "spotify_connect" / "soloist" / "runtime.py"
    _replace(runtime, "import logging\nimport platform\n", "import logging\nimport os\nimport platform\n")
    _replace(
        runtime,
        "    system = platform.system()\n    if system != \"Linux\":\n",
        "    system = platform.system()\n"
        "    # Xonos: su Android Soloist gira dentro proot, nel sistema Linux del pacchetto\n"
        "    if os.environ.get(\"XONOS_PROOT\"):\n"
        "        system = \"Linux\"\n"
        "    if system != \"Linux\":\n",
    )
    capture = root / "helpers" / "pulse_capture.py"
    text = capture.read_text()
    start = "class PAVolumeController:\n"
    end = "# One shared server per MusicAssistant instance; weak keys so a discarded mass\n"
    if text.count(start) != 1 or text.count(end) != 1:
        sys.exit(f"{capture}: non trovo la classe PAVolumeController da cambiare")
    head, rest = text.split(start)
    _, tail = rest.split(end)
    capture.write_text(head + PULSE_CONTROLLER + "\n\n" + end + tail)
    _replace(capture, "import shutil\nimport threading\n", "import shutil\nimport socket\nimport threading\n")
    # Il socket a righe di testo per i comandi (vedi PULSE_CONTROLLER), accanto a quello nativo. Con .nofail
    # PulseAudio parte anche se il modulo manca, e allora i comandi passano ancora da pactl.
    _replace(
        capture,
        '            f"load-module module-native-protocol-unix socket={self._socket_path} auth-anonymous=1\\n"\n',
        '            f"load-module module-native-protocol-unix socket={self._socket_path} auth-anonymous=1\\n"\n'
        "            # Xonos: anche il socket dei comandi a righe di testo, così niente pactl (un processo in proot)\n"
        '            ".nofail\\n"\n'
        '            f"load-module module-cli-protocol-unix socket={self._socket_path.parent}/cli\\n"\n',
    )
    _replace(
        capture,
        '            LOGGER.debug("Ignoring suspend toggle for stale sink %s", self._sink_name)\n'
        "            return\n"
        "        returncode, output = await check_output(\n",
        '            LOGGER.debug("Ignoring suspend toggle for stale sink %s", self._sink_name)\n'
        "            return\n"
        "        # Xonos: dal socket di PulseAudio (vedi PAVolumeController), senza un pactl in proot a ogni brano\n"
        "        if (controller := self._server._controller) is not None:\n"
        "            if not await asyncio.to_thread(controller.suspend_sink, self._sink_name, suspended):\n"
        '                LOGGER.warning("suspend-sink %s %d failed", self._sink_name, int(suspended))\n'
        "            return\n"
        "        returncode, output = await check_output(\n",
    )
    # Dove va il tempo quando un brano di Soloist parte o si ferma: sul telefono ogni passo passa da proot. Tre
    # righe nel registro per brano, per capire dal registro di una persona cosa rende lenti play e salti.
    backend = root / "providers" / "spotify" / "backends" / "soloist.py"
    _replace(backend, "import asyncio\nimport os\nimport shutil\n", "import asyncio\nimport os\nimport shutil\nimport time\n")
    _replace(
        backend,
        '    async def start(self) -> None:\n        """Spawn the engine on this run\'s URI and get its audio flowing."""\n',
        '    async def start(self) -> None:\n        """Spawn the engine on this run\'s URI and get its audio flowing."""\n'
        "        # Xonos: i tempi dell'avvio, per il registro\n"
        "        xonos_marks = [time.monotonic()]\n"
        "        self._xonos_started_at = xonos_marks[0]\n",
    )
    for anchor in (
        "        await sink.set_volume(100)\n        await sink.suspend()\n",
        "        await proc.start()\n",
        '            self._raise_startup_error("did not start up correctly")\n',
        "        await self._await_playback_started(proc)\n",
    ):
        _replace(backend, anchor, anchor + "        xonos_marks.append(time.monotonic())\n")
    _replace(
        backend,
        "        self._spawn_task(self._read_capture())\n        await self._set_sink(running=True)\n",
        "        self._spawn_task(self._read_capture())\n        await self._set_sink(running=True)\n"
        "        xonos_marks.append(time.monotonic())\n"
        "        self.logger.info(\n"
        '            "Soloist: avvio in %.1f s (PulseAudio %.2f s, processo %.2f s, collegato e dentro Spotify %.2f s,"\n'
        '            " il brano suona %.2f s, posizione e tubo %.2f s)",\n'
        "            xonos_marks[-1] - xonos_marks[0],\n"
        "            *(b - a for a, b in zip(xonos_marks, xonos_marks[1:])),\n"
        "        )\n",
    )
    _replace(
        backend,
        "            self._delivered += len(chunk)\n",
        "            if not self._delivered:\n"
        "                started = getattr(self, \"_xonos_started_at\", None)\n"
        "                if started is not None:\n"
        '                    self.logger.info("Soloist: primo audio %.2f s dopo l\'avvio", time.monotonic() - started)\n'
        "            self._delivered += len(chunk)\n",
    )
    _replace(
        backend,
        "        if self._teardown_done:\n            return\n        self._stopped = True\n",
        "        if self._teardown_done:\n            return\n"
        "        xonos_stop = time.monotonic()  # Xonos: il tempo per fermarlo, per il registro\n"
        "        self._stopped = True\n",
    )
    _replace(
        backend,
        "        self._teardown_done = True\n",
        '        self.logger.info("Soloist: fermato in %.2f s", time.monotonic() - xonos_stop)\n'
        "        self._teardown_done = True\n",
    )


# Xonos: al posto della classe di Music Assistant, che usa libpulse con ctypes.
PULSE_CONTROLLER = '''class PAVolumeController:
    """
    Xonos: PulseAudio comandato dal suo socket a righe di testo, o con pactl.

    Sul telefono PulseAudio e libpulse sono programmi glibc dentro proot: il Python di Music Assistant (bionic) non
    può caricare libpulse con ctypes. Il modulo module-cli-protocol-unix (caricato all'avvio, vedi _launch_daemon)
    prende i comandi come righe di testo su un socket: niente processi nuovi. Un pactl invece è un processo che passa
    da proot, e a ogni brano di Soloist ne servivano cinque (carica, volume, sospendi, riprendi, togli). Se il socket
    non risponde si usa pactl. Stessa interfaccia di prima: chiamate bloccanti, da un thread.
    """

    _PROMPT = b">>> "
    _TIMEOUT = 5.0

    def __init__(self, server: str | None = None) -> None:
        """
        Check that the daemon answers.

        :param server: PA server address to connect to (e.g. "unix:<socket>").
        """
        self._server = server or get_default_pulse_server()
        self._closed = False
        self._lock = threading.Lock()
        self._cli: socket.socket | None = None
        native = self._server[len("unix:"):] if self._server.startswith("unix:") else ""
        # il socket dei comandi sta accanto a quello nativo (vedi _launch_daemon)
        self._cli_path = os.path.join(os.path.dirname(native), "cli") if native else None
        if self._cli_send("stat") is None and self._pactl("info") is None:
            raise OSError("Timed out connecting to PulseAudio for volume control")

    @property
    def uses_socket(self) -> bool:
        """Xonos: whether the command socket is open (for the self test)."""
        return self._cli is not None

    def set_sink_volume(self, sink_name: str, volume_pct: int, channels: int = 2) -> bool:
        """
        Set hardware volume on a named PA sink.

        :param sink_name: PA sink name.
        :param volume_pct: Volume level 0-100, mapped through an exponential audio taper curve.
        :param channels: Unused: every channel gets the same volume.
        """
        amplitude = volume_pct_to_amplitude(volume_pct)
        pa_vol = round(PA_VOLUME_NORM * amplitude ** (1.0 / 3.0))
        return self._run("set-sink-volume", sink_name, str(pa_vol))

    def set_sink_volume_raw(self, sink_name: str, volume_pct: float, channels: int = 2) -> bool:
        """
        Set raw (linear) volume on a named PA sink, without the audio taper.

        :param sink_name: PA sink name.
        :param volume_pct: Linear percentage where 100 maps exactly onto PA_VOLUME_NORM (0 dB).
        :param channels: Unused: every channel gets the same volume.
        """
        volume_pct = min(max(volume_pct, 0.0), MAX_RAW_VOLUME_PCT)
        pa_vol = round(PA_VOLUME_NORM * volume_pct / 100.0)
        return self._run("set-sink-volume", sink_name, str(pa_vol))

    def suspend_sink(self, sink_name: str, suspended: bool) -> bool:
        """
        Xonos: suspend or resume a sink (Music Assistant spawned pactl for this every time).

        :returns: True if PA reported success.
        """
        return self._run("suspend-sink", sink_name, "1" if suspended else "0")

    def load_module(self, module_name: str, argument: str) -> int | None:
        """
        Load a PulseAudio module.

        :param module_name: PA module name, e.g. "module-pipe-sink".
        :param argument: Module argument string (no spaces inside values).
        :returns: The loaded module's index, or None on failure/timeout.
        """
        reply = self._cli_send(f"load-module {module_name} {argument}")
        if reply is not None:
            if reply.strip():
                LOGGER.debug("PulseAudio load-module %s: %s", module_name, reply.strip())
                return None
            # il socket non dice l'indice del modulo, e nemmeno se non si è caricato (PulseAudio 16.1 lo scrive solo
            # nel suo registro): lo si cerca nell'elenco, dal nome e dagli argomenti
            return self._cli_module_index(module_name, argument)
        output = self._pactl("load-module", module_name, *argument.split())
        try:
            return int(output.strip()) if output is not None else None
        except ValueError:
            return None

    def unload_module(self, module_index: int) -> bool:
        """
        Unload a previously-loaded PulseAudio module by index.

        :returns: True if PA reported success.
        """
        return self._run("unload-module", str(module_index))

    def close(self) -> None:
        """Close the command socket."""
        self._closed = True
        with self._lock:
            self._cli_drop()

    def _run(self, *args: str) -> bool:
        """A command that prints nothing when it works: from the socket, or with pactl if the socket is not there."""
        reply = self._cli_send(" ".join(args))
        if reply is None:
            return self._pactl(*args) is not None
        if reply.strip():
            LOGGER.debug("PulseAudio %s: %s", args[0], reply.strip())
            return False
        return True

    def _cli_send(self, line: str) -> str | None:
        """
        One command line on PulseAudio's command socket: what it printed, or None when the socket did not work.

        A reply that does not arrive is not retried (the command may have run already): the connection is dropped
        and the next command opens a new one.
        """
        if self._closed or self._cli_path is None:
            return None
        with self._lock:
            try:
                if self._cli is None:
                    self._cli_connect()
                assert self._cli is not None
                self._cli.sendall(line.encode() + b"\\n")
                return self._cli_read()
            except OSError as err:
                LOGGER.debug("PulseAudio socket, %s: %s", line.split(" ", 1)[0], err)
                self._cli_drop()
                return None

    def _cli_connect(self) -> None:
        """Open the command socket; "hello" turns on the prompt that closes every reply."""
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(self._TIMEOUT)
            sock.connect(self._cli_path)
            self._cli = sock
            sock.sendall(b"hello\\n")
            self._cli_read()
        except OSError:
            self._cli_drop()
            sock.close()
            raise

    def _cli_read(self) -> str:
        """Read up to the next prompt: the reply of the last command."""
        assert self._cli is not None
        data = b""
        while not data.endswith(self._PROMPT):
            chunk = self._cli.recv(65536)
            if not chunk:
                raise ConnectionError("PulseAudio closed the command socket")
            data += chunk
        return data[: -len(self._PROMPT)].decode(errors="replace")

    def _cli_drop(self) -> None:
        """Forget the command socket (closed if it was open)."""
        if (sock := self._cli) is not None:
            self._cli = None
            with suppress(OSError):
                sock.close()

    def _cli_module_index(self, module_name: str, argument: str) -> int | None:
        """The index of the loaded module with this name and these exact arguments (the newest if more)."""
        listing = self._cli_send("list-modules")
        if listing is None:
            return None
        found: int | None = None
        index: int | None = None
        named = False
        for raw in listing.splitlines():
            line = raw.strip()
            if line.startswith("index:"):
                try:
                    index = int(line.split(":", 1)[1])
                except ValueError:
                    index = None
                named = False
            elif line == f"name: <{module_name}>":
                named = True
            elif named and index is not None and line == f"argument: <{argument}>":
                found = index
        if found is None:
            LOGGER.debug("PulseAudio: %s is not in the module list after loading it (it did not load)", module_name)
        return found

    def _pactl(self, *args: str, timeout: float = 10.0) -> str | None:
        """pactl on the private daemon: its output, or None when it failed."""
        if self._closed:
            return None
        import subprocess  # noqa: PLC0415

        env = {**os.environ, "PULSE_SERVER": self._server}
        try:
            result = subprocess.run(
                ["pactl", "--server", self._server, *args],
                capture_output=True,
                env=env,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as err:
            LOGGER.debug("pactl %s: %s", args[0], err)
            return None
        if result.returncode != 0:
            LOGGER.debug("pactl %s: %s", args[0], result.stderr.decode(errors="replace").strip())
            return None
        return result.stdout.decode(errors="replace")
'''


def _soloist_live(root: Path) -> None:
    """
    Soloist resta acceso tra un brano e l'altro: play, salti e riprese senza un processo nuovo né un altro accesso.

    In Music Assistant 2.10.8 ogni brano di Spotify con Soloist è un processo a sé (`--single-track`), che rifà
    l'accesso a Spotify; e la pausa di un lettore Sendspin ferma il flusso, quindi anche la ripresa riparte da capo.
    Sul telefono, attraverso proot, sono secondi a ogni play, salto e ripresa. Qui, quando lo stream di un brano
    finisce prima della fine del brano (salto, pausa, un altro brano), Soloist va in pausa col sink sospeso e resta
    acceso per _XONOS_PARK_S; al brano dopo si riusa: lo stesso brano riprende (resume e seek), un altro parte con
    play(uri). Se non si può o non va, si riparte da capo come prima; dopo _XONOS_TRIES salti (o riprese) falliti
    di fila non si provano più. Si appoggia a cose che Music Assistant usa già: una pausa a metà brano non chiude
    Soloist, il seek su Soloist acceso (_cold_seek) e play(uri) su Soloist acceso (il suo Spotify Connect).
    Provato in CI con un finto Soloist (xonos_fake_soloist.py, prova soloist-sessione dell'autotest).
    """
    runtime = root / "providers" / "spotify_connect" / "soloist" / "runtime.py"
    _replace(
        runtime,
        "_ENDPOINT_POLL_INTERVAL: Final[float] = 0.25\n",
        "_ENDPOINT_POLL_INTERVAL: Final[float] = 0.05  # Xonos: 0,25 s erano in media 0,1 s in più a ogni avvio\n",
    )
    backend = root / "providers" / "spotify" / "backends" / "soloist.py"
    _replace(backend, "_JUMP_TIMEOUT_S: Final[float] = 5.0\n", "_JUMP_TIMEOUT_S: Final[float] = 5.0\n" + LIVE_CONSTANTS)
    _replace(
        backend,
        "        self._run_lock = asyncio.Lock()\n",
        "        self._run_lock = asyncio.Lock()\n"
        "        # Xonos: il timer del Soloist in pausa (_xonos_arm_park) e i salti e le riprese falliti di fila (_xonos_reuse)\n"
        "        self._xonos_park_task: asyncio.Task[None] | None = None\n"
        "        self._xonos_failures = {\"salto\": 0, \"ripresa\": 0}\n"
        "        self._xonos_failed_at = {\"salto\": 0.0, \"ripresa\": 0.0}\n"
        "        self._xonos_retries = {\"salto\": 0, \"ripresa\": 0}\n",
    )
    _replace(
        backend,
        '    async def unload(self) -> None:\n        """Stop the run and release the capture server."""\n',
        '    async def unload(self) -> None:\n        """Stop the run and release the capture server."""\n'
        "        self._xonos_cancel_park()\n",
    )
    _replace(
        backend,
        "        try:\n            async for chunk in run.stream():\n                yield chunk\n"
        "        finally:\n            await run.stop()\n            async with self._run_lock:\n"
        "                if self._run is run:\n                    self._run = None\n",
        "        try:\n            async for chunk in run.stream():\n                yield chunk\n"
        "        finally:\n"
        "            # Xonos: Soloist resta acceso e in pausa per il prossimo brano (_xonos_release), in un'attività sua:\n"
        "            # un secondo annullamento del consumatore non lo lascia a metà\n"
        "            await asyncio.shield(self._xonos_release(run))\n",
    )
    _replace(
        backend,
        "            async with self._run_lock:\n                if (run := self._run) is None:\n",
        "            async with self._run_lock:\n"
        "                if (parked := self._run) is not None and parked.xonos_parked:\n"
        "                    # Xonos: un Soloist acceso e in pausa (salto, pausa, un altro brano) si riusa: niente processo\n"
        "                    # nuovo né un altro accesso a Spotify; se non si può, si spegne e si riparte da capo, come prima\n"
        "                    self._xonos_cancel_park()\n"
        "                    if await self._xonos_reuse(parked, spotify_uri, seek_position, streamdetails):\n"
        "                        return parked\n"
        "                    await parked.stop()\n"
        "                    self._run = None\n"
        "                if (run := self._run) is None:\n"
        "                    xonos_check = time.monotonic()  # Xonos: per il registro\n",
    )
    # Il controllo del programma di Soloist (un "soloist --version" attraverso proot, e dal 76° giorno della build una
    # richiesta al CDN) si fa a ogni avvio da capo passato un minuto dall'ultimo: sul telefono, a ogni fine naturale
    # di un brano. Ogni 6 ore basta (le build durano 90 giorni e si rinnovano dal 76°). Con una riga nel registro:
    # quel tempo non compare in "Soloist: avvio in N s".
    _replace(
        runtime,
        "_VERIFY_CACHE_SECONDS: Final[float] = 60.0\n",
        "_VERIFY_CACHE_SECONDS: Final[float] = 6 * 3600.0  # Xonos: sul telefono passava da proot a ogni fine brano\n",
    )
    _replace(
        runtime,
        "                and self._binary_path.is_file()\n            ):\n                return self._binary_path\n",
        "                and self._binary_path.is_file()\n"
        "                # Xonos: con 6 ore di cache la scadenza della build (90 giorni) si guarda lo stesso: è solo la\n"
        "                # lettura di soloist.meta.json, niente processi\n"
        "                and not self._installed_expired()\n"
        "            ):\n                return self._binary_path\n",
    )
    _replace(
        backend,
        "                    except SoloistError as err:\n"
        '                        raise AudioError(f"Spotify Soloist binary unavailable: {err}") from err\n'
        "                    fresh = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)\n",
        "                    except SoloistError as err:\n"
        '                        raise AudioError(f"Spotify Soloist binary unavailable: {err}") from err\n'
        "                    self.logger.info(\n"
        '                        "Soloist: parte da capo (controllo del programma %.2f s)", time.monotonic() - xonos_check\n'
        "                    )\n"
        "                    fresh = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)\n",
    )
    _replace(backend, "    async def _wait_run_released(", LIVE_BACKEND_METHODS + "    async def _wait_run_released(")
    _replace(
        backend,
        "        self._read_bytes = 0\n        self._tail_zeros = 0\n",
        "        self._read_bytes = 0\n        self._tail_zeros = 0\n"
        "        # Xonos: la sessione che resta accesa tra un brano e l'altro (SoloistBackend._xonos_release)\n"
        "        self._xonos_parked = False\n"
        "        self._xonos_switching = False\n"
        "        self._xonos_previous_uri: str | None = None\n"
        "        self._xonos_reader_task: asyncio.Task[None] | None = None\n"
        "        # la pausa mandata da xonos_park: la sua conferma la aspetta il cambio di brano dopo\n"
        "        self._xonos_pause: asyncio.Future[None] | None = None\n"
        "        # mentre cambia brano, il seek al punto chiesto è partito (vedi _observe_position)\n"
        "        self._xonos_seek_sent = False\n"
        "        # per il registro: quando è partito il brano (avvio o cambio) e quando il sink è ripartito\n"
        "        self._xonos_started_at = time.monotonic()\n"
        "        self._xonos_sink_at = self._xonos_started_at\n",
    )
    # Per il registro: dopo un avvio o un cambio di brano, quando arriva l'audio vero dal tubo e quando c'è il primo
    # secondo intero (Music Assistant fa partire il lettore solo con un secondo di audio nel buffer).
    _replace(
        backend,
        "        shaper = _CaptureShaper()\n",
        "        shaper = _CaptureShaper()\n"
        "        xonos_first: float | None = None  # Xonos: per il registro\n"
        "        xonos_bytes = 0\n",
    )
    _replace(
        backend,
        "            if not (chunk := shaper.shape(chunk)):\n                continue\n",
        "            if not (chunk := shaper.shape(chunk)):\n                continue\n"
        "            if xonos_bytes >= 0:\n"
        "                # Xonos: il primo audio vero e il primo secondo intero dopo che il sink è ripartito\n"
        "                if xonos_first is None:\n"
        "                    xonos_first = time.monotonic()\n"
        "                xonos_bytes += len(chunk)\n"
        "                if xonos_bytes >= _BYTES_PER_SECOND:\n"
        "                    xonos_bytes = -1\n"
        "                    self.logger.info(\n"
        '                        "Soloist: dal sink ripartito, primo audio in %.2f s e primo secondo intero in %.2f s"\n'
        '                        " (silenzio tolto all\'inizio %.2f s)",\n'
        "                        xonos_first - self._xonos_sink_at,\n"
        "                        time.monotonic() - self._xonos_sink_at,\n"
        "                        shaper._lead_skipped / _BYTES_PER_SECOND,\n"
        "                    )\n",
    )
    _replace(
        backend,
        "        self._spawn_task(self._read_capture())\n        await self._set_sink(running=True)\n",
        "        self._spawn_task(self._read_capture())\n"
        "        self._xonos_sink_at = time.monotonic()  # Xonos: per il registro\n"
        "        await self._set_sink(running=True)\n",
    )
    # Mentre cambia brano, uno stato "playing" che parla ancora del brano di prima non dice che quello chiesto suona:
    # senza, un "playing" vecchio insieme al track_changed del nuovo chiudeva l'attesa prima che il brano suonasse.
    _replace(
        backend,
        '            playing = data.status == "playing"\n'
        "            was_playing, self._engine_playing = self._engine_playing, playing\n",
        '            playing = data.status == "playing"\n'
        "            if (\n"
        "                self._xonos_switching\n"
        "                and data.item is not None\n"
        "                and data.item.uri\n"
        "                and data.item.uri != self.spotify_uri\n"
        "            ):\n"
        "                # Xonos: mentre cambia brano, lo stato del brano di prima non dice niente di quello chiesto\n"
        "                playing = self._engine_playing\n"
        "            was_playing, self._engine_playing = self._engine_playing, playing\n",
    )
    # Mentre cambia brano vale come conferma del seek solo una posizione arrivata dopo il seek e vicina al punto
    # chiesto: Soloist in pausa è più avanti di quanto si è sentito (il tubo si legge a 1,1x), e alla ripresa la sua
    # posizione di prima confermava subito un seek non ancora fatto, così usciva un pezzo dal punto sbagliato.
    _replace(
        backend,
        "        self._position_ms = position_ms\n        if (\n",
        "        self._position_ms = position_ms\n"
        "        if self._xonos_switching:\n"
        "            # Xonos: mentre cambia brano vale solo una posizione arrivata dopo il seek e vicina al punto chiesto:\n"
        "            # Soloist in pausa è più avanti di quanto si è sentito, e la sua posizione di prima non conferma niente\n"
        "            if (\n"
        "                self._xonos_seek_sent\n"
        "                and self._seek_target_ms - _SEEK_CONFIRM_GRACE_MS\n"
        "                <= position_ms\n"
        "                <= self._seek_target_ms + _XONOS_SEEK_AHEAD_MS\n"
        "            ):\n"
        "                self._seek_confirmed.set()\n"
        "            return\n"
        "        if (\n",
    )
    _replace(
        backend,
        '    def _spawn_task(self, coro: object) -> None:\n'
        '        """Track a run-scoped task so stop() can cancel and join it."""\n'
        "        task: asyncio.Task[None] = asyncio.create_task(coro)  # type: ignore[arg-type]\n"
        "        task.add_done_callback(self._task_done)\n"
        "        self._tasks.append(task)\n",
        '    def _spawn_task(self, coro: object) -> asyncio.Task[None]:\n'
        '        """Track a run-scoped task so stop() can cancel and join it."""\n'
        "        task: asyncio.Task[None] = asyncio.create_task(coro)  # type: ignore[arg-type]\n"
        "        task.add_done_callback(self._task_done)\n"
        "        self._tasks.append(task)\n"
        "        return task\n",
    )
    # il lettore del tubo si ferma da solo quando Soloist va in pausa tra un brano e l'altro (xonos_park)
    _replace(
        backend,
        "        self._spawn_task(self._read_capture())\n",
        "        self._xonos_reader_task = self._spawn_task(self._read_capture())\n",
    )
    _replace(
        backend,
        "            or self._delivery_done\n            or self._error is not None\n        ):\n            return\n",
        "            or self._delivery_done\n            or self._error is not None\n"
        "            # Xonos: questa pausa la chiede Xonos, per tenere acceso Soloist tra un brano e l'altro\n"
        "            or self._xonos_parked\n"
        "            or self._xonos_switching\n"
        "        ):\n            return\n",
    )
    _replace(
        backend,
        "            if self._started.is_set() and self._reader is not None:\n",
        "            # Xonos: in pausa tra due brani, o mentre cambia brano, il sink lo comanda xonos_park/xonos_switch\n"
        "            if self._started.is_set() and self._reader is not None and not (\n"
        "                self._xonos_parked or self._xonos_switching\n"
        "            ):\n",
    )
    _replace(
        backend,
        '    def _observe_item(self, uri: str, duration_ms: int | None) -> None:\n'
        '        """Record the engine reaching an item."""\n',
        '    def _observe_item(self, uri: str, duration_ms: int | None) -> None:\n'
        '        """Record the engine reaching an item."""\n'
        "        if uri != self.spotify_uri and (self._xonos_parked or self._xonos_switching):\n"
        "            # Xonos: in pausa, o mentre cambia brano, arrivano ancora notizie del brano di prima: non dicono che\n"
        "            # quello chiesto è finito. Se Soloist non arriva al brano chiesto, il cambio non riesce da sé.\n"
        "            return\n",
    )
    _replace(
        backend,
        '        """Fail the run when the account starts playing somewhere else."""\n',
        '        """Fail the run when the account starts playing somewhere else."""\n'
        "        if self._xonos_parked or self._xonos_switching:\n"
        "            # Xonos: in pausa tra due brani Soloist può smettere di essere il dispositivo attivo: il brano dopo lo\n"
        "            # riprende, come farebbe un Soloist nuovo (o il cambio non riesce e si riparte da capo)\n"
        "            return\n",
    )
    _replace(backend, "    # ---- internals ----\n", LIVE_RUN_METHODS + "    # ---- internals ----\n")


def _soloist_connect(root: Path) -> None:
    """
    Soloist in modalità Spotify Connect: una sessione sola che cambia brano su comando.

    Con --single-track (come in Music Assistant) Soloist suona un brano e si chiude, e si chiude anche appena gli si
    chiede un altro brano: sul telefono dell'utente (0.1.52 e 0.1.53) ogni salto sulla sessione accesa finiva in
    "websocket connection closed", e ogni avanti rifaceva processo e accesso a Spotify (circa 3,5 s in tutto). Senza
    --single-track Soloist è un dispositivo Spotify Connect (il plugin Spotify Connect di Music Assistant lo usa così):
    resta acceso, e play(uri) cambia brano. Qui: si avvia senza brano (_xonos_connect_args), casuale e ripeti spenti, poi
    play del brano (un play senza activate, che suona sul posto senza passare la riproduzione alle app di Spotify); i
    salti sono play(uri) sulla sessione accesa (_xonos_reuse, xonos_switch); a fine brano Soloist non esce: quando passa a
    un altro brano (autoplay), o si ferma alla fine, il brano è finito e Soloist va in pausa, pronto per il prossimo
    (_xonos_item_ended). Se in modalità Spotify Connect non parte, si torna a un processo per brano
    (_xonos_fresh_run). Il telefono compare come dispositivo "Xonos" tra quelli dell'account (scelta dell'utente).
    Provato in CI col finto Soloist (xonos_fake_soloist.py senza --single-track); con Spotify vero va provato sul telefono.
    """
    backend = root / "providers" / "spotify" / "backends" / "soloist.py"
    _replace(
        backend,
        '        self._xonos_retries = {"salto": 0, "ripresa": 0}\n',
        '        self._xonos_retries = {"salto": 0, "ripresa": 0}\n'
        "        # Xonos: la modalità Spotify Connect (_xonos_fresh_run): quando la si riprova dopo un avvio non riuscito\n"
        "        self._xonos_connect_blocked_until = 0.0\n"
        "        self._xonos_connect_fails = 0\n",
    )
    _replace(
        backend,
        "        self._xonos_sink_at = self._xonos_started_at\n",
        "        self._xonos_sink_at = self._xonos_started_at\n"
        "        # Xonos: Soloist in modalità Spotify Connect (_soloist_connect), e la sua pausa dopo la fine di un brano\n"
        "        self._xonos_connect = False\n"
        "        self._xonos_holding = False\n",
    )
    # l'avvio: senza --single-track, attesa dell'accesso più corta (poi un processo per brano), e il brano chiesto con play
    _replace(
        backend,
        "            backend._session_args(self.spotify_uri),\n",
        "            # Xonos: in modalità Spotify Connect senza --single-track (_soloist_connect in prepare.py)\n"
        "            backend._xonos_connect_args(self.spotify_uri)\n"
        "            if self._xonos_connect\n"
        "            else backend._session_args(self.spotify_uri),\n",
    )
    _replace(
        backend,
        "            async with asyncio.timeout(_STARTUP_TIMEOUT_S):\n                while not self._error and not (\n",
        "            # Xonos: in modalità Spotify Connect l'attesa è più corta: se non entra, un processo per brano\n"
        "            async with asyncio.timeout(_XONOS_CONNECT_LOGIN_S if self._xonos_connect else _STARTUP_TIMEOUT_S):\n"
        "                while not self._error and not (\n",
    )
    _replace(
        backend,
        "        await self._await_playback_started(proc)\n",
        "        if self._xonos_connect:\n"
        "            # Xonos: in modalità Spotify Connect Soloist parte senza brano: il brano lo chiede play\n"
        "            await self._xonos_connect_play(client)\n"
        "        else:\n"
        "            await self._await_playback_started(proc)\n",
    )
    _replace(
        backend,
        "                    fresh = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)\n"
        "                    try:\n"
        "                        await fresh.start()\n"
        "                    except BaseException:\n"
        "                        await fresh.stop()\n"
        "                        raise\n"
        "                    self._run = fresh\n",
        "                    # Xonos: in modalità Spotify Connect se si può, sennò un processo per brano\n"
        "                    fresh = await self._xonos_fresh_run(spotify_uri, seek_position, streamdetails)\n"
        "                    self._run = fresh\n",
    )
    # Dopo la fine del brano gli stati di Soloist (che suona l'autoplay finché non va in pausa) non riaprono il sink; e il
    # dispositivo "non attivo" non ferma niente: un play senza activate suona sul posto, e se un'altra app prende
    # l'account Soloist va in pausa (allora si ferma la coda, come prima).
    _replace(
        backend,
        "                self._xonos_parked or self._xonos_switching\n            ):\n",
        "                self._xonos_parked or self._xonos_switching or self._item_over\n            ):\n",
    )
    _replace(
        backend,
        "        if self._xonos_parked or self._xonos_switching:\n"
        "            # Xonos: in pausa tra due brani Soloist può smettere",
        "        if self._xonos_parked or self._xonos_switching or self._xonos_connect:\n"
        "            # Xonos: in pausa tra due brani Soloist può smettere",
    )
    # A fine brano Soloist in modalità Spotify Connect non esce: il brano è finito e Soloist va in pausa, pronto per il
    # prossimo. Due modi: passa a un altro brano (l'autoplay di Spotify), o si ferma alla fine.
    _replace(
        backend,
        "            self._item_over = True\n"
        '            self.logger.debug("Engine wandered to %s; %s has ended", uri, self.spotify_uri)\n',
        "            if self._xonos_connect:\n"
        "                # Xonos: in modalità Spotify Connect Soloist non esce a fine brano: va in pausa, per il prossimo\n"
        '                self.logger.debug("Engine wandered to %s; %s has ended", uri, self.spotify_uri)\n'
        "                self._xonos_item_ended()\n"
        "                return\n"
        "            self._item_over = True\n"
        '            self.logger.debug("Engine wandered to %s; %s has ended", uri, self.spotify_uri)\n',
    )
    _replace(
        backend,
        '            if was_playing and data.status == "paused":\n',
        "            if was_playing and (\n"
        '                data.status == "paused"\n'
        "                # Xonos: in modalità Spotify Connect, senza autoplay, Soloist può fermarsi alla fine del brano\n"
        '                or (self._xonos_connect and data.status in ("stopped", "idle"))\n'
        "            ):\n",
    )
    _replace(
        backend,
        "        # only the Spotify app or Web API pauses the engine, and a paused engine stalls\n",
        "        if self._xonos_connect and self._xonos_near_end():\n"
        "            # Xonos: in modalità Spotify Connect, senza autoplay, Soloist si ferma alla fine del brano: è la fine\n"
        "            # del brano, non una pausa chiesta dall'app di Spotify\n"
        "            self._xonos_item_ended()\n"
        "            return\n"
        "        # only the Spotify app or Web API pauses the engine, and a paused engine stalls\n",
    )
    # Subito dopo un avvio o un cambio di brano, le righe di Soloist nel registro: dal registro di una persona si vede
    # cosa fa Soloist mentre carica il brano (e perché, se non va).
    _replace(
        backend,
        '            self.logger.debug("[soloist] %s", text)\n',
        "            # Xonos: subito dopo un avvio o un cambio di brano le righe di Soloist vanno nel registro\n"
        "            if time.monotonic() - self._xonos_started_at < _XONOS_ENGINE_LOG_S:\n"
        '                self.logger.info("[soloist] %s", text[:300])\n'
        "            else:\n"
        '                self.logger.debug("[soloist] %s", text)\n',
    )


def _phone_chain(root: Path) -> None:
    """
    Il percorso dell'audio dal servizio al lettore del telefono: un passo in meno e i tempi nel registro.

    Il buffer di Music Assistant tiene l'audio come arriva dal servizio (Soloist: 32 bit 44,1 kHz), e a ogni brano
    un secondo ffmpeg lo converte nel formato della sessione Sendspin. Sul telefono la sessione è sempre PCM 16 bit
    48 kHz stereo (il lettore è Xonos, o myCar, e senza PyAV Sendspin non codifica altro): il buffer tiene già quel
    formato, lo converte il primo ffmpeg, e AudioBuffer.get_stream non avvia il secondo quando i formati sono uguali e
    non ci sono filtri. A ogni play e a ogni salto si risparmia l'avvio di un ffmpeg (col linker del telefono),
    tutto in serie dopo il buffer pronto. Con un filtro (dissolvenza, normalizzazione dinamica) il secondo ffmpeg
    parte come prima.

    Poi le righe "Xonos tempi:" nel registro: quando arriva play_index (e next o previous, che lo fanno partire dopo
    1 s, e l'attesa di un'altra azione sullo stesso lettore), quando il brano è pronto, quando il comando arriva al
    lettore (la sessione Sendspin parte: non è ancora audio) e quando il primo audio parte verso il lettore. Con quelle di Soloist e di Xonos (gli orari hanno i
    millesimi) si vede dal registro di una persona dove va il tempo tra un tocco e il brano nuovo.
    """
    _replace(
        root / "controllers" / "streams" / "audio_buffer.py",
        "    arriving = arriving_audio_format(streamdetails)\n"
        "    return AudioFormat(\n"
        "        content_type=ContentType.from_bit_depth(arriving.bit_depth),\n"
        "        sample_rate=arriving.sample_rate,\n"
        "        bit_depth=pcm_container_bit_depth(arriving.bit_depth),\n"
        "        # buffer the stereo fold of a surround source, so audio analysis measures\n"
        "        # the same audio that is played back rather than the untouched surround mix\n"
        "        channels=min(arriving.channels, 2),\n"
        "    )\n",
        "    # Xonos: sul telefono la sessione Sendspin è sempre PCM 16 bit 48 kHz stereo. Se dopo il buffer non c'è un\n"
        "    # filtro (normalizzazione spenta o del servizio, come Spotify, niente dissolvenza) il buffer tiene già quel\n"
        "    # formato: lo converte il primo ffmpeg, anche da mono o surround, e per il brano non serve un secondo ffmpeg\n"
        "    # (_phone_chain in prepare.py). Con un filtro il secondo ffmpeg c'è comunque: il buffer resta come prima, così\n"
        "    # il guadagno lavora su tutti i bit e il passaggio a 16 bit si fa una volta sola, con il dither\n"
        "    if (\n"
        "        streamdetails.volume_normalization_mode\n"
        "        in (VolumeNormalizationMode.DISABLED, VolumeNormalizationMode.SOURCE)\n"
        "        and not streamdetails.fade_in\n"
        "    ):\n"
        "        return AudioFormat(content_type=ContentType.PCM_S16LE, sample_rate=48000, bit_depth=16, channels=2)\n"
        "    arriving = arriving_audio_format(streamdetails)\n"
        "    return AudioFormat(\n"
        "        content_type=ContentType.from_bit_depth(arriving.bit_depth),\n"
        "        sample_rate=arriving.sample_rate,\n"
        "        bit_depth=pcm_container_bit_depth(arriving.bit_depth),\n"
        "        # buffer the stereo fold of a surround source, so audio analysis measures\n"
        "        # the same audio that is played back rather than the untouched surround mix\n"
        "        channels=min(arriving.channels, 2),\n"
        "    )\n",
    )
    controller = root / "controllers" / "player_queues" / "controller.py"
    _replace(
        controller,
        '        """Play item at index (or item_id) X in queue."""\n'
        "        self._check_player_permission(queue_id)\n",
        '        """Play item at index (or item_id) X in queue."""\n'
        "        self._check_player_permission(queue_id)\n"
        "        # Xonos: i tempi di un play o di un salto, per il registro\n"
        "        xonos_t0 = time.monotonic()\n"
        '        self.logger.info("Xonos tempi: play_index %s ricevuto", index)\n',
    )
    _replace(
        controller,
        "            # Reset flow_mode - the streams controller will set it if flow mode is used.\n"
        "            queue.flow_mode = False\n",
        '            self.logger.info("Xonos tempi: brano pronto in %.2f s", time.monotonic() - xonos_t0)\n'
        "            # Reset flow_mode - the streams controller will set it if flow mode is used.\n"
        "            queue.flow_mode = False\n",
    )
    _replace(
        controller,
        "                await self.mass.players.play_media(queue_id, player_media)\n"
        "                queue.current_index = index\n"
        "                queue.current_item = queue_item\n"
        "                self.signal_update(queue_id)\n",
        "                await self.mass.players.play_media(queue_id, player_media)\n"
        "                queue.current_index = index\n"
        "                queue.current_item = queue_item\n"
        "                self.signal_update(queue_id)\n"
        "            self.logger.info(\n"
        '                "Xonos tempi: comando al lettore dato %.2f s dopo play_index (non è ancora audio)",\n'
        "                time.monotonic() - xonos_t0,\n"
        "            )\n",
    )
    # L'attesa del lucchetto del lettore (un'altra azione di play in corso) e il secondo di next e previous prima di
    # play_index: senza queste righe sembrerebbero tempo del brano nuovo.
    helpers = root / "controllers" / "player_queues" / "helpers.py"
    _replace(helpers, "import functools\nimport random\n", "import functools\nimport random\nimport time\n")
    _replace(
        helpers,
        "        async with self.mass.players.get_group_and_player_lock(queue_id):\n",
        "        xonos_t0 = time.monotonic()  # Xonos: l'attesa di un'altra azione sullo stesso lettore, per il registro\n"
        "        async with self.mass.players.get_group_and_player_lock(queue_id):\n"
        "            if (xonos_wait := time.monotonic() - xonos_t0) >= 0.05:\n"
        "                self.logger.info(  # type: ignore[attr-defined]\n"
        '                    "Xonos tempi: %s ha aspettato %.2f s un\'altra azione sul lettore", func.__name__, xonos_wait\n'
        "                )\n",
    )
    for command, index in (("next", "next_index"), ("previous", "prev_index")):
        _replace(
            controller,
            f"        # debounce rapid {command} button presses using call_later\n",
            f'        self.logger.info("Xonos tempi: {command} ricevuto, play_index %s fra 1 s", {index})\n'
            f"        # debounce rapid {command} button presses using call_later\n",
        )
    playback = root / "providers" / "sendspin" / "playback.py"
    _replace(
        playback,
        '        await import_module_in_thread("av")\n        push_stream: PushStream | None = None\n',
        "        self._xonos_session_t0 = time.monotonic()  # Xonos: per il registro\n"
        '        await import_module_in_thread("av")\n        push_stream: PushStream | None = None\n',
    )
    _replace(
        playback,
        "                    if self._first_commit_monotonic_us is None:\n"
        "                        self._first_commit_monotonic_us = commit_now_us\n",
        "                    if self._first_commit_monotonic_us is None:\n"
        "                        self._first_commit_monotonic_us = commit_now_us\n"
        "                        # Xonos: quando parte il primo audio verso il lettore, e con quanto anticipo\n"
        "                        self.player.logger.info(\n"
        '                            "Xonos tempi: primo audio al lettore %.2f s dopo l\'inizio del flusso,"\n'
        '                            " da suonare fra %d ms",\n'
        "                            time.monotonic() - self._xonos_session_t0,\n"
        "                            (commit_start_us - commit_now_us) / 1000,\n"
        "                        )\n",
    )


# Xonos: le costanti della sessione che resta accesa (_soloist_live).
LIVE_CONSTANTS = '''# Xonos: quanto resta acceso e in pausa Soloist senza brani, pronto per il prossimo (SoloistBackend._xonos_release)
_XONOS_PARK_S: Final[float] = 600.0
# Xonos: salti (o riprese) falliti di fila dopo cui non si provano più: questo Soloist non li accetterebbe, e ognuno
# costa l'attesa (_JUMP_TIMEOUT_S) prima di ripartire da capo
_XONOS_TRIES: Final[int] = 2
# Xonos: dopo quanto si riprovano i salti (o le riprese) sulla sessione accesa, dopo _XONOS_TRIES falliti di fila
# (per esempio in galleria): senza, si ripartiva da capo per tutta la vita del servizio
_XONOS_RETRY_S: Final[float] = 900.0
# Xonos: quanto si aspetta la conferma di un comando a Soloist, e la pausa che chiude la lettura del tubo (il sink è
# sospeso: quello che c'è nel tubo si legge subito)
_XONOS_COMMAND_S: Final[float] = 5.0
_XONOS_DRAIN_S: Final[float] = 0.02
# Xonos: un salto o una ripresa sulla sessione accesa ha _JUMP_TIMEOUT_S (5 s) in tutto, così se non va restano i
# secondi per ripartire da capo nei 15 s che Music Assistant dà al buffer; fino a _XONOS_SWITCH_S solo se Soloist ha
# già detto di avere il brano chiesto (sul telefono, in rete mobile, un Soloist che carica piano conviene aspettarlo:
# ripartire da capo costa 5-7 s e deve caricare lo stesso brano)
_XONOS_SWITCH_S: Final[float] = 8.0
# Xonos: ogni quanto si guarda se Soloist suona il brano chiesto
_XONOS_POLL_S: Final[float] = 0.01
# Xonos: alla ripresa il seek parte con Soloist ancora in pausa; quanto si aspetta che dica la posizione nuova prima
# di riprendere (se in pausa non la dice, il seek si riprova dopo la ripresa)
_XONOS_SEEK_PAUSED_S: Final[float] = 0.3
# Xonos: mentre cambia brano, quanto oltre il punto chiesto può stare la posizione che conferma il seek
_XONOS_SEEK_AHEAD_MS: Final[int] = 1000
# Xonos: Soloist in modalità Spotify Connect (_soloist_connect in prepare.py): una sessione sola che cambia brano su
# comando. Se non parte si torna a un processo per brano, e la si riprova dopo _XONOS_CONNECT_RETRY_S (raddoppiato a ogni
# nuovo fallimento, fino a 16 ore)
_XONOS_CONNECT: Final[bool] = True
_XONOS_CONNECT_LOGIN_S: Final[float] = 8.0
_XONOS_CONNECT_RETRY_S: Final[float] = 1800.0
# Xonos: il nome del telefono nei dispositivi Spotify Connect dell'account
_XONOS_DEVICE_NAME: Final[str] = "Xonos"
# Xonos: in modalità Spotify Connect, una pausa a meno di tanto dalla fine (posizione di Soloist, o audio letto) è la
# fine del brano, non una pausa chiesta dall'app di Spotify
_XONOS_END_MS: Final[int] = 3000
_XONOS_END_READ_S: Final[float] = 5.0
# Xonos: per quanto, dopo un avvio o un cambio di brano, le righe di Soloist vanno nel registro (sennò solo in debug)
_XONOS_ENGINE_LOG_S: Final[float] = 15.0
'''

# Xonos: i metodi di SoloistBackend per la sessione che resta accesa (_soloist_live).
LIVE_BACKEND_METHODS = '''    async def _xonos_release(self, run: _SingleTrackRun) -> None:
        """
        Xonos: a brano saltato, messo in pausa o sostituito, Soloist resta acceso e in pausa per un po'.

        In Music Assistant 2.10.8 ogni brano avvia un Soloist nuovo, che rifà l'accesso a Spotify: sul telefono,
        attraverso proot, sono secondi a ogni play, salto e ripresa (la pausa di un lettore Sendspin ferma il flusso,
        e la ripresa lo riapre). Un Soloist in pausa invece si riusa (_xonos_reuse); dopo _XONOS_PARK_S senza brani
        si spegne.
        """
        async with self._run_lock:
            if run._item_over and run._delivered:
                # per il registro: a fine brano, quanto audio in quanto tempo dal sink ripartito (l'avvio ha la sua
                # riga). Il tubo si legge a 1,1x dopo un primo secondo senza freno: se Soloist tiene il passo il
                # rapporto è 1,1x o poco più; più basso, non tiene il passo e ai cambi di brano naturali c'è un buco
                spent = time.monotonic() - run._xonos_sink_at
                self.logger.info(
                    "Soloist: brano finito, %.1f s di audio in %.1f s dal sink ripartito (%.2fx)",
                    run._delivered / _BYTES_PER_SECOND,
                    spent,
                    run._delivered / _BYTES_PER_SECOND / max(spent, 0.001),
                )
            if self._run is run and run.xonos_can_park():
                try:
                    await run.xonos_park()
                except Exception as err:  # noqa: BLE001 - se non va in pausa bene si spegne, come prima
                    self.logger.info("Soloist: non resta in pausa (%s), lo spengo", err)
                else:
                    self._xonos_arm_park(run)
                    return
            await run.stop()
            if self._run is run:
                self._run = None

    async def _xonos_reuse(
        self,
        run: _SingleTrackRun,
        spotify_uri: str,
        seek_position: int,
        streamdetails: StreamDetails | None,
    ) -> bool:
        """
        Xonos: il Soloist in pausa suona il brano chiesto, senza un processo nuovo né un altro accesso a Spotify.

        Lo stesso brano è una ripresa (alla posizione chiesta), un altro è un salto. Falso se non si può o non va:
        allora si riparte da capo, come prima. Dopo _XONOS_TRIES salti (o riprese) falliti di fila non si provano più
        per _XONOS_RETRY_S. Il salto solo in modalità Spotify Connect: con un processo per brano (--single-track)
        Soloist si chiude appena gli si chiede un altro brano (visto sul telefono, "websocket connection closed").
        """
        same = spotify_uri == run.spotify_uri and not run._item_over
        what = "ripresa" if same else "salto"
        if not same and not run._xonos_connect:
            return False
        if self._xonos_failures[what] >= _XONOS_TRIES:
            if time.monotonic() - self._xonos_failed_at[what] < self._xonos_retry_after(what):
                return False
            # passato un po' di tempo (la galleria è finita): un altro tentativo
            self._xonos_failures[what] = _XONOS_TRIES - 1
            self._xonos_retries[what] += 1
            self.logger.info("Soloist: riprovo il %s sulla sessione accesa", what)
        if (why := run.xonos_unusable(streamdetails)) is not None:
            self.logger.info("Soloist: la sessione accesa non si riusa (%s), riparte da capo", why)
            return False
        started = time.monotonic()
        try:
            steps = await run.xonos_switch(spotify_uri, seek_position * 1000, streamdetails)
        except asyncio.CancelledError:
            # Lo stream nuovo è stato annullato a metà del cambio: quale brano ha Soloist non è più sicuro, il timer
            # di spegnimento è già tolto e nessuno legge il suo audio. Si spegne: il brano dopo riparte da capo.
            await asyncio.shield(self._xonos_drop(run))
            raise
        except Exception as err:  # noqa: BLE001 - si riparte da capo, come prima
            self._xonos_failures[what] += 1
            self._xonos_failed_at[what] = time.monotonic()
            if self._xonos_failures[what] >= _XONOS_TRIES:
                self.logger.warning(
                    "Soloist: %s sulla sessione accesa non riesce, per %.0f minuti si riparte da capo",
                    what,
                    self._xonos_retry_after(what) / 60,
                )
            self.logger.info("Soloist: %s sulla sessione accesa non riuscito (%s), riparte da capo", what, err)
            return False
        self._xonos_failures[what] = 0
        self._xonos_retries[what] = 0
        self.logger.info("Soloist: %s sulla sessione accesa in %.2f s (%s)", what, time.monotonic() - started, steps)
        return True

    def _xonos_retry_after(self, what: str) -> float:
        """
        Xonos: dopo quanto si riprova il salto (o la ripresa) sulla sessione accesa, dopo _XONOS_TRIES falliti di fila.

        _XONOS_RETRY_S, raddoppiato a ogni nuovo tentativo fallito (15, 30, 60 minuti... fino a 16 ore): un Soloist
        che non accetta salti non costa un'attesa ogni quarto d'ora per tutta la vita del servizio.
        """
        return _XONOS_RETRY_S * 2 ** min(self._xonos_retries[what], 6)

    async def _xonos_drop(self, run: _SingleTrackRun) -> None:
        """Xonos: spegne un Soloist che non si può più riusare (un cambio di brano annullato a metà)."""
        await run.stop()
        if self._run is run:
            self._run = None

    async def _xonos_fresh_run(
        self, spotify_uri: str, seek_position: int, streamdetails: StreamDetails | None
    ) -> _SingleTrackRun:
        """
        Xonos: avvia Soloist per questo brano, se si può in modalità Spotify Connect.

        In modalità Spotify Connect (senza --single-track) Soloist resta acceso e cambia brano su comando: i salti e la
        fine di un brano non rifanno né il processo né l'accesso a Spotify. Se in quella modalità non parte, questo brano
        e i prossimi (per _XONOS_CONNECT_RETRY_S, raddoppiato a ogni nuovo fallimento) vanno con un processo per brano,
        come in Music Assistant.
        """
        if _XONOS_CONNECT and time.monotonic() >= self._xonos_connect_blocked_until:
            run = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)
            run._xonos_connect = True
            # finché suona il brano chiesto, le notizie di Soloist (un brano di prima, il dispositivo) non contano
            run._xonos_switching = True
            try:
                await run.start()
            except Exception as err:  # noqa: BLE001 - si prova con un processo per brano, come prima
                await run.stop()
                if run._unpaired:
                    # l'abbinamento non c'è più: con un processo per brano sarebbe lo stesso
                    raise
                wait = _XONOS_CONNECT_RETRY_S * 2 ** min(self._xonos_connect_fails, 5)
                self._xonos_connect_fails += 1
                self._xonos_connect_blocked_until = time.monotonic() + wait
                self.logger.warning(
                    "Soloist: in modalità Spotify Connect non parte (%s); per %.0f minuti un processo per brano",
                    err,
                    wait / 60,
                )
            except BaseException:
                await run.stop()
                raise
            else:
                self._xonos_connect_fails = 0
                return run
        run = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)
        try:
            await run.start()
        except BaseException:
            await run.stop()
            raise
        return run

    def _xonos_connect_args(self, spotify_uri: str) -> list[str]:
        """
        Xonos: gli argomenti di Soloist in modalità Spotify Connect.

        Quelli di un processo per brano (_session_args) senza --single-track: Soloist parte senza brano, col nome
        _XONOS_DEVICE_NAME nei dispositivi dell'account, e il brano lo chiede _SingleTrackRun._xonos_connect_play.
        """
        args = self._session_args(spotify_uri)
        index = args.index("--single-track")
        del args[index : index + 2]
        if "--device-name" in args:
            args[args.index("--device-name") + 1] = _XONOS_DEVICE_NAME
        return args

    def _xonos_arm_park(self, run: _SingleTrackRun) -> None:
        """Xonos: dopo _XONOS_PARK_S senza brani il Soloist in pausa si spegne."""
        self._xonos_cancel_park()

        async def expire() -> None:
            await asyncio.sleep(_XONOS_PARK_S)
            async with self._run_lock:
                if self._run is run and run.xonos_parked:
                    self.logger.info("Soloist: in pausa da %.0f s senza brani, lo spengo", _XONOS_PARK_S)
                    await run.stop()
                    self._run = None

        self._xonos_park_task = asyncio.create_task(expire())

    def _xonos_cancel_park(self) -> None:
        """Xonos: il Soloist in pausa serve ancora (o si spegne adesso): niente timer."""
        if (task := self._xonos_park_task) is not None:
            self._xonos_park_task = None
            task.cancel()

'''

# Xonos: i metodi di _SingleTrackRun per la sessione che resta accesa (_soloist_live).
LIVE_RUN_METHODS = '''    # ---- Xonos: la sessione che resta accesa tra un brano e l'altro ----

    @property
    def xonos_parked(self) -> bool:
        """Whether the run waits, paused, for the next item (see SoloistBackend._xonos_release)."""
        return self._xonos_parked

    def xonos_can_park(self) -> bool:
        """
        Whether the run can stay on, paused, for the next item: alive, signed in, mid-item.

        In Spotify Connect mode also after the item ended: the engine does not exit, it moved on and was paused.
        """
        return (
            not self._stopped
            and self._error is None
            and (self._xonos_connect or not self._item_over)
            and not self._engine_exited
            and proc_running(self._proc)
            and self._client is not None
            and self._client.connected
            and self._logged_in is not False
            and self._reader is not None
            and self._started.is_set()
        )

    async def xonos_park(self) -> None:
        """Suspend the sink, pause the engine and let go of the consumer: the run waits for the next item."""
        client = self._client
        assert client is not None
        started = time.monotonic()
        self._xonos_parked = True
        # Prima il sink: da qui nel tubo non entra più niente. La conferma della pausa è un giro attraverso proot: non
        # si aspetta qui, dove ferma il salto, ma al cambio di brano dopo (xonos_switch), che di solito la trova già
        # arrivata. Se non arriva, la sessione non si riusa.
        await self._set_sink(running=False)
        pause = asyncio.ensure_future(asyncio.wait_for(client.pause(await_result=True), _XONOS_COMMAND_S))
        # un errore lo guarda xonos_unusable o xonos_switch; qui solo perché asyncio non lo segnali come perso
        pause.add_done_callback(lambda task: task.cancelled() or task.exception())
        self._xonos_pause = pause
        # stop() la annulla, se Soloist si spegne prima
        self._tasks.append(pause)
        if (task := self._xonos_reader_task) is not None:
            self._xonos_reader_task = None
            await _cancel_and_join([task])
            with suppress(ValueError):
                self._tasks.remove(task)
        if self._error is not None:
            raise AudioError(self._error)
        self.logger.info(
            "Soloist: in pausa e acceso in %.2f s, pronto per il prossimo brano", time.monotonic() - started
        )

    def xonos_unusable(self, streamdetails: StreamDetails | None) -> str | None:
        """Why the paused engine cannot play the next item (None: it can): it must be alive, signed in, and so on."""
        if not self._xonos_parked:
            return "non in pausa"
        if self._stopped or self._error is not None:
            return self._error or "fermato"
        if self._engine_exited or not proc_running(self._proc):
            return "Soloist uscito"
        if self._client is None or not self._client.connected or self._reader is None:
            return "collegamento perso"
        if self._logged_in is False:
            return "accesso a Spotify perso"
        pause = self._xonos_pause
        if pause is not None and pause.done() and (pause.cancelled() or pause.exception() is not None):
            return "pausa non confermata"
        # the engine reads its loudness setting only when it starts
        saved = self.queue_id
        self.queue_id = streamdetails.queue_id if streamdetails is not None else None
        try:
            wanted = self._engine_normalization_enabled()
        finally:
            self.queue_id = saved
        if wanted != self.engine_normalizes:
            return "normalizzazione cambiata"
        return None

    def xonos_reusable(self, streamdetails: StreamDetails | None) -> bool:
        """Whether the paused engine can play the next item (see xonos_unusable)."""
        return self.xonos_unusable(streamdetails) is None

    async def xonos_switch(
        self, spotify_uri: str, seek_position_ms: int, streamdetails: StreamDetails | None
    ) -> str:
        """
        Play the next item on the paused engine: the same one (a resume) or another one (a jump).

        As at a fresh start the sink stays suspended until the engine plays the asked item at the asked position, so
        no audio of the item before reaches the new consumer; what that item left in the FIFO is read away first.
        Return how long each step took, for the log.
        """
        client = self._client
        assert client is not None
        # after the item ended (Spotify Connect mode) the engine is on another one: the same uri is played again
        same = spotify_uri == self.spotify_uri and not self._item_over
        marks = [time.monotonic()]
        self._xonos_switching = True
        self._xonos_seek_sent = False
        self._xonos_previous_uri = self.spotify_uri
        self.spotify_uri = spotify_uri
        self.media_key = streamdetails.uri if streamdetails is not None else None
        self.queue_id = streamdetails.queue_id if streamdetails is not None else None
        self._seek_target_ms = seek_position_ms
        self._duration_ms = (
            streamdetails.duration * 1000
            if streamdetails is not None and streamdetails.duration
            else None
        )
        self._started = asyncio.Event()
        if same:
            # the engine is already on this item
            self._started.set()
        self._seek_confirmed = asyncio.Event()
        self._position_ms = None
        self._engine_playing = False
        self._item_over = False
        self._xonos_holding = False
        self._delivery_done = False
        self._delivered = 0
        self._read_bytes = 0
        self._tail_zeros = 0
        self._chunks = asyncio.Queue(maxsize=self._chunks.maxsize)
        self._xonos_started_at = marks[0]
        # la pausa mandata da xonos_park, di solito confermata da un pezzo: si aspetta qui, prima di svuotare il tubo,
        # così quello che Soloist ha reso ancora del brano di prima ci è già arrivato
        if (pause := self._xonos_pause) is not None:
            self._xonos_pause = None
            with suppress(ValueError):
                self._tasks.remove(pause)
            await pause
        marks.append(time.monotonic())
        await self._xonos_drain()
        marks.append(time.monotonic())
        if same:
            # Soloist in pausa è più avanti di quanto si è sentito (il tubo si legge a 1,1x): il seek al punto chiesto
            # parte prima di riprendere, così non rende audio dal punto sbagliato. Lo conferma solo una posizione
            # arrivata dopo il seek e vicina al punto (_observe_position); se in pausa non la dice, il seek si rifà
            # dopo la ripresa.
            self._xonos_seek_sent = True
            await asyncio.wait_for(client.seek(seek_position_ms, await_result=True), _XONOS_COMMAND_S)
            with suppress(TimeoutError):
                await asyncio.wait_for(self._seek_confirmed.wait(), _XONOS_SEEK_PAUSED_S)
            await asyncio.wait_for(client.resume(await_result=True), _XONOS_COMMAND_S)
        else:
            await asyncio.wait_for(client.play(spotify_uri, await_result=True), _XONOS_COMMAND_S)
        marks.append(time.monotonic())
        await self._xonos_await_playing(same, marks[0])
        marks.append(time.monotonic())
        if (seek_position_ms or same) and not self._seek_confirmed.is_set():
            self._xonos_seek_sent = True
            await self._cold_seek(client, seek_position_ms)
        if self._error is not None:
            raise AudioError(self._error)
        marks.append(time.monotonic())
        self._xonos_parked = False
        self._xonos_switching = False
        self._xonos_reader_task = self._spawn_task(self._read_capture())
        self._xonos_sink_at = time.monotonic()
        await self._set_sink(running=True)
        if self._error is not None:
            raise AudioError(self._error)
        marks.append(time.monotonic())
        return (
            "pausa di prima %.2f s, tubo %.2f s, comando %.2f s, il brano suona %.2f s, posizione %.2f s, sink %.2f s"
            % tuple(b - a for a, b in zip(marks, marks[1:]))
        )

    async def _xonos_await_playing(self, same: bool, begun: float) -> None:
        """
        Wait for the engine to report that it plays the asked item.

        :param same: Whether the item is the one the engine paused on (a resume).
        :param begun: When the switch began (time.monotonic()).
        """
        while not (self._started.is_set() and self._engine_playing):
            if self._error is not None or self._engine_exited or not proc_running(self._proc):
                raise AudioError(self._error or "Spotify Soloist stopped while changing item")
            # tutto il cambio ha _JUMP_TIMEOUT_S; _XONOS_SWITCH_S quando Soloist ha già detto il brano chiesto (salto)
            # o il punto chiesto (ripresa), cioè mentre lo carica
            loaded = self._seek_confirmed.is_set() if same else self._started.is_set()
            if time.monotonic() - begun >= (_XONOS_SWITCH_S if loaded else _JUMP_TIMEOUT_S):
                raise AudioError("Spotify Soloist did not start the asked item in time")
            await asyncio.sleep(_XONOS_POLL_S)

    async def _xonos_connect_play(self, client: SoloistClient) -> None:
        """
        Spotify Connect mode: the engine started with no item; shuffle and repeat off, then play this run's item.

        A bare play (no activate) plays locally without a Connect transfer, as the Spotify Connect provider of Music
        Assistant notes: the Spotify apps are not handed this playback, as with --single-track.
        """
        begun = time.monotonic()
        await asyncio.wait_for(client.set_shuffle(False, await_result=True), _XONOS_COMMAND_S)
        await asyncio.wait_for(client.set_repeat_track(False, await_result=True), _XONOS_COMMAND_S)
        await asyncio.wait_for(client.set_repeat_context(False, await_result=True), _XONOS_COMMAND_S)
        await asyncio.wait_for(client.play(self.spotify_uri, await_result=True), _XONOS_COMMAND_S)
        await self._xonos_await_playing(False, begun)
        self._xonos_switching = False

    def _xonos_item_ended(self) -> None:
        """
        Spotify Connect mode: the item ended (the engine moved on, or paused at its end).

        The engine does not exit as with --single-track: its audio is done (_item_over), and it is paused so it plays
        nothing more to nobody; then the run waits for the next item, which plays on the same session.
        """
        self._item_over = True
        self._finish_delivery()
        if not self._xonos_holding:
            self._xonos_holding = True
            self._spawn_task(self._xonos_hold())

    async def _xonos_hold(self) -> None:
        """Suspend the sink and pause the engine after its item (Spotify Connect mode)."""
        await self._set_sink(running=False)
        if (client := self._client) is not None and client.connected and not self._xonos_parked:
            await asyncio.wait_for(client.pause(await_result=True), _XONOS_COMMAND_S)

    def _xonos_near_end(self) -> bool:
        """Whether the item is at its end: by the engine's position, or by the audio read (Spotify Connect mode)."""
        if not self._duration_ms:
            return False
        if self._position_ms is not None and self._position_ms >= self._duration_ms - _XONOS_END_MS:
            return True
        read_s = self._read_bytes / _BYTES_PER_SECOND
        return read_s >= (self._duration_ms - self._seek_target_ms) / 1000 - _XONOS_END_READ_S

    async def _xonos_drain(self) -> None:
        """Read away what the item before left in the FIFO (the sink is suspended: nothing new arrives)."""
        reader = self._reader
        assert reader is not None
        while True:
            try:
                chunk = await asyncio.wait_for(reader.read(_READ_CHUNK_SIZE), _XONOS_DRAIN_S)
            except TimeoutError:
                # col loop fermo più dell'attesa, i dati del tubo e il timeout arrivano nello stesso giro: i dati sono
                # nel buffer ma non presi. Il sink è sospeso: col buffer vuoto non resta niente
                if reader._buffer:
                    continue
                return
            if not chunk:
                raise AudioError("Spotify playback was interrupted")

'''


def _texts(root: Path) -> None:
    """
    Mette i testi dei passi di Xonos (strings.json) nei file di traduzione di Music Assistant.

    L'inglese è quello di riserva; le altre lingue sono quelle di Xonos, coi nomi di Music Assistant.
    """
    texts = json.loads(STRINGS.read_text(encoding="utf-8"))
    for locale, strings in texts.items():
        if strings.keys() != texts["en"].keys():
            sys.exit(f"{STRINGS}: {locale} non ha le stesse voci dell'inglese")
        path = root / "translations" / f"{locale}.json"
        if not path.is_file():
            sys.exit(f"{path}: Music Assistant non ha questa lingua")
        translations = json.loads(path.read_text(encoding="utf-8"))
        translations.update(strings)
        path.write_text(json.dumps(translations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # I testi di Music Assistant stesso che lui non traduce in una lingua di Xonos (ma-strings.json: per ora i passi di
    # Soloist in italiano). Solo voci che Music Assistant ha in inglese: un nome sbagliato ferma tutto.
    english = json.loads((root / "translations" / "en.json").read_text(encoding="utf-8"))
    extra = json.loads(MA_STRINGS.read_text(encoding="utf-8"))
    for locale, strings in extra.items():
        unknown = sorted(key for key in strings if key not in english)
        if unknown:
            sys.exit(f"{MA_STRINGS}: {locale}: voci che Music Assistant non ha: {', '.join(unknown[:3])}")
        path = root / "translations" / f"{locale}.json"
        translations = json.loads(path.read_text(encoding="utf-8"))
        translations.update(strings)
        path.write_text(json.dumps(translations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
