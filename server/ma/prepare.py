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
    # Il proxy /sendspin (da cui passano Xonos e myCar) comprimeva ogni pacchetto: OkHttp offre sempre permessage-deflate
    # e aiohttp dal lato server lo accetta di suo. Così ogni pezzo di PCM da 25 ms passava da zlib sul ciclo di Music
    # Assistant (e l'app lo decomprimeva): PCM non si comprime quasi, e aiosendspin stesso la compressione la spegne.
    _replace(
        root / "controllers" / "webserver" / "sendspin_proxy.py",
        "        wsock = web.WebSocketResponse(heartbeat=25)\n",
        "        wsock = web.WebSocketResponse(heartbeat=25, compress=False)  # Xonos: niente zlib sull'audio\n",
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
    _spotify_limits(root)
    _soloist(root)
    _soloist_live(root)
    _soloist_connect(root)
    _soloist_preload(root)
    _phone_chain(root)
    _phone_buffers(root)
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


def _spotify_limits(root: Path) -> None:
    """
    I limiti di Spotify sul telefono (registro dell'utente con la 0.1.53: Spotify ha bloccato le richieste per un'ora).

    Il server sul telefono entra in Spotify con l'app della persona, che ha limiti suoi (Development Mode). Music
    Assistant già ferma le richieste quando Spotify chiede di aspettare più di un minuto (Retry-After), ma:
    - la riproduzione (BYPASS_THROTTLER) passava senza limiti e anche durante il blocco: suonare un artista o una
      playlist grande mandava centinaia di richieste di fila, e ogni play durante il blocco ne mandava altre;
    - un rinnovo del token rifiutato (troppe richieste, o un guasto di Spotify) diventava LoginFailed, e il servizio si
      toglieva finché la persona non lo rifaceva; il blocco stava solo in memoria, e dopo un riavvio (Android chiude
      l'app) il servizio chiedeva di nuovo /me, non partiva e Music Assistant lo riprovava ogni 2 minuti;
    - la persona leggeva solo "Rate limit reached. Please try again later.", e nel registro non c'era chi aveva fatto
      le richieste.
    Qui, come in parte fa già Music Assistant 2.11: la riproduzione prende un posto nel limite e si ferma subito durante
    un blocco lungo; i rinnovi rifiutati passano da soli; il blocco va nel registro (con le richieste degli ultimi 5
    minuti) e in un file che vale anche dopo un riavvio; durante il blocco il servizio parte con l'account dell'ultimo
    accesso; l'errore dice quanti minuti mancano. E una ricerca su Spotify è una richiesta sola.
    """
    throttle = root / "helpers" / "throttle_retry.py"
    # La riproduzione: un posto nel limite come le altre richieste e, durante un blocco lungo, l'errore subito.
    _replace(
        throttle,
        "        if BYPASS_THROTTLER.get():\n            yield 0\n            return\n",
        "        if BYPASS_THROTTLER.get():\n"
        "            # Xonos: la riproduzione non aspetta i blocchi brevi, ma prende un posto nel limite come le altre\n"
        "            # richieste (come in Music Assistant 2.11, senza posti in più): prima passava senza limiti, e suonare un\n"
        "            # artista o una playlist grande mandava centinaia di richieste di fila. Durante un blocco lungo si ferma\n"
        "            # subito come le altre: qui Xonos fa diversamente da Music Assistant (anche 2.11 la fa passare), perché\n"
        "            # ogni richiesta durante il blocco è comunque rifiutata, e Spotify non dice se così il blocco si allunga.\n"
        "            # Il blocco si guarda anche dopo l'attesa del posto: può essere arrivato intanto.\n"
        "            delay = 0.0 if self.cooldown_remaining > MAX_WAIT_TIME else await self.throttler.acquire()\n"
        "            if (remaining := self.cooldown_remaining) > MAX_WAIT_TIME:\n"
        '                msg = f"Rate limited for another {remaining:.0f} seconds"\n'
        "                raise RateLimited(msg, backoff_time=round(remaining))\n"
        "            yield delay\n"
        "            return\n",
    )
    # I blocchi chiesti dal server su un orologio che conta anche il tempo in cui il telefono dorme: su Android
    # time.monotonic() lì si ferma, e un blocco di un'ora con il telefono addormentato durava di più (e ora ferma anche
    # la riproduzione). Il conto dei posti nel limite (Throttler) resta su time.monotonic().
    _replace(
        throttle,
        "MAX_WAIT_TIME = 60\n",
        "MAX_WAIT_TIME = 60\n"
        "\n"
        "\n"
        "def _xonos_clock() -> float:\n"
        '    """Xonos: i secondi dei blocchi chiesti dal server, compreso il tempo in cui il telefono dorme."""\n'
        "    try:\n"
        "        return time.clock_gettime(time.CLOCK_BOOTTIME)\n"
        "    except AttributeError:  # dove CLOCK_BOOTTIME non c'è (non Linux)\n"
        "        return time.monotonic()\n",
    )
    for clock_line in (
        "        return max(0.0, self._cooldown_until - time.monotonic())\n",
        "        self._cooldown_until = max(self._cooldown_until, time.monotonic() + seconds)\n",
        '        """Sleep until the given monotonic deadline, return the time waited."""\n'
        "        remaining = deadline - time.monotonic()\n",
        "                            honored_until = time.monotonic() + sleep_time\n",
        "        :param honored_until: Monotonic deadline the caller already waited out, so a\n",
    ):
        _replace(
            throttle,
            clock_line,
            clock_line.replace("time.monotonic()", "_xonos_clock()")
            .replace("given monotonic deadline", "given deadline (Xonos: su _xonos_clock)")
            .replace("Monotonic deadline", "Deadline (Xonos: su _xonos_clock)"),
        )
    # Chi usa il throttler può sapere di un blocco lungo chiesto dal server (solo quello: non le attese che Music
    # Assistant allunga da sé dopo più rifiuti brevi) e dare i testi dell'errore che lo dice.
    _replace(
        throttle,
        "        self._cooldown_until: float = 0.0\n",
        "        self._cooldown_until: float = 0.0\n"
        "        # Xonos: chi avvisare di un blocco lungo chiesto dal server (con i secondi), e di chi sono i testi\n"
        '        # dell\'errore che lo dice (per esempio "provider.spotify"; None: quelli comuni)\n'
        "        self.xonos_on_block: Callable[[float], None] | None = None\n"
        "        self.xonos_owner: str | None = None\n"
        "        # Xonos: di chi è il blocco lungo (per Spotify il segno del Client ID; None: di nessuno in particolare)\n"
        "        self.xonos_mark: str | None = None\n",
    )
    _replace(
        throttle,
        "                            if isinstance(e, RateLimited):\n"
        "                                throttler.set_cooldown(server_wait)\n",
        "                            if isinstance(e, RateLimited):\n"
        "                                throttler.set_cooldown(server_wait)\n"
        "                                if throttler.xonos_on_block is not None:\n"
        "                                    throttler.xonos_on_block(server_wait)  # Xonos\n",
    )
    _replace(
        throttle,
        "                            raise _give_up(e, server_wait) from e\n",
        "                            raise _give_up(e, server_wait, throttler.xonos_owner) from e\n",
    )
    _replace(
        throttle,
        "                raise _give_up(e, e.backoff_time) from e\n",
        "                raise _give_up(e, e.backoff_time, throttler.xonos_owner) from e\n",
    )
    _replace(
        throttle,
        "def _give_up(err: ResourceTemporarilyUnavailable, wait: float) -> RetriesExhausted:\n"
        '    """\n'
        "    Return the error of a call that does not sit out the wait asked of it.\n"
        "\n"
        "    :param err: The error that asked for the wait, its localization is carried over.\n"
        "    :param wait: The wait that was asked for, in seconds.\n"
        '    """\n',
        "def _give_up(\n"
        "    err: ResourceTemporarilyUnavailable, wait: float, owner: str | None = None\n"
        ") -> RetriesExhausted:\n"
        '    """\n'
        "    Return the error of a call that does not sit out the wait asked of it.\n"
        "\n"
        "    :param err: The error that asked for the wait, its localization is carried over.\n"
        "    :param wait: The wait that was asked for, in seconds.\n"
        "    :param owner: Xonos: di chi sono i testi dell'errore di un blocco lungo (None: quelli comuni).\n"
        '    """\n'
        "    if isinstance(err, RateLimited) and wait > MAX_WAIT_TIME:\n"
        '        # Xonos: quanti minuti mancano, nella lingua della persona, invece di "Rate limit reached. Please try\n'
        '        # again later."\n'
        "        return RetriesExhausted(\n"
        '            f"Not retrying, asked to wait {wait:.0f} seconds",\n'
        '            translation_key="xonos_rate_limited",\n'
        "            translation_args=[max(1, round(wait / 60))],\n"
        "            translation_owner=owner,\n"
        "        )\n",
    )

    folder = root / "providers" / "spotify"
    # Un rinnovo del token rifiutato per troppe richieste o per un guasto di Spotify passa da solo (come in Music
    # Assistant 2.11): prima diventava LoginFailed, che toglie il servizio finché la persona non lo rifà.
    helpers = folder / "helpers.py"
    _replace(
        helpers,
        "from music_assistant_models.errors import LoginFailed\n",
        "from music_assistant_models.errors import LoginFailed, RateLimited, ResourceTemporarilyUnavailable\n",
    )
    _replace(
        helpers,
        "from music_assistant.helpers.process import AsyncProcess, check_output\n",
        "from music_assistant.helpers.process import AsyncProcess, check_output\n"
        "from music_assistant.helpers.throttle_retry import parse_retry_after\n",
    )
    _replace(
        helpers,
        "    for _ in range(2):\n"
        "        async with http_session.post(\n"
        '            "https://accounts.spotify.com/api/token", data=params\n'
        "        ) as response:\n"
        "            if response.status != 200:\n",
        "    for attempt in range(2):\n"
        "        async with http_session.post(\n"
        '            "https://accounts.spotify.com/api/token", data=params\n'
        "        ) as response:\n"
        "            # Xonos (come Music Assistant 2.11): troppe richieste non sono un accesso sbagliato\n"
        "            if response.status == 429:\n"
        "                raise RateLimited(\n"
        '                    f"Spotify rate limits the {session_name} token refresh",\n'
        '                    backoff_time=parse_retry_after(response.headers.get("Retry-After")),\n'
        "                )\n"
        "            if response.status != 200:\n",
    )
    _replace(
        helpers,
        "                # the token failed to refresh, we allow one retry\n"
        "                await asyncio.sleep(2)\n"
        "                continue\n",
        "                # the token failed to refresh, we allow one retry\n"
        "                if response.status >= 500 and attempt:\n"
        "                    # Xonos (come Music Assistant 2.11): un guasto di Spotify passa da solo\n"
        "                    raise ResourceTemporarilyUnavailable(\n"
        '                        f"Spotify failed to refresh the {session_name} access token: HTTP {response.status}",\n'
        "                        backoff_time=30,\n"
        "                    )\n"
        "                await asyncio.sleep(2)\n"
        "                continue\n",
    )

    provider = folder / "provider.py"
    _replace(
        provider,
        "import asyncio\nimport os\nimport shutil\nimport time\nfrom collections import OrderedDict\n",
        "import asyncio\nimport hashlib\nimport os\nimport re\nimport shutil\nimport time\n"
        "from collections import Counter, OrderedDict, deque\n",
    )
    _replace(
        provider,
        "    RateLimited,\n    ResourceTemporarilyUnavailable,\n    UnsupportedFeaturedException,\n)\n",
        "    RateLimited,\n    ResourceTemporarilyUnavailable,\n    RetriesExhausted,\n    UnsupportedFeaturedException,\n)\n",
    )
    _replace(
        provider,
        "from music_assistant.controllers.cache import use_cache\n",
        "from music_assistant.controllers.cache import use_cache\n"
        "from music_assistant.controllers.tasks.constants import ACTIVE_TASK_ID\n",
    )
    _replace(
        provider,
        "from music_assistant.helpers.throttle_retry import (\n    ThrottlerManager,\n",
        "from music_assistant.helpers.throttle_retry import (\n"
        "    BYPASS_THROTTLER,\n    MAX_RETRY_AFTER,\n    MAX_WAIT_TIME,\n    ThrottlerManager,\n",
    )
    _replace(
        provider,
        "_THROTTLERS: dict[str, ThrottlerManager] = {}\n",
        "_THROTTLERS: dict[str, ThrottlerManager] = {}\n" + SPOTIFY_LIMITS_STATE,
    )
    # Il blocco: nel registro, nel file per il riavvio, e quello di prima di un riavvio rimesso al suo posto.
    _replace(
        provider,
        "            # Xonos: con la chiave della persona, i limiti di una chiave propria\n"
        "            self.throttler.set_rate_limit(rate_limit=30, period=30)\n",
        "            # Xonos: con la chiave della persona, i limiti di una chiave propria\n"
        "            self.throttler.set_rate_limit(rate_limit=30, period=30)\n"
        "            # Xonos: un blocco lungo di Spotify nel registro e in un file, che vale anche dopo un riavvio\n"
        "            self.throttler.xonos_on_block = self._xonos_blocked\n"
        '            self.throttler.xonos_owner = "provider.spotify"\n'
        "            self._xonos_restore_block()\n",
    )
    # Durante un blocco (o se Spotify non risponde) il servizio parte con l'account dell'ultimo accesso riuscito.
    _replace(
        provider,
        "            # try login which will raise if it fails (logs in global session)\n"
        "            await self.login()\n"
        '            if not app_var("spotify_client_id"):\n',
        "            # try login which will raise if it fails (logs in global session)\n"
        "            await self._xonos_login()\n"
        '            if not app_var("spotify_client_id"):\n',
    )
    # L'account dell'ultimo accesso vale solo finché /me non risponde: la persona può aver rifatto Spotify con un altro
    # account durante il blocco (allora /me non ha risposto e l'account salvato è quello di prima).
    _replace(
        provider,
        "    _sp_user: dict[str, Any] | None = None\n",
        "    _sp_user: dict[str, Any] | None = None\n"
        "    # Xonos: _sp_user è l'account dell'ultimo accesso, non confermato da Spotify: /me si richiede al prossimo accesso\n"
        "    _xonos_user_unconfirmed: bool = False\n",
    )
    _replace(
        provider,
        "        if not self._sp_user:\n"
        "            self._sp_user = userinfo = await self._get_data(\n"
        '                "me", auth_info=auth_info, use_global_session=True\n'
        "            )\n",
        "        if not self._sp_user or self._xonos_user_unconfirmed:\n"
        "            try:\n"
        '                userinfo = await self._get_data("me", auth_info=auth_info, use_global_session=True)\n'
        "            except (RetriesExhausted, ResourceTemporarilyUnavailable):\n"
        "                # Xonos (come Music Assistant 2.11): Spotify non risponde, o ha bloccato le richieste: l'account\n"
        "                # dell'ultimo accesso riuscito\n"
        "                if (stored := self._xonos_stored_account()) is None:\n"
        "                    raise\n"
        "                self._sp_user = stored\n"
        "                self._xonos_user_unconfirmed = True\n"
        '                self.logger.info("Spotify non risponde: il servizio usa l\'account dell\'ultimo accesso")\n'
        "                return auth_info\n"
        "            self._sp_user = userinfo\n"
        '            if self.get_setup_value(_XONOS_ACCOUNT_NAME) != userinfo.get("display_name"):\n'
        '                self._update_setup_data(_XONOS_ACCOUNT_NAME, userinfo.get("display_name"), immediate=False)\n',
    )
    _replace(
        provider,
        '                self._update_setup_data(CONF_ACCOUNT_ID, userinfo["id"])\n'
        '            self.logger.info("Successfully logged in to Spotify as %s", userinfo["display_name"])\n',
        '                self._update_setup_data(CONF_ACCOUNT_ID, userinfo["id"])\n'
        "            self._xonos_user_unconfirmed = False  # Xonos: l'account è quello che dice Spotify\n"
        '            self.logger.info("Successfully logged in to Spotify as %s", userinfo["display_name"])\n',
    )
    _replace(
        provider,
        "        except MediaNotFoundError, ProviderUnavailableError:\n            return False\n",
        "        except MediaNotFoundError, ProviderUnavailableError:\n            return False\n"
        "        except (RetriesExhausted, ResourceTemporarilyUnavailable):\n"
        "            # Xonos (come Music Assistant 2.11): Spotify non risponde, vale la risposta dell'ultima volta\n"
        "            return bool(self.get_setup_value(_XONOS_AUDIOBOOKS, False))\n",
    )
    _replace(
        provider,
        "            self._audiobooks_supported = await self._test_audiobook_support()\n",
        "            self._audiobooks_supported = await self._test_audiobook_support()\n"
        "            if self.get_setup_value(_XONOS_AUDIOBOOKS) != self._audiobooks_supported:\n"
        "                self._update_setup_data(_XONOS_AUDIOBOOKS, self._audiobooks_supported, immediate=False)\n",
    )
    # Il conto delle richieste (Web API e rinnovi del token), per il registro.
    market = '        kwargs["market"] = "from_token"\n'
    _replace(provider, market, "        self._xonos_count(endpoint)\n" + market)
    for docstring, method in (
        ('        """Delete data from api."""\n', "DELETE"),
        ('        """Put data on api."""\n', "PUT"),
        ('        """Post data on api."""\n', "POST"),
    ):
        _replace(provider, docstring, f'{docstring}        self._xonos_count(endpoint, "{method}")\n')
    _replace(
        provider,
        "        try:\n"
        "            auth_info = await get_spotify_token(\n"
        "                self.mass.http_session,\n"
        "                # Xonos: senza la chiave di Music Assistant",
        '        self._xonos_count("token")  # Xonos\n'
        "        try:\n"
        "            auth_info = await get_spotify_token(\n"
        "                self.mass.http_session,\n"
        "                # Xonos: senza la chiave di Music Assistant",
    )
    # Il blocco salvato e il conto se ne vanno con il servizio.
    _replace(
        provider,
        "                _THROTTLERS.pop(self.instance_id, None)\n",
        "                _THROTTLERS.pop(self.instance_id, None)\n"
        "                # Xonos: anche il blocco salvato per il riavvio e il conto delle richieste\n"
        "                self._xonos_block_file.unlink(missing_ok=True)\n"
        "                for xonos_state in (_XONOS_REQUESTS, _XONOS_REPORTED, _XONOS_BLOCKED_UNTIL):\n"
        "                    xonos_state.pop(self.instance_id, None)\n",
    )
    playable = "    async def _xonos_playable_again(self) -> None:\n"
    _replace(provider, playable, SPOTIFY_LIMITS_METHODS + playable)
    # Una ricerca su Spotify sul telefono è una richiesta sola: al massimo 10 per tipo. Xonos ne chiede 25, cioè tre
    # richieste a ogni ricerca (Spotify ne dà 10 per volta), e la ricerca riparte a ogni pausa mentre si scrive.
    _replace(
        provider,
        "        page_limit = min(limit, 10)\n",
        '        if not app_var("spotify_client_id"):\n'
        "            limit = min(limit, 10)  # Xonos: una richiesta sola per ricerca\n"
        "        page_limit = min(limit, 10)\n",
    )


# Xonos: lo stato dei limiti di Spotify (_spotify_limits), accanto a _THROTTLERS.
SPOTIFY_LIMITS_STATE = '''
# Xonos: le richieste a Spotify degli ultimi 5 minuti per istanza (quando, tipo, per chi), quando il loro conto è
# andato nel registro l'ultima volta e fin quando (ora del telefono) vale l'ultimo blocco scritto nel registro. Come
# il throttler, sopravvivono a un nuovo caricamento del servizio.
_XONOS_REQUESTS: dict[str, deque[tuple[float, str, str]]] = {}
_XONOS_REPORTED: dict[str, float] = {}
_XONOS_BLOCKED_UNTIL: dict[str, float] = {}
_XONOS_WINDOW_S = 300
# Xonos: oltre tante richieste in 5 minuti il loro conto va nel registro, al massimo ogni _XONOS_REPORT_S
_XONOS_BUSY = 100
_XONOS_REPORT_S = 600
# Xonos: un blocco salvato per il riavvio vale al massimo 3 ore; se Spotify blocca ancora, la prima richiesta dopo
# lo dice di nuovo
_XONOS_BLOCK_FILE_S = 3 * 60 * 60
# Xonos: nei dati dell'account, il nome e la risposta sugli audiolibri dell'ultimo accesso riuscito
_XONOS_ACCOUNT_NAME = "xonos_account_name"
_XONOS_AUDIOBOOKS = "xonos_audiobooks"
'''

# Xonos: i metodi di SpotifyProvider per i limiti di Spotify (_spotify_limits).
SPOTIFY_LIMITS_METHODS = '''    def _xonos_stored_account(self) -> dict[str, Any] | None:
        """Xonos: l'account dell'ultimo accesso riuscito (come Music Assistant 2.11), None se non ce n'è stato uno."""
        if not (account_id := self.get_setup_value(CONF_ACCOUNT_ID)):
            return None
        return {"id": account_id, "display_name": self.get_setup_value(_XONOS_ACCOUNT_NAME) or account_id}

    async def _xonos_login(self) -> None:
        """
        Xonos: l'accesso all'avvio del servizio.

        Se Spotify ha bloccato le richieste (anche prima di un riavvio) o non risponde, il servizio parte lo stesso con
        l'account dell'ultimo accesso riuscito, come in Music Assistant 2.11: Soloist e librespot suonano senza la Web
        API, e la prima richiesta dopo il blocco rifà l'accesso. Prima il servizio non partiva e Music Assistant lo
        riprovava ogni 2 minuti: a ogni tentativo un rinnovo del token, e PulseAudio acceso e spento in proot.
        """
        stored = self._xonos_stored_account()
        if stored is not None and (remaining := self.throttler.cooldown_remaining) > MAX_WAIT_TIME:
            self._sp_user = stored
            self._xonos_user_unconfirmed = True
            self.logger.info(
                "Spotify: richieste bloccate ancora per %d minuti, il servizio usa l'account dell'ultimo accesso",
                max(1, round(remaining / 60)),
            )
            return
        try:
            await self.login()
        except ResourceTemporarilyUnavailable as err:
            wait = min(float(err.backoff_time or 0), MAX_RETRY_AFTER)
            if isinstance(err, RateLimited) and wait > MAX_WAIT_TIME:
                self.throttler.set_cooldown(wait)
                self._xonos_blocked(wait)
            if stored is None:
                raise
            self._sp_user = stored
            self._xonos_user_unconfirmed = True
            self.logger.info("Spotify non risponde all'accesso (%s): il servizio usa l'account dell'ultimo accesso", err)

    @property
    def _xonos_block_file(self) -> Path:
        """Xonos: il file del blocco di Spotify, per il riavvio."""
        return Path(self.mass.storage_path) / f".xonos-spotify-block-{self.instance_id}"

    def _xonos_key_mark(self) -> str:
        """Xonos: un segno del Client ID in uso (non il Client ID): un blocco vale solo per l'app Spotify che l'ha preso."""
        client_id = app_var("spotify_client_id") or str(self.get_setup_value(CONF_XONOS_CLIENT_ID) or "")
        return hashlib.sha256(client_id.encode()).hexdigest()[:12]

    def _xonos_blocked(self, seconds: float) -> None:
        """
        Xonos: Spotify ha chiesto di aspettare più di un minuto (Retry-After) e il throttler ha fermato le richieste.

        Una riga nel registro, con le richieste degli ultimi 5 minuti (per capire chi le ha fatte), e il blocco in un
        file: dopo un riavvio (Android chiude l'app) il servizio non chiede di nuovo a Spotify prima della fine.
        Più richieste rifiutate insieme sono lo stesso blocco: una riga sola.
        """
        self.throttler.xonos_mark = self._xonos_key_mark()  # il blocco è dell'app Spotify di questo Client ID
        until = time.time() + seconds
        if until <= _XONOS_BLOCKED_UNTIL.get(self.instance_id, 0.0) + 60:
            return
        _XONOS_BLOCKED_UNTIL[self.instance_id] = until
        self.logger.warning(
            "Spotify ha bloccato le richieste per %d minuti (Retry-After %d s); richieste negli ultimi 5 min: %s",
            max(1, round(seconds / 60)),
            round(seconds),
            self._xonos_requests(),
        )
        try:
            self._xonos_block_file.write_text(
                f"{time.time() + min(seconds, _XONOS_BLOCK_FILE_S):.0f} {self._xonos_key_mark()}"
            )
        except OSError as err:
            self.logger.warning("Blocco di Spotify non salvato: %s", err)

    def _xonos_restore_block(self) -> None:
        """
        Xonos: il blocco di Spotify salvato prima di un riavvio, se vale ancora e per questa app Spotify.

        Il throttler resta lo stesso quando il servizio si ricarica: se la persona l'ha rifatto con un altro Client ID,
        il blocco dell'app di prima si toglie anche dalla memoria.
        """
        if self.throttler.xonos_mark not in (None, self._xonos_key_mark()):
            self.throttler._cooldown_until = 0.0
            self.throttler.xonos_mark = None
            _XONOS_BLOCKED_UNTIL.pop(self.instance_id, None)
            self.logger.info("Spotify: un altro Client ID, il blocco dell'app di prima non vale più")
        path = self._xonos_block_file
        try:
            until_text, mark = path.read_text().split()
            remaining = min(float(until_text) - time.time(), _XONOS_BLOCK_FILE_S)
        except FileNotFoundError:
            return
        except (OSError, ValueError):
            remaining, mark = 0.0, ""
        if remaining <= 0 or mark != self._xonos_key_mark():
            path.unlink(missing_ok=True)
            return
        self.throttler.xonos_mark = mark
        until = time.time() + remaining
        _XONOS_BLOCKED_UNTIL[self.instance_id] = max(_XONOS_BLOCKED_UNTIL.get(self.instance_id, 0.0), until)
        if remaining > self.throttler.cooldown_remaining + 60:
            self.throttler.set_cooldown(remaining)
            self.logger.info(
                "Spotify: richieste ancora bloccate per %d minuti (blocco salvato prima del riavvio)",
                max(1, round(remaining / 60)),
            )

    def _xonos_count(self, endpoint: str, method: str = "") -> None:
        """
        Xonos: una richiesta a Spotify, per il registro.

        Di che tipo (l'indirizzo senza id né nomi) e per chi: bg un lavoro in disparte (sincronizzazione, metadati), play
        la riproduzione, user una richiesta della persona. Si tengono gli ultimi 5 minuti; se sono più di _XONOS_BUSY,
        il loro conto va nel registro, al massimo ogni _XONOS_REPORT_S.
        """
        family = re.sub(r"[0-9A-Za-z]{16,}", "{id}", endpoint.split("?")[0])
        who = "bg" if ACTIVE_TASK_ID.get() else "play" if BYPASS_THROTTLER.get() else "user"
        now = time.monotonic()
        requests = _XONOS_REQUESTS.setdefault(self.instance_id, deque())
        requests.append((now, f"{method} {family}" if method else family, who))
        while now - requests[0][0] > _XONOS_WINDOW_S:
            requests.popleft()
        reported = _XONOS_REPORTED.get(self.instance_id)
        if len(requests) > _XONOS_BUSY and (reported is None or now - reported > _XONOS_REPORT_S):
            _XONOS_REPORTED[self.instance_id] = now
            self.logger.info("Spotify Web API, richieste negli ultimi 5 min: %s", self._xonos_requests())

    def _xonos_requests(self) -> str:
        """Xonos: le richieste a Spotify degli ultimi 5 minuti, in tutto e per tipo e per chi (le più numerose prima)."""
        now = time.monotonic()
        counts = Counter(
            (family, who)
            for when, family, who in _XONOS_REQUESTS.get(self.instance_id, ())
            if now - when <= _XONOS_WINDOW_S
        )
        if not counts:
            return "nessuna"
        parts = ", ".join(f"{family} {who} {count}" for (family, who), count in counts.most_common(12))
        return f"{counts.total()} ({parts})"

'''


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
    # Come PulseAudio si accorda con chi suona (Soloist, pacat): il buffer e i pacchetti chiesti (tlength, minreq), la
    # latenza, la memoria condivisa (SHM, memfd) e lo srbchannel, che decidono quante volte ogni secondo l'audio passa
    # dal socket (e quindi da proot). PulseAudio lo dice solo a livello debug, e Music Assistant non gli dava né il
    # livello né dove scrivere (senza un terminale può scegliere il journal, che sul telefono non c'è). Le righe
    # dell'accordo vanno nel registro (ognuna al massimo una volta ogni 10 minuti), le altre restano in debug.
    _replace(
        capture,
        '                f"--file={self._config_path}",\n',
        '                f"--file={self._config_path}",\n'
        "                # Xonos: i messaggi dell'accordo con chi suona, sullo stderr che legge _supervise\n"
        '                "--log-target=stderr",\n'
        '                "--log-level=debug",\n',
    )
    # Se PulseAudio scrive troppo (una riga a ogni pacchetto, per esempio a ogni fine di un "underrun"), passa a info
    # col socket dei comandi: le righe vanno lette tutte, anche quelle che restano in debug.
    _replace(
        capture,
        "                try:\n"
        "                    async for line in proc.iter_stderr():\n"
        '                        LOGGER.debug("pulseaudio: %s", line)\n',
        "                try:\n"
        "                    xonos_lines: deque[float] | None = deque()  # Xonos: le righe dell'ultimo minuto\n"
        "                    xonos_failed = False  # Xonos: un passaggio a info già fallito (nel registro una volta sola)\n"
        "                    async for line in proc.iter_stderr():\n"
        '                        LOGGER.log(_xonos_pulse_level(line), "pulseaudio: %s", line)  # Xonos\n'
        "                        if xonos_lines is not None and _xonos_pulse_chatty(xonos_lines):\n"
        "                            xonos_lines = None\n"
        "                            xonos_reply: str | None = None\n"
        "                            if (controller := self._controller) is not None:\n"
        "                                with suppress(Exception):  # un errore qui fermerebbe la lettura e PulseAudio\n"
        "                                    xonos_reply = await asyncio.to_thread(\n"
        '                                        controller._cli_send, "set-log-level 3"\n'
        "                                    )\n"
        "                            if xonos_reply is not None and not xonos_reply.strip():\n"
        "                                LOGGER.info(\n"
        '                                    "PulseAudio: più di %d righe in un minuto, da qui solo quelle di livello info",\n'
        "                                    _XONOS_PULSE_MAX_LINES,\n"
        "                                )\n"
        "                            else:\n"
        "                                # il socket dei comandi non c'è, non ha risposto o ha detto un errore: si riprova\n"
        "                                # dopo altre _XONOS_PULSE_MAX_LINES righe (nel registro la prima volta)\n"
        "                                LOGGER.log(\n"
        "                                    logging.DEBUG if xonos_failed else logging.INFO,\n"
        '                                    "PulseAudio: più di %d righe in un minuto, livello info non messo (%s): "\n'
        '                                    "si riprova",\n'
        "                                    _XONOS_PULSE_MAX_LINES,\n"
        '                                    (xonos_reply or "").strip() or "socket dei comandi senza risposta",\n'
        "                                )\n"
        "                                xonos_failed = True\n"
        "                                xonos_lines = deque()\n",
    )
    _replace(capture, "import threading\nimport uuid\n", "import threading\nimport time\nimport uuid\n")
    _replace(capture, "from contextlib import suppress\n", "from collections import deque\nfrom contextlib import suppress\n")
    _replace(
        capture,
        'LOGGER = logging.getLogger(f"{MASS_LOGGER_NAME}.helpers.pulse_capture")\n',
        'LOGGER = logging.getLogger(f"{MASS_LOGGER_NAME}.helpers.pulse_capture")\n' + PULSE_LOG,
    )
    # Il ciclo che tiene collegata la WebSocket di Soloist si ricollegava subito quando la connessione si chiudeva senza
    # errori: un Soloist che accetta e chiude lo faceva girare senza sosta (Python e, attraverso proot, connect e close).
    # Ora, se Soloist è uscito, si smette; se è acceso, al massimo due collegamenti al secondo.
    backend_file = root / "providers" / "spotify" / "backends" / "soloist.py"
    _replace(
        backend_file,
        "_CONNECT_POLL_S: Final[float] = 0.05\n",
        "_CONNECT_POLL_S: Final[float] = 0.05\n"
        "# Xonos: il tempo minimo fra due collegamenti alla WebSocket di Soloist, quando si chiude senza errori\n"
        "_XONOS_EVENTS_RETRY_S: Final[float] = 0.5\n",
    )
    _replace(
        backend_file,
        "        while not self._stopped:\n"
        "            try:\n"
        "                await client.listen_events(self._handle_event)\n"
        "            except asyncio.CancelledError:\n"
        "                raise\n"
        "            except Exception as err:\n"
        "                if self._engine_exited or proc.returncode is not None:\n"
        "                    # the daemon exited (the item finished); the socket dying with\n"
        "                    # it is not an error\n"
        "                    return\n"
        '                self.logger.debug("Soloist event connection lost, reconnecting: %s", err)\n'
        "                await asyncio.sleep(_CONNECT_POLL_S)\n",
        "        while not self._stopped:\n"
        "            xonos_since = time.monotonic()\n"
        "            try:\n"
        "                await client.listen_events(self._handle_event)\n"
        "            except asyncio.CancelledError:\n"
        "                raise\n"
        "            except Exception as err:\n"
        "                if self._engine_exited or proc.returncode is not None:\n"
        "                    # the daemon exited (the item finished); the socket dying with\n"
        "                    # it is not an error\n"
        "                    return\n"
        '                self.logger.debug("Soloist event connection lost, reconnecting: %s", err)\n'
        "                await asyncio.sleep(_CONNECT_POLL_S)\n"
        "                continue\n"
        "            # Xonos: chiusa senza errori. Soloist uscito: niente da riprendere; acceso: non più di due volte al secondo\n"
        "            if self._engine_exited or proc.returncode is not None:\n"
        "                return\n"
        "            if (xonos_rest := _XONOS_EVENTS_RETRY_S - (time.monotonic() - xonos_since)) > 0:\n"
        "                await asyncio.sleep(xonos_rest)\n",
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


# Xonos: le righe di PulseAudio sull'accordo con chi suona, nel registro (vedi _soloist).
PULSE_LOG = '''
# Xonos: le righe di PulseAudio su come si accorda con chi suona: buffer e pacchetti (tlength, minreq), latenza, memoria
# condivisa (SHM, memfd, il ripiego sulla memoria normale) e srbchannel. Nel registro a livello INFO, ognuna al massimo
# una volta ogni _XONOS_PULSE_EVERY_S (a ogni brano si ripetono uguali); le altre righe di PulseAudio restano in debug.
_XONOS_PULSE_WORDS: Final = ("Requested tlength", "Final latency", "srbchannel", "Negotiated SHM", "memory pool")
_XONOS_PULSE_EVERY_S: Final = 600.0
_xonos_pulse_said: dict[str, float] = {}
# Xonos: le ultime righe sull'accordo, anche quelle non scritte nel registro (le legge l'autotest)
_xonos_pulse_seen: deque[tuple[float, str]] = deque(maxlen=64)


def _xonos_pulse_level(line: str) -> int:
    """Xonos: il livello nel registro di una riga di PulseAudio."""
    if not any(word in line for word in _XONOS_PULSE_WORDS):
        return logging.DEBUG
    now = time.monotonic()
    _xonos_pulse_seen.append((now, line))
    said = _xonos_pulse_said.get(line)
    if said is not None and now - said < _XONOS_PULSE_EVERY_S:
        return logging.DEBUG
    if len(_xonos_pulse_said) > 200:
        _xonos_pulse_said.clear()
    _xonos_pulse_said[line] = now
    return logging.INFO


# Xonos: oltre tante righe di PulseAudio in un minuto (per esempio una a ogni pacchetto) il demone passa al livello info
_XONOS_PULSE_MAX_LINES: Final = 1200


def _xonos_pulse_chatty(times: deque[float]) -> bool:
    """Xonos: una riga in più di PulseAudio; vero se nell'ultimo minuto sono almeno _XONOS_PULSE_MAX_LINES."""
    now = time.monotonic()
    times.append(now)
    while now - times[0] > 60:
        times.popleft()
    return len(times) >= _XONOS_PULSE_MAX_LINES

'''

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
        "        self._xonos_retries = {\"salto\": 0, \"ripresa\": 0}\n"
        "        # un salto o una ripresa non riusciti contano solo se poi il brano parte da capo (_xonos_fresh_run)\n"
        "        self._xonos_unconfirmed: str | None = None\n",
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
        '                        "Soloist: parte da capo (controllo del programma %.2f s; %s)",\n'
        "                        time.monotonic() - xonos_check,\n"
        "                        await asyncio.to_thread(_xonos_network),\n"
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
        "        self._xonos_connect_fails = 0\n"
        "        # Xonos: gli account Spotify nella cartella dei dati di Soloist al primo avvio (_xonos_foreign_account)\n"
        "        self._xonos_accounts: frozenset[str] | None = None\n",
    )
    _replace(
        backend,
        "from music_assistant.providers.spotify.helpers import soloist_session_present\n",
        "from music_assistant.providers.spotify.helpers import (  # Xonos: anche gli account (_xonos_foreign_account)\n"
        "    _soloist_session_accounts,\n"
        "    soloist_session_present,\n"
        ")\n",
    )
    _replace(
        backend,
        "        self._xonos_sink_at = self._xonos_started_at\n",
        "        self._xonos_sink_at = self._xonos_started_at\n"
        "        # Xonos: Soloist in modalità Spotify Connect (_soloist_connect), e la sua pausa dopo la fine di un brano\n"
        "        self._xonos_connect = False\n"
        "        self._xonos_holding = False\n"
        "        # Xonos: l'avvio in modalità Spotify Connect non è partito in tempo, con Soloist acceso (_xonos_connect_play)\n"
        "        self._xonos_slow_start = False\n",
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
        "                    client_ready.is_set() and client.connected and self._logged_in\n"
        "                ):\n"
        "                    await asyncio.sleep(_CONNECT_POLL_S)\n",
        "                    client_ready.is_set() and client.connected and self._logged_in\n"
        "                ):\n"
        "                    if self._xonos_connect and not proc_running(proc):\n"
        "                        # Xonos: un Soloist già uscito non entrerà più: subito un processo per brano\n"
        '                        self._raise_startup_error("stopped before signing in")\n'
        "                    await asyncio.sleep(_CONNECT_POLL_S)\n",
    )
    # In modalità Spotify Connect Soloist non esce da solo a fine brano: stop() non aspetta la sua uscita (5 s), lo chiude.
    _replace(
        backend,
        "        if (self._engine_exited or self._item_over) and (proc := self._proc) is not None:\n",
        "        # Xonos: in modalità Spotify Connect Soloist non esce a fine brano: la sua uscita non si aspetta\n"
        "        if (self._engine_exited or (self._item_over and not self._xonos_connect)) and (\n"
        "            proc := self._proc\n"
        "        ) is not None:\n",
    )
    # Audio letto oltre la durata: con ripeti acceso da fuori Soloist ricomincia lo stesso brano, senza cambi né pause.
    _replace(
        backend,
        "                self._read_bytes += len(chunk)\n",
        "                self._read_bytes += len(chunk)\n"
        "                if self._xonos_connect and self._xonos_overrun():\n"
        "                    # Xonos: il brano è ricominciato (ripeti acceso da fuori): è finito\n"
        "                    self._xonos_item_ended()\n"
        "                    return\n",
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


def _soloist_preload(root: Path) -> None:
    """
    Il brano dopo già in Soloist: in modalità Spotify Connect "avanti" non deve caricarlo da capo.

    Dal registro dell'utente (0.1.55, Spotify vero): un avanti si sente 1,6-1,8 s dopo il tocco, e 0,75-0,93 s sono
    Soloist che carica il brano dopo play(uri). Soloist ha una coda come le app di Spotify (add_to_queue, skip_next,
    get_queue che risponde con queue_changed; niente per togliere un brano). Quando un brano parte sulla sessione accesa,
    poco dopo (_XONOS_PRELOAD_AFTER_S) il brano dopo della coda di Music Assistant, se è un brano Spotify di questo
    servizio e non c'è ripeti un brano, va nella coda di Soloist (un brano alla volta, e solo se la coda è vuota:
    _SingleTrackRun._xonos_preload). Poi:
    - "avanti" verso quel brano è skip_next invece di play(uri) (xonos_mode "coda"); se Soloist va altrove, play(uri), e
      dopo _XONOS_TRIES volte niente precarico per quella sessione;
    - a fine brano Soloist ci passa da solo: come oggi con l'autoplay il brano finisce e Soloist va in pausa, e quando
      Music Assistant chiede proprio quel brano si riprende da dove è (seek a 0 e ripresa, xonos_mode "pronto");
    - un brano rimasto in coda (salto altrove) non si toglie: a fine brano Soloist ci passa, la pausa lo ferma e Music
      Assistant chiede il suo brano dopo con play(uri), come con l'autoplay. Finché c'è, niente altri brani in coda.
    Mai con --single-track. Nel registro: «avanti col brano in coda (precaricato) in N s», accanto a «salto sulla
    sessione accesa in N s», e «brano dopo già pronto in Soloist, ripresa in N s». Con _XONOS_PRELOAD = False si spegne.

    E per decidere il resto, una riga per ogni cambio di brano con quello che succede dentro, in secondi dal comando:
    gli eventi di Soloist, quanto audio tiene PulseAudio (list-sink-inputs dal socket dei comandi, ogni 20 ms) e i
    risvegli di proot («Soloist: dentro il salto: ...»), senza uri né titoli.
    Provato in CI col finto Soloist (xonos_fake_soloist.py, coda e skip_next); con Spotify vero va provato sul telefono.
    """
    backend = root / "providers" / "spotify" / "backends" / "soloist.py"
    _replace(
        backend,
        "from music_assistant_models.enums import ContentType, MediaType\n",
        "from music_assistant_models.enums import ContentType, MediaType, RepeatMode  # Xonos: RepeatMode (precarico)\n",
    )
    _replace(
        backend,
        "    SoloistPositionSync,\n    SoloistTrackChanged,\n)\n",
        "    SoloistPositionSync,\n    SoloistQueueChanged,  # Xonos: la coda di Soloist (precarico)\n    SoloistTrackChanged,\n)\n",
    )
    _replace(
        backend,
        "        self._xonos_unconfirmed: str | None = None\n",
        "        self._xonos_unconfirmed: str | None = None\n"
        "        # Xonos: il brano che Xonos ha messo nella coda di Soloist (_SingleTrackRun._xonos_preload), e la sessione:\n"
        "        # con la sessione finisce anche la sua coda\n"
        "        self._xonos_queued_uri: str | None = None\n"
        "        self._xonos_queued_run: _SingleTrackRun | None = None\n",
    )
    _replace(
        backend,
        "        self._xonos_connect = False\n        self._xonos_holding = False\n",
        "        self._xonos_connect = False\n        self._xonos_holding = False\n"
        "        # Xonos: quello che Soloist dice di sé, qualsiasi brano: il brano, lo stato e la coda (l'ultimo queue_changed,\n"
        "        # come (provenienza, uri)); e quello che serve al precarico del brano dopo (_soloist_preload in prepare.py)\n"
        "        self._xonos_engine_uri: str | None = None\n"
        "        self._xonos_engine_status: str | None = None\n"
        "        self._xonos_upcoming: list[tuple[str, str]] | None = None\n"
        "        self._xonos_queue_seen = asyncio.Event()\n"
        "        self._xonos_queue_silent = False\n"
        "        self._xonos_details = streamdetails\n"
        "        self._xonos_preload_task: asyncio.Task[None] | None = None\n"
        "        self._xonos_preload_fails = 0\n"
        "        self._xonos_preload_off = False\n"
        "        self._xonos_stale_said = False\n"
        '        self._xonos_switch_mode = "avvio"\n'
        "        # Xonos: cosa succede dentro un cambio di brano, per una riga del registro (_xonos_timeline_end)\n"
        "        self._xonos_timeline: list[str] | None = None\n"
        "        self._xonos_command_at = 0.0\n"
        "        self._xonos_position_said = False\n"
        "        self._xonos_levels: list[tuple[float, float | None]] = []\n"
        "        self._xonos_level_task: asyncio.Task[None] | None = None\n"
        "        self._xonos_wakeups_from: tuple[str, int] | None = None\n"
        "        # Xonos: la posizione più avanti di questo brano detta da Soloist fuori dai cambi (_xonos_restarted)\n"
        "        self._xonos_peak_ms: int | None = None\n",
    )
    _replace(
        backend,
        '    async def _handle_event(self, event: SoloistEvent) -> None:\n'
        '        """Track what the engine is doing with this run\'s one item."""\n'
        "        data = event.data\n",
        '    async def _handle_event(self, event: SoloistEvent) -> None:\n'
        '        """Track what the engine is doing with this run\'s one item."""\n'
        "        data = event.data\n"
        "        # Xonos: il brano su cui è Soloist (anche non chiesto), il suo stato e la sua coda, e i tempi di un cambio\n"
        "        self._xonos_note(data)\n",
    )
    _replace(
        backend,
        '        """Confirm an armed seek once the engine reports at (or past) its target."""\n'
        "        self._position_ms = position_ms\n",
        '        """Confirm an armed seek once the engine reports at (or past) its target."""\n'
        "        xonos_peak = self._xonos_peak_ms  # Xonos: prima di prendere la posizione nuova\n"
        "        self._position_ms = position_ms\n"
        "        if self._xonos_parked or self._xonos_switching:\n"
        "            # Xonos: in pausa o mentre cambia brano le posizioni possono essere del brano di prima: si riparte\n"
        "            self._xonos_peak_ms = None\n"
        "        elif self._xonos_restarted(xonos_peak, position_ms):\n"
        "            # Xonos: Soloist ha ricominciato lo stesso brano alla sua fine (era anche nella sua coda): è finito\n"
        '            self.logger.debug("Engine started %s over at its end; it has ended", self.spotify_uri)\n'
        "            self._xonos_item_ended()\n"
        "            return\n"
        "        else:\n"
        "            self._xonos_peak_ms = position_ms if xonos_peak is None else max(xonos_peak, position_ms)\n",
    )
    _replace(
        backend,
        "        if isinstance(data, SoloistTrackChanged):\n"
        "            if data.item is not None and data.item.uri:\n"
        "                self._observe_item(data.item.uri, _decorated_duration_ms(data.item))\n"
        "            return\n",
        "        if isinstance(data, SoloistTrackChanged):\n"
        "            if data.item is not None and data.item.uri == self.spotify_uri and self._xonos_track_restarted():\n"
        "                # Xonos: lo stesso brano annunciato di nuovo alla sua fine: Soloist è passato alla copia in coda\n"
        '                self.logger.debug("Engine started %s again from its queue; it has ended", self.spotify_uri)\n'
        "                self._xonos_item_ended()\n"
        "                return\n"
        "            if data.item is not None and data.item.uri:\n"
        "                self._observe_item(data.item.uri, _decorated_duration_ms(data.item))\n"
        "            return\n",
    )
    _replace(
        backend,
        "        xonos_stop = time.monotonic()  # Xonos: il tempo per fermarlo, per il registro\n"
        "        self._stopped = True\n",
        "        xonos_stop = time.monotonic()  # Xonos: il tempo per fermarlo, per il registro\n"
        "        self._stopped = True\n"
        "        # Xonos: la coda di questo Soloist se ne va con lui\n"
        "        self.backend._xonos_forget_queued(self)\n",
    )


def _phone_buffers(root: Path) -> None:
    """
    Due attese e un costo del percorso dell'audio che sul telefono non servono.

    - Il "banco" di 5 s: con una sorgente in tempo reale a un flusso solo (Soloist), Music Assistant aspetta 5 s di audio
      invece di 1 prima di far partire un brano a cui manca meno di un minuto (riprese e seek nell'ultimo minuto, brani
      corti). È per le casse Sonos, che si spengono nel buco di circa 1,5 s al brano dopo. Soloist rende a 1,1x: 5 s
      sono circa 3,5 s di attesa in più; con 2 s circa 0,8. Sul telefono il lettore è Xonos (o myCar) via Sendspin, che
      regge un avvio in ritardo, e in modalità Spotify Connect il brano dopo parte in circa 1 s: 2 s bastano.
    - ffmpeg che svuota l'uscita a ogni pacchetto: per i brani Music Assistant legge pezzi da 1 s, e ffmpeg scriveva
      nel tubo a ogni pacchetto (pochi ms di audio), cioè tante scritture e tanti risvegli di Python per niente. Con
      "-flush_packets 0" scrive a blocchi (32 KB, circa 0,17 s); le sorgenti dal vivo (pezzi da 20 ms) restano com'erano.
    """
    _replace(
        root / "controllers" / "streams" / "constants.py",
        "REALTIME_COLD_START_BANK: Final[int] = 5\n",
        "# Xonos: sul telefono 2 s invece di 5. Il lettore è Xonos (o myCar) via Sendspin, non una cassa Sonos che si spegne\n"
        "# nel buco al brano dopo; e con Soloist (1,1x) 5 s erano circa 3,5 s di attesa in più (_phone_buffers in prepare.py)\n"
        "REALTIME_COLD_START_BANK: Final[int] = 2\n",
    )
    _replace(
        root / "controllers" / "streams" / "audio.py",
        "        ffmpeg_proc = FFMpeg(\n"
        "            audio_input=audio_source,\n"
        "            input_format=ffmpeg_input_format,\n"
        "            output_format=pcm_format,\n"
        "            filter_params=filter_params,\n"
        "            extra_input_args=extra_input_args,\n"
        "            collect_log_history=True,\n"
        "            loglevel=ffmpeg_loglevel,\n"
        "        )\n",
        "        ffmpeg_proc = FFMpeg(\n"
        "            audio_input=audio_source,\n"
        "            input_format=ffmpeg_input_format,\n"
        "            output_format=pcm_format,\n"
        "            filter_params=filter_params,\n"
        "            extra_input_args=extra_input_args,\n"
        "            # Xonos: con pezzi da 1 s (i brani) ffmpeg scrive a blocchi, non a ogni pacchetto: meno risvegli di\n"
        "            # Python; le sorgenti dal vivo (pezzi da 20 ms) restano com'erano (_phone_buffers in prepare.py)\n"
        '            extra_output_args=["-flush_packets", "0"] if chunk_seconds >= 0.5 else None,\n'
        "            collect_log_history=True,\n"
        "            loglevel=ffmpeg_loglevel,\n"
        "        )\n",
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
# Xonos: il primo brano di un Soloist appena partito in modalità Spotify Connect. Soloist dice il brano chiesto solo
# quando ne ha l'audio, e a freddo sul telefono dell'utente ci metteva 5,2-5,5 s dopo l'accesso (0.1.56): con i 5 s dei
# salti l'avvio non riusciva mai e la modalità Spotify Connect si spegneva per 30 minuti (ogni avanti da capo, 7 s).
# Se non parte nemmeno così, la modalità si spegne subito, prima del ripiego (che dopo tanto non sempre entra nei 15 s
# che Music Assistant dà al buffer: senza, ogni brano riproverebbe e fallirebbe da capo)
_XONOS_CONNECT_START_S: Final[float] = 8.0
# Xonos: il nome del telefono nei dispositivi Spotify Connect dell'account
_XONOS_DEVICE_NAME: Final[str] = "Xonos"
# Xonos: in modalità Spotify Connect, una pausa a meno di tanto dalla fine (posizione di Soloist, o audio letto) è la
# fine del brano, non una pausa chiesta dall'app di Spotify
_XONOS_END_MS: Final[int] = 3000
_XONOS_END_READ_S: Final[float] = 5.0
# Xonos: un brano ricominciato da Soloist alla sua fine si riconosce anche dall'audio letto (le posizioni di Soloist
# possono arrivare di rado), solo se davanti al brano c'erano almeno tanti secondi: nei brani corti una posizione
# normale può restare indietro di oltre 1 s rispetto a quanto letto (il primo secondo arriva tutto insieme)
_XONOS_READ_FLOOR_S: Final[float] = 15.0
# Xonos: per quanto, dopo un avvio o un cambio di brano, le righe di Soloist vanno nel registro (sennò solo in debug)
_XONOS_ENGINE_LOG_S: Final[float] = 15.0
# Xonos: in modalità Spotify Connect, audio letto oltre la durata del brano di tanto: il brano ricomincia (ripeti acceso da
# fuori), ed è finito
_XONOS_OVERRUN_S: Final[float] = 5.0
# Xonos: in modalità Spotify Connect il brano dopo della coda di Music Assistant va anche nella coda di Soloist
# (add_to_queue): "avanti" diventa skip_next su un brano che Soloist ha già, e a fine brano Soloist ci passa da solo
# (_soloist_preload in prepare.py). False: niente coda di Soloist, ogni brano con play(uri), come prima.
_XONOS_PRELOAD: Final[bool] = True
# Xonos: quanto dopo l'inizio di un brano si mette in coda il brano dopo: la coda di Music Assistant è già a posto, e
# una raffica di salti non mette in coda brani per niente
_XONOS_PRELOAD_AFTER_S: Final[float] = 1.0
# Xonos: quanto si aspetta la coda di Soloist (get_queue, che risponde con queue_changed)
_XONOS_QUEUE_WAIT_S: Final[float] = 1.0
# Xonos: dopo skip_next, quanto si aspetta che Soloist dica su quale brano è passato (sennò play del brano chiesto); e
# quanto, su quel brano, che dica di suonare prima di chiedergli di riprendere (un Soloist in pausa può restarci)
_XONOS_QUEUE_LAND_S: Final[float] = 2.0
_XONOS_QUEUE_PLAY_S: Final[float] = 0.3
# Xonos: durante un cambio di brano, ogni quanto si guarda quanto audio tiene PulseAudio (per il registro)
_XONOS_LEVEL_POLL_S: Final[float] = 0.02
# Xonos: le parole del registro per gli stati di Soloist e per i modi di cambiare brano (_SingleTrackRun.xonos_mode)
_XONOS_STATUS_WORDS: Final[dict[str, str]] = {
    "playing": "suona",
    "paused": "in pausa",
    "stopped": "fermo",
    "idle": "fermo",
}
_XONOS_SWITCH_WORDS: Final[dict[str, str]] = {
    "salto": "il salto",
    "coda": "l'avanti col brano in coda",
    "pronto": "la ripresa del brano già pronto",
    "ripresa": "la ripresa",
    "avvio": "l'avvio",
}


def _xonos_now() -> float:
    """Xonos: i secondi da quando il telefono è acceso, sospensione compresa (time.monotonic() su Android si ferma)."""
    return time.clock_gettime(time.CLOCK_BOOTTIME)


def _xonos_network() -> str:
    """
    Xonos: la rete del telefono e le opzioni del DNS di Soloist, dal file del DNS che scrive Xonos (bloccante).

    La prima riga del file ("# xonos: ...") dice solo tipi e numeri, mai indirizzi: così dal registro di una persona
    si vede su quale rete un avvio da capo è stato lento.
    """
    path = os.environ.get("XONOS_DNS_FILE")
    if not path:
        return "rete non detta"
    try:
        with open(path, encoding="utf-8") as file:
            lines = file.read().splitlines()
    except OSError:
        return "rete non detta"
    said = next((line[len("# xonos: ") :] for line in lines if line.startswith("# xonos: ")), "rete non detta")
    options = next((line.split(None, 1)[1] for line in lines if line.startswith("options ")), "nessuna")
    return f"{said}, opzioni del DNS {options}"


def _xonos_input_latency(listing: str | None, sink_name: str) -> float | None:
    """
    Xonos: dall'elenco di PulseAudio (list-sink-inputs), i ms di audio che tiene chi suona su questo sink.

    È la "current latency" del flusso: il buffer del flusso più quello del sink. None se nessuno suona sul sink.
    Dell'elenco si legge solo questo: le proprietà del flusso possono avere il titolo del brano.
    """
    if not listing:
        return None
    on_sink = False
    for raw in listing.splitlines():
        line = raw.strip().lstrip("*").strip()
        if line.startswith("index:"):
            on_sink = False
        elif line.startswith("sink:"):
            on_sink = line.endswith(f"<{sink_name}>")
        elif on_sink and line.startswith("current latency:"):
            try:
                return float(line.split(":", 1)[1].split()[0])
            except (IndexError, ValueError):
                return None
    return None


def _xonos_wakeups(proc: AsyncProcess | None) -> tuple[str, int] | None:
    """
    Xonos: quante volte il processo di Soloist si è fermato e ripreso (cambi di contesto), e il suo nome.

    Sul telefono il processo è proot, che ferma Soloist a ogni chiamata di sistema che segue: i suoi risvegli dicono
    quanto lavora. None se non si legge.
    """
    pid = proc.proc.pid if proc is not None and proc.proc is not None else None
    if pid is None:
        return None
    try:
        status = Path(f"/proc/{pid}/status").read_text()
        program = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\\0", 1)[0]
    except OSError:
        return None
    count = 0
    for line in status.splitlines():
        if line.startswith(("voluntary_ctxt_switches:", "nonvoluntary_ctxt_switches:")):
            count += int(line.split(":", 1)[1])
    return ("proot" if b"proot" in os.path.basename(program) else "processo"), count
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
                # e quanto se ne aspettava: la durata del brano meno il punto da cui è partito (un pezzo che manca è
                # la fine del brano persa al cambio, per esempio audio ancora in PulseAudio quando Soloist è passato oltre)
                expected = (
                    "%.1f s" % ((run._duration_ms - run._seek_target_ms) / 1000) if run._duration_ms else "non si sa"
                )
                self.logger.info(
                    "Soloist: brano finito, %.1f s di audio (atteso %s) in %.1f s dal sink ripartito (%.2fx)",
                    run._delivered / _BYTES_PER_SECOND,
                    expected,
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
        In modalità Spotify Connect il brano dopo può essere già in Soloist (_soloist_preload in prepare.py): "pronto"
        (Soloist ci è passato da solo a fine brano) conta come una ripresa, "coda" (skip_next) come un salto.
        """
        mode = run.xonos_mode(spotify_uri)
        what = "ripresa" if mode in ("ripresa", "pronto") else "salto"
        if what == "salto" and not run._xonos_connect:
            return False
        if run._xonos_connect and await asyncio.to_thread(self._xonos_foreign_account):
            self.logger.info("Soloist: la sessione accesa non si riusa (c'è un altro account Spotify)")
            self._xonos_forget_queued()
            return False
        if self._xonos_failures[what] >= _XONOS_TRIES:
            if _xonos_now() - self._xonos_failed_at[what] < self._xonos_retry_after(what):
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
            # conta solo se poi il brano parte da capo (_xonos_fresh_run): un brano che Spotify non dà, o la rete che
            # manca, non dicono niente della sessione accesa
            self._xonos_unconfirmed = what
            self.logger.info(
                "Soloist: %s sulla sessione accesa non riuscito (%s), riparte da capo", what, err or type(err).__name__
            )
            return False
        self._xonos_failures[what] = 0
        self._xonos_retries[what] = 0
        spent = time.monotonic() - started
        # come è andato davvero (un avanti col brano in coda può finire con play, se Soloist è andato altrove): le due
        # righe si confrontano nel registro
        if run._xonos_switch_mode == "coda":
            self.logger.info("Soloist: avanti col brano in coda (precaricato) in %.2f s (%s)", spent, steps)
        elif run._xonos_switch_mode == "pronto":
            self.logger.info("Soloist: brano dopo già pronto in Soloist, ripresa in %.2f s (%s)", spent, steps)
        else:
            self.logger.info("Soloist: %s sulla sessione accesa in %.2f s (%s)", what, spent, steps)
        # il brano dopo nella coda di Soloist, per il prossimo avanti (solo in modalità Spotify Connect)
        run.xonos_preload_soon()
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
        if await asyncio.to_thread(self._xonos_foreign_account):
            # Soloist ha dentro l'accesso di un altro account: non si suona finché non si rifà l'abbinamento
            self._xonos_unconfirmed = None
            self._xonos_forget_queued()
            self.logger.warning(
                "Soloist: un altro account Spotify è entrato in Soloist dalla rete locale; va rifatto l'abbinamento"
            )
            error = LoginFailed(
                "Another Spotify account signed in to Spotify Soloist from the local network",
                translation_key="soloist_pairing_required",
                translation_owner="provider.spotify",
            )
            if self.provider.available:
                self.provider.unload_with_error(error)
            raise error
        connect_error: Exception | None = None
        if _XONOS_CONNECT and _xonos_now() >= self._xonos_connect_blocked_until:
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
                    self._xonos_unconfirmed = None
                    raise
                connect_error = err
                if run._xonos_slow_start:
                    # Soloist acceso ha preso il play ma non è partito in tempo: niente modalità Spotify Connect per un
                    # po' già ora, perché il ripiego dopo tanto può non entrare nei 15 s che Music Assistant dà al buffer
                    # (sennò ogni brano riproverebbe la modalità Spotify Connect e fallirebbe da capo)
                    self._xonos_block_connect(err)
                    connect_error = None
                self.logger.info(
                    "Soloist: in modalità Spotify Connect non parte (%s), provo un processo per brano",
                    err or type(err).__name__,
                )
            except BaseException:
                self._xonos_unconfirmed = None
                await run.stop()
                raise
            else:
                self._xonos_connect_fails = 0
                self._xonos_count_failure()
                return run
        run = _SingleTrackRun(self, spotify_uri, seek_position * 1000, streamdetails)
        try:
            await run.start()
        except BaseException:
            # non parte nemmeno così (brano non disponibile, niente rete...): non è colpa della modalità Spotify Connect
            # né della sessione accesa, e non si conta
            self._xonos_unconfirmed = None
            await run.stop()
            raise
        self._xonos_count_failure()
        if connect_error is not None:
            # un processo per brano parte e la modalità Spotify Connect no: per un po' un processo per brano
            self._xonos_block_connect(connect_error)
        return run

    def _xonos_block_connect(self, err: BaseException) -> None:
        """Xonos: per un po' un processo per brano (_XONOS_CONNECT_RETRY_S, raddoppiato a ogni fallimento, fino a 16 ore)."""
        wait = _XONOS_CONNECT_RETRY_S * 2 ** min(self._xonos_connect_fails, 5)
        self._xonos_connect_fails += 1
        self._xonos_connect_blocked_until = _xonos_now() + wait
        self.logger.warning(
            "Soloist: in modalità Spotify Connect non parte (%s); per %.0f minuti un processo per brano",
            err or type(err).__name__,
            wait / 60,
        )

    def _xonos_count_failure(self) -> None:
        """Xonos: il salto (o la ripresa) di prima non riuscito conta: dopo è partito da capo, quindi era la sessione."""
        what = self._xonos_unconfirmed
        self._xonos_unconfirmed = None
        if what is None:
            return
        self._xonos_failures[what] += 1
        self._xonos_failed_at[what] = _xonos_now()
        if self._xonos_failures[what] >= _XONOS_TRIES:
            self.logger.warning(
                "Soloist: %s sulla sessione accesa non riesce, per %.0f minuti si riparte da capo",
                what,
                self._xonos_retry_after(what) / 60,
            )

    def _xonos_foreign_account(self) -> bool:
        """
        Xonos: se nella cartella dei dati di Soloist è entrato un altro account Spotify (bloccante).

        In modalità Spotify Connect Soloist si annuncia nella rete locale, e un altro account può prenderlo dalla sua app
        ("any Spotify account may claim it", README del plugin Spotify Connect di Music Assistant). Soloist tiene ogni
        account in settings/Users/<nome>-user: quelli del primo controllo sono l'abbinamento della persona, e uno nuovo
        (o più di uno) vuol dire l'accesso di un altro.
        """
        accounts = frozenset(_soloist_session_accounts(self._data_dir))
        if self._xonos_accounts is None:
            self._xonos_accounts = accounts
        return len(accounts) > 1 or not accounts <= self._xonos_accounts

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

    def _xonos_forget_queued(self, run: _SingleTrackRun | None = None) -> None:
        """Xonos: il brano messo in coda da Xonos non c'è più (o non conta più): di questa sessione, o di qualsiasi."""
        if run is None or self._xonos_queued_run is run:
            self._xonos_queued_uri = None
            self._xonos_queued_run = None

    def _xonos_next_uri(self, run: _SingleTrackRun) -> str | None:
        """
        Xonos: l'uri Spotify del brano che Music Assistant suona dopo quello di questa sessione.

        Solo se è un brano di Spotify di questo servizio (il collegamento che Music Assistant prova per primo: quello di
        qualità più alta, come _get_streamdetail_candidates), mai con ripeti un brano; con ripeti tutto, a fine coda è il
        primo. None in tutti gli altri casi: allora la coda di Soloist resta com'è.
        """
        details = run._xonos_details
        queue_id = run.queue_id
        if details is None or queue_id is None or details.media_type != MediaType.TRACK:
            return None
        queues = self.mass.player_queues
        queue = queues.get(queue_id)
        data = queues.queue_data_or_none(queue_id)
        if queue is None or data is None or queue.repeat_mode == RepeatMode.ONE:
            return None
        items = data.items
        # il brano di questa sessione: di solito quello nel buffer o quello in corso, sennò si cerca in tutta la coda
        index = None
        for candidate in (queue.index_in_buffer, queue.current_index):
            if candidate is not None and 0 <= candidate < len(items):
                found = items[candidate].streamdetails
                if found is details or (found is not None and found.uri == details.uri):
                    index = candidate
                    break
        if index is None:
            index = next((i for i, item in enumerate(items) if item.streamdetails is details), None)
        if index is None:
            return None
        following = queues.get_next_item(queue_id, index)
        if following is None or following.media_item is None or following.media_type != MediaType.TRACK:
            return None
        mappings = sorted(
            (mapping for mapping in following.media_item.provider_mappings if mapping.available),
            key=lambda mapping: mapping.quality or 0,
            reverse=True,
        )
        if not mappings or mappings[0].provider_instance != self.provider.instance_id:
            return None
        return f"spotify:track:{mappings[0].item_id}"

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
        # un brano dopo che stava per andare nella coda di Soloist non ci va più: lo decide il prossimo brano
        await self._xonos_stop_preload()
        # Prima il sink: da qui nel tubo non entra più niente. La conferma della pausa è un giro attraverso proot: non
        # si aspetta qui, dove ferma il salto, ma al cambio di brano dopo (xonos_switch), che di solito la trova già
        # arrivata. Se non arriva, la sessione non si riusa.
        await self._set_sink(running=False)
        self._xonos_send_pause()
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
        if (
            pause is not None
            and pause.done()
            and (pause.cancelled() or pause.exception() is not None)
            and self._engine_playing
        ):
            return "pausa non riuscita"
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

    def xonos_mode(self, spotify_uri: str) -> str:
        """
        How the paused engine gets to this item.

        "ripresa": the same item, resumed at the asked point. "pronto" (Spotify Connect mode): at the end of the item
        before the engine moved on to this one by itself (the item Xonos put in its queue) and was paused at its start:
        it is resumed too. "coda" (Spotify Connect mode): the item Xonos put in the engine's queue, still first in it:
        skip_next, which finds it loaded. "salto": play of the uri.
        """
        if spotify_uri == self.spotify_uri and not self._item_over:
            return "ripresa"
        if self._xonos_connect and self._item_over and spotify_uri == self._xonos_engine_uri:
            return "pronto"
        if self._xonos_connect and self._xonos_queued_first(spotify_uri):
            return "coda"
        return "salto"

    async def xonos_switch(
        self, spotify_uri: str, seek_position_ms: int, streamdetails: StreamDetails | None
    ) -> str:
        """
        Play the next item on the paused engine: the same one (a resume) or another one (a jump).

        As at a fresh start the sink stays suspended until the engine plays the asked item at the asked position, so
        no audio of the item before reaches the new consumer; what that item left in the FIFO is read away first.
        Return how long each step took, for the log. How the engine got there is in _xonos_switch_mode (xonos_mode;
        "coda" becomes "salto" when the engine did not move to the queued item).
        """
        client = self._client
        assert client is not None
        mode = self.xonos_mode(spotify_uri)
        self._xonos_switch_mode = mode
        # after the item ended (Spotify Connect mode) the engine is on another one: the same uri is played again,
        # unless the engine is on the asked one already ("pronto"), which is resumed as the same item
        same = mode in ("ripresa", "pronto")
        marks = [time.monotonic()]
        self._xonos_switching = True
        self._xonos_seek_sent = False
        self._xonos_previous_uri = self.spotify_uri
        self.spotify_uri = spotify_uri
        self.media_key = streamdetails.uri if streamdetails is not None else None
        self.queue_id = streamdetails.queue_id if streamdetails is not None else None
        self._xonos_details = streamdetails
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
        # Xonos: la posizione più avanti del brano di prima non vale per questo (_xonos_restarted)
        self._xonos_peak_ms = None
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
            try:
                await pause
            except Exception:
                # a pause may get no ack (Music Assistant's Spotify Connect provider tolerates that too): what counts is
                # that the engine reports not playing
                if self._engine_playing:
                    raise
        if mode == "coda" and self._xonos_engine_uri == spotify_uri:
            # Il brano di prima è finito proprio adesso e Soloist è passato da solo al brano in coda (prima della pausa,
            # che l'ha fermato lì): skip_next andrebbe al brano ancora dopo. Si riprende da lì, come a fine brano.
            mode = self._xonos_switch_mode = "pronto"
            same = True
            self._started.set()
        marks.append(time.monotonic())
        try:
            if same:
                await self._xonos_drain()
                marks.append(time.monotonic())
                # Soloist in pausa è più avanti di quanto si è sentito (il tubo si legge a 1,1x): il seek al punto
                # chiesto parte prima di riprendere, così non rende audio dal punto sbagliato. Lo conferma solo una
                # posizione arrivata dopo il seek e vicina al punto (_observe_position); se in pausa non la dice, il
                # seek si rifà dopo la ripresa. Anche per il brano già pronto: Soloist ne ha suonato un pezzo prima
                # della pausa, e il seek butta quello che aveva già pronto.
                self._xonos_timeline_start()
                self._xonos_seek_sent = True
                await asyncio.wait_for(client.seek(seek_position_ms, await_result=True), _XONOS_COMMAND_S)
                with suppress(TimeoutError):
                    await asyncio.wait_for(self._seek_confirmed.wait(), _XONOS_SEEK_PAUSED_S)
                await asyncio.wait_for(client.resume(await_result=True), _XONOS_COMMAND_S)
            elif self._sink_running:
                # il sink non è sospeso (non dovrebbe succedere): prima si svuota il tubo, poi il comando, come prima
                await self._xonos_drain()
                marks.append(time.monotonic())
                await self._xonos_jump(client, spotify_uri)
            else:
                # Il sink è sospeso (xonos_park): nel tubo non entra niente di nuovo. Il comando parte subito, e il
                # tubo si svuota mentre Soloist carica il brano; "comando" è quanto resta da aspettare dopo il tubo.
                jump = asyncio.ensure_future(self._xonos_jump(client, spotify_uri))
                # se il cambio si ferma prima di aspettarlo, un suo errore non resta "mai letto"
                jump.add_done_callback(lambda task: task.cancelled() or task.exception())
                try:
                    await self._xonos_drain()
                    marks.append(time.monotonic())
                    await jump
                finally:
                    jump.cancel()
            marks.append(time.monotonic())
            await self._xonos_await_playing(same, marks[0])
            marks.append(time.monotonic())
            if (seek_position_ms or same) and not self._seek_confirmed.is_set():
                self._xonos_seek_sent = True
                await self._cold_seek(client, seek_position_ms)
            if self._error is not None:
                raise AudioError(self._error)
        except BaseException:
            self._xonos_timeline_end(failed=True)
            raise
        self._xonos_timeline_end()
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

    async def _xonos_jump(self, client: SoloistClient, spotify_uri: str) -> None:
        """
        Ask the engine for another item: skip_next onto the item Xonos queued ("coda"), or play of the uri.

        After skip_next the engine has to say it moved to the queued item; if it moves elsewhere (the queue was not what
        Xonos knew) or does not move, the item is played with play, as without the queue.
        """
        self._xonos_timeline_start()
        if self._xonos_switch_mode == "coda":
            before = self._xonos_engine_uri
            try:
                await asyncio.wait_for(client.skip_next(await_result=True), _XONOS_QUEUE_LAND_S)
            except TimeoutError:
                # niente conferma (un errore, o Soloist che non lo accetta in pausa): si va con play, senza aspettare
                landed = None
            else:
                self._xonos_mark("conferma")
                landed = await self._xonos_await_moved(before)
            if landed == spotify_uri:
                self._xonos_preload_fails = 0
                # skip_next on a paused engine may leave it paused on the new item: then it is resumed
                if not await self._xonos_playing_within(_XONOS_QUEUE_PLAY_S) and self._xonos_engine_status == "paused":
                    self._xonos_mark("ripresa")
                    await asyncio.wait_for(client.resume(await_result=True), _XONOS_COMMAND_S)
                return
            self._xonos_queue_missed(landed)
            self._xonos_switch_mode = "salto"
            self._xonos_mark("play")
        await asyncio.wait_for(client.play(spotify_uri, await_result=True), _XONOS_COMMAND_S)
        self._xonos_mark("conferma")
        # dopo un play non si sa se Spotify tiene la coda di prima: la si chiede di nuovo (precarico)
        self._xonos_upcoming = None

    async def _xonos_await_moved(self, before: str | None) -> str | None:
        """After skip_next: the item the engine says it moved to, or None if it does not move in time."""
        deadline = time.monotonic() + _XONOS_QUEUE_LAND_S
        while time.monotonic() < deadline:
            if self._xonos_engine_uri != before:
                return self._xonos_engine_uri
            if self._error is not None or self._engine_exited or not proc_running(self._proc):
                raise AudioError(self._error or "Spotify Soloist stopped while changing item")
            await asyncio.sleep(_XONOS_POLL_S)
        return None

    async def _xonos_playing_within(self, seconds: float) -> bool:
        """Whether the engine reports playing the asked item within this time."""
        deadline = time.monotonic() + seconds
        while not (self._started.is_set() and self._engine_playing):
            if time.monotonic() >= deadline or self._error is not None:
                return False
            await asyncio.sleep(_XONOS_POLL_S)
        return True

    async def _xonos_await_playing(self, same: bool, begun: float, limit: float | None = None) -> None:
        """
        Wait for the engine to report that it plays the asked item.

        :param same: Whether the item is the one the engine paused on (a resume).
        :param begun: When the switch began (time.monotonic()).
        :param limit: The whole wait, in place of the switch limits (the first item of a fresh Spotify Connect run).
        """
        while not (self._started.is_set() and self._engine_playing):
            if self._error is not None or self._engine_exited or not proc_running(self._proc):
                raise AudioError(self._error or "Spotify Soloist stopped while changing item")
            # tutto il cambio ha _JUMP_TIMEOUT_S; _XONOS_SWITCH_S quando Soloist ha già detto il brano chiesto (salto)
            # o il punto chiesto (ripresa), cioè mentre lo carica
            loaded = self._seek_confirmed.is_set() if same else self._started.is_set()
            if limit is None:
                limit_s = _XONOS_SWITCH_S if loaded else _JUMP_TIMEOUT_S
            else:
                limit_s = limit
            if time.monotonic() - begun >= limit_s:
                raise AudioError("Spotify Soloist did not start the asked item in time")
            await asyncio.sleep(_XONOS_POLL_S)

    async def _xonos_connect_play(self, client: SoloistClient) -> None:
        """
        Spotify Connect mode: the engine started with no item; shuffle and repeat off, then play this run's item.

        A bare play (no activate) plays locally without a Connect transfer, as the Spotify Connect provider of Music
        Assistant notes: the Spotify apps are not handed this playback, as with --single-track.
        """
        begun = time.monotonic()
        self._xonos_switch_mode = "avvio"
        self._xonos_timeline_start()
        try:
            try:
                await asyncio.wait_for(client.play(self.spotify_uri, await_result=True), _XONOS_COMMAND_S)
            except TimeoutError:
                raise AudioError("Spotify Soloist did not confirm play") from None
            self._xonos_mark("conferma")
            # shuffle and repeat off after the play (before it the engine has no item and may refuse them with no
            # ack), while the item loads: they only matter at its end, and one refused does not stop the Spotify
            # Connect mode
            self._spawn_task(self._xonos_connect_options(client))
            try:
                # a fresh engine names the asked item only once it has its audio: a cold load, not a switch
                await self._xonos_await_playing(False, begun, _XONOS_CONNECT_START_S)
            except AudioError:
                if self._error is None and not self._engine_exited and proc_running(self._proc):
                    # Xonos: Soloist è acceso e ha preso il play, ma il brano non è partito in tempo
                    self._xonos_slow_start = True
                raise
        except BaseException:
            self._xonos_timeline_end(failed=True)
            raise
        self._xonos_timeline_end()
        self._xonos_switching = False
        # il brano dopo nella coda di Soloist, per il prossimo avanti
        self.xonos_preload_soon()

    async def _xonos_connect_options(self, client: SoloistClient) -> None:
        """Spotify Connect mode: shuffle and repeat off; one not confirmed only goes in the log."""
        for name, send in (
            ("set_shuffle", client.set_shuffle),
            ("set_repeat_track", client.set_repeat_track),
            ("set_repeat_context", client.set_repeat_context),
        ):
            try:
                await asyncio.wait_for(send(False, await_result=True), _XONOS_COMMAND_S)
            except Exception as err:  # noqa: BLE001 - solo nel registro
                self.logger.info("Soloist: %s non confermato (%s)", name, err or type(err).__name__)

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
        """Suspend the sink and pause the engine after its item, if it plays on (Spotify Connect mode)."""
        await self._set_sink(running=False)
        if not self._xonos_parked:
            self._xonos_send_pause()

    def _xonos_send_pause(self) -> None:
        """
        Pause the engine, once and only if it plays: a pause to an engine that stopped by itself may get no ack.

        The ack is checked by xonos_unusable and xonos_switch, not here: an error does not fail the run.
        """
        client = self._client
        if self._xonos_pause is not None or not self._engine_playing or client is None or not client.connected:
            return
        pause = asyncio.ensure_future(asyncio.wait_for(client.pause(await_result=True), _XONOS_COMMAND_S))
        # an error is looked at by xonos_unusable or xonos_switch; here only so asyncio does not report it as lost
        pause.add_done_callback(lambda task: task.cancelled() or task.exception())
        self._xonos_pause = pause
        # stop() cancels it, if the engine goes first
        self._tasks.append(pause)

    def _xonos_overrun(self) -> bool:
        """Whether more audio than the item lasts was read: the engine started it over (Spotify Connect mode)."""
        if not self._duration_ms:
            return False
        read_s = self._read_bytes / _BYTES_PER_SECOND
        return read_s > (self._duration_ms - self._seek_target_ms) / 1000 + _XONOS_OVERRUN_S

    def _xonos_restarted(self, peak_ms: int | None, position_ms: int) -> bool:
        """
        Whether the engine started this same item over at its end (Spotify Connect mode): the item is over.

        It happens when the item was also in the engine's queue (played with play(uri) while queued): at its end the
        engine moves to the queued copy, with the same uri, maybe with no track change. Without this the run would hand
        over the item's opening again until _xonos_overrun (5 s more). It needs the item at its end, a jump back of at
        least a second to its start, and at least half of the item's audio already read (not wall time: the engine
        writes ahead and the first second comes at once), so a late position of the item before cannot look like a
        restart. The end is the furthest position seen for this item (outside switches) or, as the engine may report
        positions rarely, the audio read (the engine is never behind it), the latter only when at least
        _XONOS_READ_FLOOR_S were ahead: on a short item a normal position can lag what was read by more than a second.
        """
        if (
            not self._xonos_connect
            or not self._started.is_set()
            or self._item_over
            or self._xonos_parked
            or self._xonos_switching
            or not self._duration_ms
        ):
            return False
        expected_s = max(0.0, (self._duration_ms - self._seek_target_ms) / 1000)
        read_s = self._read_bytes / _BYTES_PER_SECOND
        if peak_ms is not None and peak_ms >= self._duration_ms - _XONOS_END_MS:
            reached = peak_ms
        elif expected_s >= _XONOS_READ_FLOOR_S and read_s >= expected_s - _XONOS_END_READ_S:
            # Xonos: nessuna posizione vicino alla fine (Soloist può darne di rado): vale l'audio letto
            reached = max(peak_ms or 0, self._seek_target_ms + int(read_s * 1000))
        else:
            return False
        return position_ms < _XONOS_END_MS and reached - position_ms >= 1000 and read_s >= expected_s / 2

    def _xonos_track_restarted(self) -> bool:
        """
        Whether a track change to this same item means the engine started it over (Spotify Connect mode).

        At the end of an item that was also in the engine's queue the engine announces the queued copy, with the same
        uri. It counts only near the item's end (_xonos_near_end) and with at least half of the item's audio already
        read, so the announcement at the start of the item, or a repeated one, is not taken for it.
        """
        if (
            not self._xonos_connect
            or not self._started.is_set()
            or self._item_over
            or self._xonos_parked
            or self._xonos_switching
            or not self._duration_ms
        ):
            return False
        expected_s = max(0.0, (self._duration_ms - self._seek_target_ms) / 1000)
        return self._xonos_near_end() and self._read_bytes / _BYTES_PER_SECOND >= expected_s / 2

    def _xonos_near_end(self) -> bool:
        """Whether the item is at its end: by the engine's position, or by the audio read (Spotify Connect mode)."""
        if not self._duration_ms:
            return False
        if self._position_ms is not None and self._position_ms >= self._duration_ms - _XONOS_END_MS:
            return True
        read_s = self._read_bytes / _BYTES_PER_SECOND
        return read_s >= (self._duration_ms - self._seek_target_ms) / 1000 - _XONOS_END_READ_S

    # ---- Xonos: il brano dopo nella coda di Soloist (_soloist_preload in prepare.py) ----

    def xonos_preload_soon(self) -> None:
        """
        In a moment, put Music Assistant's next item in the engine's queue (Spotify Connect mode only).

        Never with --single-track (the engine exits at its item's end), never after the queue failed this session.
        """
        if not (_XONOS_PRELOAD and self._xonos_connect) or self._xonos_preload_off or self._stopped:
            return
        if (old := self._xonos_preload_task) is not None:
            old.cancel()
            with suppress(ValueError):
                self._tasks.remove(old)
        self._xonos_preload_task = self._spawn_task(self._xonos_preload())

    async def _xonos_stop_preload(self) -> None:
        """Stop a preload on its way: the item changes, and with it the next one."""
        if (task := self._xonos_preload_task) is not None:
            self._xonos_preload_task = None
            await _cancel_and_join([task])
            with suppress(ValueError):
                self._tasks.remove(task)

    def _xonos_preloadable(self) -> bool:
        """Whether the run plays an item now, in Spotify Connect mode, with the engine's queue still usable."""
        client = self._client
        return (
            _XONOS_PRELOAD
            and self._xonos_connect
            and not self._xonos_preload_off
            and not (self._xonos_parked or self._xonos_switching or self._item_over or self._stopped)
            and self._error is None
            and client is not None
            and client.connected
        )

    async def _xonos_preload(self) -> None:
        """
        Put Music Assistant's next item in the engine's queue, so that "next" and the end of the item find it loaded.

        One entry at a time, and only Xonos's own: when the engine's queue already has an entry, nothing is added (an
        entry left from before cannot be removed: it only goes in the log, once). The engine plays a queued item before
        anything else at the end of the item, and skip_next goes to it.
        """
        backend = self.backend
        try:
            await asyncio.sleep(_XONOS_PRELOAD_AFTER_S)
            if not self._xonos_preloadable():
                return
            following = backend._xonos_next_uri(self)
            if following is None or following == self.spotify_uri:
                return
            if (
                backend._xonos_queued_run is self
                and backend._xonos_queued_uri == following
                and self._xonos_upcoming is not None
                and self._xonos_upcoming[:1] == [("queue", following)]
            ):
                # già in coda, e Soloist l'ha detto (per esempio dopo una pausa dello stesso brano)
                return
            begun = time.monotonic()
            upcoming = await self._xonos_ask_queue()
            if upcoming is None or not self._xonos_preloadable():
                return
            if backend._xonos_queued_run is self and ("queue", backend._xonos_queued_uri) not in upcoming:
                # il brano messo in coda prima non c'è più (Soloist l'ha tolto, per esempio con un play)
                backend._xonos_forget_queued(self)
            if any(source == "queue" for source, _uri in upcoming):
                if upcoming[0] == ("queue", following):
                    # è già il primo della coda (messo da Xonos prima): basta ricordarlo
                    backend._xonos_queued_uri = following
                    backend._xonos_queued_run = self
                    return
                if not self._xonos_stale_said:
                    self._xonos_stale_said = True
                    self.logger.info(
                        "Soloist: nella coda di Soloist c'è già un brano che non è il dopo di Music Assistant (rimasto da"
                        " prima): niente precarico finché Soloist non lo passa"
                    )
                return
            # ricordato prima di mandarlo: se il precarico si ferma a metà, il brano in coda resta di Xonos
            backend._xonos_queued_uri = following
            backend._xonos_queued_run = self
            # e l'elenco della coda di prima non vale più: senza, un avanti subito dopo (o una conferma che non arriva)
            # vedrebbe la coda vuota e farebbe play(uri), lasciando il brano in coda, che ripartirebbe alla sua fine
            self._xonos_upcoming = None
            client = self._client
            assert client is not None
            added: BaseException | None = None
            try:
                await asyncio.wait_for(
                    asyncio.shield(client.add_to_queue(following, await_result=True)), _XONOS_COMMAND_S
                )
            except Exception as err:  # noqa: BLE001 - si guarda la coda: forse è entrato lo stesso
                added = err
            upcoming = await self._xonos_ask_queue()
            if upcoming is not None and upcoming[:1] != [("queue", following)]:
                backend._xonos_forget_queued(self)
                if ("queue", following) in upcoming:
                    # entrato, ma skip_next non ci andrebbe: "avanti" usa play
                    self.logger.info("Soloist: il brano dopo è nella coda di Soloist ma non è il primo: avanti con play")
                else:
                    self._xonos_queue_failed(
                        f"add_to_queue: {added or type(added).__name__}" if added is not None else "brano non in coda"
                    )
                return
            if upcoming is None and added is not None:
                # né la conferma né la coda: non si sa se c'è, e non si conta su di lui
                backend._xonos_forget_queued(self)
                self._xonos_queue_failed(f"add_to_queue: {added or type(added).__name__}")
                return
            self.logger.info(
                "Soloist: il brano dopo è nella coda di Soloist (precarico in %.2f s%s)",
                time.monotonic() - begun,
                "" if upcoming is not None else ", coda non confermata",
            )
        except asyncio.CancelledError:
            raise
        except Exception as err:  # noqa: BLE001 - il precarico non deve fermare il brano
            self.logger.info("Soloist: precarico del brano dopo non riuscito (%s)", err or type(err).__name__)

    async def _xonos_ask_queue(self) -> list[tuple[str, str]] | None:
        """The engine's upcoming queue as (source, uri), from get_queue; None if it does not answer in time."""
        client = self._client
        if client is None or not client.connected:
            return None
        self._xonos_queue_seen.clear()
        await client.get_queue(5)
        try:
            await asyncio.wait_for(self._xonos_queue_seen.wait(), _XONOS_QUEUE_WAIT_S)
        except TimeoutError:
            if not self._xonos_queue_silent:
                self._xonos_queue_silent = True
                self.logger.info("Soloist: non risponde a get_queue in %.1f s, niente precarico", _XONOS_QUEUE_WAIT_S)
            return None
        return list(self._xonos_upcoming or [])

    def _xonos_queued_first(self, spotify_uri: str) -> bool:
        """
        Whether this item is the one Xonos put in the engine's queue, still there and first.

        Played by the engine, it left the queue (_xonos_note forgets it). The last listing the engine sent (if any) must
        still show it first: if something else is first, skip_next would not go to it.
        """
        backend = self.backend
        if self._xonos_preload_off or backend._xonos_queued_run is not self or backend._xonos_queued_uri != spotify_uri:
            return False
        upcoming = self._xonos_upcoming
        return upcoming is None or upcoming[:1] == [("queue", spotify_uri)]

    def _xonos_queue_missed(self, landed: str | None) -> None:
        """skip_next did not land on the queued item: play it with play; after _XONOS_TRIES of these, no more queue."""
        self.backend._xonos_forget_queued(self)
        self._xonos_queue_failed("Soloist è passato a un altro brano" if landed else "Soloist non è passato al brano")

    def _xonos_queue_failed(self, why: str) -> None:
        """The engine's queue did not do what it should: after _XONOS_TRIES times, no preload for this session."""
        self._xonos_preload_fails += 1
        if self._xonos_preload_fails >= _XONOS_TRIES:
            self._xonos_preload_off = True
            self.logger.warning(
                "Soloist: la coda di Soloist non va (%s, %d volte): per questa sessione niente precarico",
                why,
                self._xonos_preload_fails,
            )
        else:
            self.logger.info("Soloist: la coda di Soloist non ha fatto quello che serve (%s)", why)

    def _xonos_note(self, data: object) -> None:
        """
        What the engine says about itself, whatever the item: which track, its state, its queue.

        Also the moments of a switch for the log (_xonos_mark). Called for every event; an error here only goes in the
        log, never stops the events.
        """
        try:
            uri = None
            if isinstance(data, SoloistTrackChanged | SoloistPlaybackState) and data.item is not None:
                uri = data.item.uri or None
            if uri is not None and uri != self._xonos_engine_uri:
                self._xonos_engine_uri = uri
                self._xonos_mark("brano" if uri == self.spotify_uri else "altro brano")
                if self._xonos_upcoming and self._xonos_upcoming[0][1] == uri:
                    # the engine moved to the first of its queue: it is not in the queue anymore
                    self._xonos_upcoming = self._xonos_upcoming[1:]
                backend = self.backend
                if backend._xonos_queued_run is self and backend._xonos_queued_uri == uri:
                    backend._xonos_forget_queued(self)
            if isinstance(data, SoloistPlaybackState):
                if data.status != self._xonos_engine_status:
                    self._xonos_engine_status = data.status
                    self._xonos_mark(_XONOS_STATUS_WORDS.get(data.status, "stato " + data.status[:20]))
                if data.position is not None:
                    self._xonos_mark_position(data.position.position_ms)
            elif isinstance(data, SoloistPositionSync):
                self._xonos_mark_position(data.position.position_ms)
            elif isinstance(data, SoloistQueueChanged):
                self._xonos_upcoming = [
                    (str(entry.source), entry.item.uri)
                    for entry in data.upcoming
                    if entry.item is not None and entry.item.uri
                ]
                self._xonos_queue_seen.set()
        except Exception as err:  # noqa: BLE001 - solo nel registro
            self.logger.debug("Soloist: evento non letto da Xonos: %s", err)

    # ---- Xonos: cosa succede dentro un cambio di brano, per il registro ----

    def _xonos_timeline_start(self) -> None:
        """From the command on, what happens inside the switch goes in one line of the log (_xonos_timeline_end)."""
        self._xonos_command_at = time.monotonic()
        self._xonos_timeline = []
        self._xonos_position_said = False
        self._xonos_levels = []
        self._xonos_wakeups_from = _xonos_wakeups(self._proc)
        if self._xonos_level_task is not None:
            self._xonos_level_task.cancel()
        self._xonos_level_task = self._spawn_task(self._xonos_watch_levels(self._xonos_levels))

    def _xonos_mark(self, what: str) -> None:
        """A moment inside the switch, in seconds from the command (never a uri or a title)."""
        if (timeline := self._xonos_timeline) is not None and len(timeline) < 24:
            timeline.append("+%.2f %s" % (time.monotonic() - self._xonos_command_at, what))

    def _xonos_mark_position(self, position_ms: int) -> None:
        """The first position the engine says inside the switch."""
        if self._xonos_timeline is not None and not self._xonos_position_said:
            self._xonos_position_said = True
            self._xonos_mark("posizione %.1f s" % (position_ms / 1000))

    async def _xonos_watch_levels(self, levels: list[tuple[float, float | None]]) -> None:
        """Every _XONOS_LEVEL_POLL_S, how much audio PulseAudio holds for this run's sink (list-sink-inputs)."""
        server = self.backend._server
        controller = getattr(server, "_controller", None)
        if controller is None or self._sink is None:
            return
        name = self._sink.sink_name
        try:
            while len(levels) < 1000:
                listing = await asyncio.to_thread(controller._cli_send, "list-sink-inputs")
                levels.append((time.monotonic() - self._xonos_command_at, _xonos_input_latency(listing, name)))
                await asyncio.sleep(_XONOS_LEVEL_POLL_S)
        except Exception as err:  # noqa: BLE001 - solo per il registro
            self.logger.debug("Soloist: livello di PulseAudio non letto: %s", err)

    def _xonos_timeline_end(self, *, failed: bool = False) -> None:
        """
        One line in the log with what happened inside the switch: the engine's events and PulseAudio's audio.

        "audio nuovo" is the first time PulseAudio holds more than 50 ms above the lowest level seen; the level at the
        end is what it holds when the engine says it plays. No uri, no title.
        """
        timeline = self._xonos_timeline
        if timeline is None:
            return
        self._xonos_timeline = None
        if (task := self._xonos_level_task) is not None:
            self._xonos_level_task = None
            task.cancel()
            with suppress(ValueError):
                self._tasks.remove(task)
        levels = [(at, ms) for at, ms in self._xonos_levels if ms is not None]
        if not levels:
            pulse = "nessun flusso"
        else:
            lowest = levels[0][1]
            rise = None
            for at, ms in levels:
                lowest = min(lowest, ms)
                if rise is None and ms > lowest + 50:
                    rise = at
            pulse = "%.0f ms all'inizio, %s, %.0f ms alla fine (%d letture)" % (
                levels[0][1],
                "audio nuovo da +%.2f" % rise if rise is not None else "niente audio nuovo",
                levels[-1][1],
                len(self._xonos_levels),
            )
        wakeups = "risvegli non letti"
        if (begun := self._xonos_wakeups_from) is not None and (now := _xonos_wakeups(self._proc)) is not None:
            wakeups = "%s %d risvegli" % (now[0], now[1] - begun[1])
        self.logger.info(
            "Soloist: dentro %s%s: %s; PulseAudio: %s; %s",
            _XONOS_SWITCH_WORDS.get(self._xonos_switch_mode, self._xonos_switch_mode),
            " (non riuscito)" if failed else "",
            ", ".join(timeline) or "nessun evento",
            pulse,
            wakeups,
        )

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
