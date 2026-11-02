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


def _fingerprint(version: str) -> str:
    changed = f"{Path(__file__).stat().st_mtime_ns} {STRINGS.stat().st_mtime_ns}"
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
    _spotify(root)
    _soloist(root)
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
        "            while True:\n"
        "                if xonos_errors is not None:\n"
        '                    await session.form([], step_id="xonos_finish_retry", errors=xonos_errors, last_step=True)\n'
        "                try:\n"
        "                    await session.finish(setup_data)\n"
        "                    return\n"
        "                except SetupFlowError as err:\n"
        '                    xonos_errors = {"base": err.translation_key or str(err)}\n'
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
    capture.write_text(head + PACTL_CONTROLLER + "\n\n" + end + tail)


# Xonos: al posto della classe di Music Assistant, che usa libpulse con ctypes.
PACTL_CONTROLLER = '''class PAVolumeController:
    """
    Xonos: PulseAudio comandato con pactl invece che con libpulse.

    Sul telefono PulseAudio e libpulse sono programmi glibc dentro proot: il Python di Music Assistant (bionic) non
    può caricare libpulse con ctypes. pactl fa le stesse cose da fuori e xonos_server lo fa passare da proot come
    PulseAudio. Stessa interfaccia di prima: chiamate bloccanti, da un thread.
    """

    def __init__(self, server: str | None = None) -> None:
        """
        Check that the daemon answers.

        :param server: PA server address to connect to (e.g. "unix:<socket>").
        """
        self._server = server or get_default_pulse_server()
        self._closed = False
        if self._pactl("info") is None:
            raise OSError("Timed out connecting to PulseAudio for volume control")

    def set_sink_volume(self, sink_name: str, volume_pct: int, channels: int = 2) -> bool:
        """
        Set hardware volume on a named PA sink.

        :param sink_name: PA sink name.
        :param volume_pct: Volume level 0-100, mapped through an exponential audio taper curve.
        :param channels: Unused: pactl sets every channel.
        """
        amplitude = volume_pct_to_amplitude(volume_pct)
        pa_vol = round(PA_VOLUME_NORM * amplitude ** (1.0 / 3.0))
        return self._pactl("set-sink-volume", sink_name, str(pa_vol)) is not None

    def set_sink_volume_raw(self, sink_name: str, volume_pct: float, channels: int = 2) -> bool:
        """
        Set raw (linear) volume on a named PA sink, without the audio taper.

        :param sink_name: PA sink name.
        :param volume_pct: Linear percentage where 100 maps exactly onto PA_VOLUME_NORM (0 dB).
        :param channels: Unused: pactl sets every channel.
        """
        volume_pct = min(max(volume_pct, 0.0), MAX_RAW_VOLUME_PCT)
        pa_vol = round(PA_VOLUME_NORM * volume_pct / 100.0)
        return self._pactl("set-sink-volume", sink_name, str(pa_vol)) is not None

    def load_module(self, module_name: str, argument: str) -> int | None:
        """
        Load a PulseAudio module.

        :param module_name: PA module name, e.g. "module-pipe-sink".
        :param argument: Module argument string (no spaces inside values).
        :returns: The loaded module's index, or None on failure/timeout.
        """
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
        return self._pactl("unload-module", str(module_index)) is not None

    def close(self) -> None:
        """Nothing to tear down: every call is its own pactl."""
        self._closed = True

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


if __name__ == "__main__":
    main()
