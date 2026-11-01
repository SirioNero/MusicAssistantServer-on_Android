"""
Il server di Music Assistant dentro Xonos.

Xonos lo avvia come processo a sé, dal pacchetto scaricato con "Fai diventare Xonos il tuo server
Music Assistant": `python3 -m xonos_server --data <cartella> --cache <cartella>`. Gira con un ciclo asyncio
suo e risponde solo sul telefono stesso (127.0.0.1, porta 8095 per l'API e 8097 per i flussi) come un
Music Assistant qualsiasi: Xonos, e myCar attraverso Xonos, ci si collegano come a quello di casa. I dati
(account, servizi, libreria) stanno nella cartella dell'app; non si annuncia in rete.

Su stdout dice a Xonos a che punto è, una riga per volta: "XONOS_STATE starting", "running", "stopped" o
"failed <perché>". SIGTERM lo ferma come si deve.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FORMAT = "%(asctime)s.%(msecs)03d %(levelname)s (%(threadName)s) [%(name)s] %(message)s"
LOG_FILE_BYTES = 2_000_000
STATE_PREFIX = "XONOS_STATE"
SELFTEST_FILE = "xonos-selftest"

_lock = threading.Lock()
_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
_stop_event: asyncio.Event | None = None
_state = "stopped"
_error = ""


def main(argv: list[str] | None = None) -> int:
    """Avvia il server, dice a Xonos come va e si ferma con SIGTERM (o SIGINT)."""
    parser = argparse.ArgumentParser(prog="xonos_server")
    parser.add_argument("--data", required=True, help="Dove il server tiene impostazioni e libreria")
    parser.add_argument("--cache", required=True, help="Dove tiene quello che può riscaricare")
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args(argv)
    _tools_through_linker()
    _linux_programs(args.data, args.cache)
    stopping = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopping.set())
    _say("starting")
    start(args.data, args.cache, log_level=args.log_level)
    said = "starting"
    while True:
        if stopping.wait(0.2):
            stop()
            break
        now = state()
        if now != said:
            _say(now if now != "failed" else f"failed {error()}")
            said = now
        if now in ("failed", "stopped"):
            break
    final = state()
    if final != said:
        _say(final if final != "failed" else f"failed {error()}")
    return 1 if final == "failed" else 0


def start(
    data_dir: str, cache_dir: str, tools_dir: str = "", library_dir: str = "", log_level: str = "INFO"
) -> None:
    """
    Avvia il server in un thread suo e torna subito; se gira già non fa niente.

    :param data_dir: Dove il server tiene impostazioni e libreria.
    :param cache_dir: Dove tiene quello che può riscaricare.
    :param tools_dir: Una cartella con i programmi che chiama (ffmpeg, ffprobe), messa prima nel PATH.
    :param library_dir: Dove stanno le librerie di quei programmi (LD_LIBRARY_PATH).
    :param log_level: Il livello del registro del server.
    """
    global _thread, _state, _error
    with _lock:
        if _thread is not None and _thread.is_alive():
            return
        _state = "starting"
        _error = ""
        if tools_dir and tools_dir not in os.environ.get("PATH", "").split(os.pathsep):
            os.environ["PATH"] = tools_dir + os.pathsep + os.environ.get("PATH", "")
        if library_dir:
            os.environ["LD_LIBRARY_PATH"] = library_dir
        _thread = threading.Thread(
            target=_run, args=(data_dir, cache_dir, log_level), name="music-assistant", daemon=True
        )
        _thread.start()


def state() -> str:
    """Dice se il server sta partendo (starting), gira (running), si è fermato per un errore (failed) o no (stopped)."""
    return _state


def error() -> str:
    """Dice perché il server si è fermato o non è partito, se è successo."""
    return _error


def stop(timeout: float = 15.0) -> None:
    """Ferma il server e lo aspetta, al massimo [timeout] secondi."""
    loop, event, thread = _loop, _stop_event, _thread
    if loop is not None and event is not None:
        loop.call_soon_threadsafe(event.set)
    if thread is not None:
        thread.join(timeout)


def _say(what: str) -> None:
    print(f"{STATE_PREFIX} {what}", flush=True)


def _tools_through_linker() -> None:
    """
    Da Android 10 un'app non può avviare i programmi che ha scaricato (W^X): li fa partire il linker di
    sistema, `/system/bin/linker64 <programma> <argomenti>`. Xonos dice quale (XONOS_LINKER) e dove stanno i
    programmi del pacchetto (XONOS_BIN); qui ogni processo avviato con uno di quei programmi passa da lui.
    Music Assistant avvia ffmpeg, ffprobe e librespot con subprocess (anche asyncio passa da lì).
    """
    linker = os.environ.get("XONOS_LINKER", "")
    bin_dir = os.environ.get("XONOS_BIN", "")
    if not bin_dir:
        return
    bin_dir = os.path.realpath(bin_dir)
    # Python stesso è uno di quei programmi: chi lo riavvia (sys.executable) passa dal linker come gli altri.
    python = os.path.join(bin_dir, "python3")
    if os.path.exists(python):
        sys.executable = python
    if not linker:
        return

    def through(args: object) -> object:
        if isinstance(args, (str, bytes, os.PathLike)):
            return args
        try:
            args = list(args)  # type: ignore[call-overload]
        except TypeError:
            return args
        if not args:
            return args
        program = os.fsdecode(args[0])
        path = os.path.abspath(program if os.sep in program else os.path.join(bin_dir, program))
        if os.path.realpath(os.path.dirname(path)) != bin_dir or not os.path.isfile(path):
            return args
        return [linker, path, *args[1:]]

    class Popen(subprocess.Popen):  # type: ignore[type-arg]
        def __init__(self, args, *rest, **kwargs):  # type: ignore[no-untyped-def]
            if not kwargs.get("shell"):
                args = through(args)
            super().__init__(args, *rest, **kwargs)

    subprocess.Popen = Popen  # type: ignore[misc]


# I programmi per Linux che Music Assistant chiama per nome: stanno nel sistema Linux del pacchetto.
LINUX_PROGRAMS = {"pulseaudio", "pactl", "pacat", "parec"}
# Le variabili di Android e di questo Python che ai programmi glibc non servono o li confonderebbero
# (SSL_CERT_FILE punta ai certificati del pacchetto, che dentro proot non si vedono).
GUEST_ENV_DROP = {"LD_LIBRARY_PATH", "LD_PRELOAD", "PYTHONHOME", "PYTHONPATH", "SSL_CERT_FILE", "SSL_CERT_DIR"}
FALLBACK_DNS = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
MULTICAST_PREFIX = "XONOS_MULTICAST"


def _linux_programs(data_dir: str, cache_dir: str) -> None:
    """
    Soloist (il motore ufficiale di Spotify) e PulseAudio, a cui dà l'audio, sono programmi per Linux con glibc:
    girano dentro proot, in un piccolo sistema Linux che sta nel pacchetto (rootfs.tar, estratto la prima volta).
    proot è nella cartella delle librerie dell'app, l'unico posto da cui Android lascia avviare un programma:
    Xonos dice dove (XONOS_PROOT e XONOS_PROOT_LOADER). Ogni processo che Music Assistant avvia con pulseaudio,
    pactl o un programma della sua cartella dei dati (Soloist, che scarica lui) passa da proot. Le cartelle dei
    dati e della cache si vedono dentro allo stesso percorso: socket, tubi e file restano gli stessi.
    """
    proot = os.environ.get("XONOS_PROOT", "")
    loader = os.environ.get("XONOS_PROOT_LOADER", "")
    bin_dir = os.environ.get("XONOS_BIN", "")
    if not (proot and loader and bin_dir and os.path.isfile(proot)):
        os.environ.pop("XONOS_PROOT", None)
        return
    package = Path(bin_dir).parent
    try:
        rootfs = _rootfs(package)
    except Exception as err:  # noqa: BLE001 - senza sistema Linux si va avanti, solo senza Soloist
        print(f"Sistema Linux per Soloist non pronto: {err}", file=sys.stderr, flush=True)
        rootfs = None
    if rootfs is None:
        os.environ.pop("XONOS_PROOT", None)
        return
    data = os.path.realpath(data_dir)
    cache = os.path.realpath(cache_dir)
    Path(data).mkdir(parents=True, exist_ok=True)
    tmp = Path(cache) / "proot-tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    dns = os.environ.get("XONOS_DNS_FILE") or os.path.join(data, "resolv.conf")
    if not os.path.isfile(dns) or os.path.getsize(dns) == 0:
        Path(dns).write_text(FALLBACK_DNS)
    machine_id = Path(data) / "machine-id"
    if not machine_id.is_file():
        import uuid  # noqa: PLC0415

        machine_id.write_text(uuid.uuid4().hex + "\n")
    base = [
        proot, "--kill-on-exit", "-r", str(rootfs),
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", data, "-b", cache,
        "-b", f"{dns}:/etc/resolv.conf", "-b", f"{machine_id}:/etc/machine-id",
    ]

    def guest(args: list[str]) -> str | None:
        program = os.fsdecode(args[0])
        if os.sep not in program:
            return f"/usr/bin/{program}" if program in LINUX_PROGRAMS else None
        path = os.path.realpath(program)
        if path.startswith(data + os.sep) and os.path.isfile(path):
            return path
        return None

    previous = subprocess.Popen

    class Popen(previous):  # type: ignore[misc, valid-type]
        def __init__(self, args, *rest, **kwargs):  # type: ignore[no-untyped-def]
            pairing = False
            if not kwargs.get("shell") and not isinstance(args, (str, bytes, os.PathLike)):
                args = list(args)
                program = guest(args) if args else None
                if program is not None:
                    cwd = os.path.realpath(os.fsdecode(kwargs.get("cwd") or os.getcwd()))
                    inside = cwd if cwd.startswith((data + os.sep, cache + os.sep)) or cwd in (data, cache) else "/"
                    env = {k: v for k, v in (kwargs.get("env") or os.environ).items() if k not in GUEST_ENV_DROP}
                    env["PATH"] = "/usr/bin:/bin"
                    env["PROOT_LOADER"] = loader
                    env["PROOT_TMP_DIR"] = str(tmp)
                    kwargs["env"] = env
                    pairing = "--pair" in args
                    args = [*base, "-w", inside, program, *map(os.fsdecode, args[1:])]
            super().__init__(args, *rest, **kwargs)
            if pairing:
                # Mentre Soloist si fa trovare dall'app Spotify (mDNS), il telefono deve ricevere il multicast.
                _say_multicast(True)
                threading.Thread(target=_multicast_off_at_exit, args=(self.pid,), daemon=True).start()

    subprocess.Popen = Popen  # type: ignore[misc]


def _rootfs(package: Path) -> Path | None:
    """Il sistema Linux del pacchetto, estratto da rootfs.tar la prima volta (tutto o niente); None se non c'è."""
    target = package / "rootfs"
    if (target / ".xonos-complete").is_file():
        return target
    archive = package / "rootfs.tar"
    if not archive.is_file():
        return None
    import shutil  # noqa: PLC0415
    import tarfile  # noqa: PLC0415

    partial = package / "rootfs.new"
    shutil.rmtree(partial, ignore_errors=True)
    with tarfile.open(archive) as tar:
        tar.extractall(partial, filter="data")
    (partial / ".xonos-complete").write_text("ok\n")
    shutil.rmtree(target, ignore_errors=True)
    os.replace(partial, target)
    return target


def _say_multicast(on: bool) -> None:
    print(f"{MULTICAST_PREFIX} {'on' if on else 'off'}", flush=True)


def _multicast_off_at_exit(pid: int) -> None:
    # /proc/<pid> resta finché asyncio non raccoglie il processo: non si chiama waitpid, che lo ruberebbe ad asyncio.
    import time  # noqa: PLC0415

    while os.path.exists(f"/proc/{pid}"):
        time.sleep(1)
    _say_multicast(False)


def _run(data_dir: str, cache_dir: str, log_level: str) -> None:
    global _loop, _stop_event, _state, _error
    try:
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        _setup_logging(data_dir, log_level)
        # Importato solo ora: caricare Music Assistant richiede qualche secondo.
        from music_assistant.helpers.logging import activate_log_queue_handler  # noqa: PLC0415
        from music_assistant.mass import MusicAssistant  # noqa: PLC0415

        mass = MusicAssistant(data_dir, cache_dir, False)

        async def run_mass() -> None:
            global _loop, _stop_event, _state
            _loop = asyncio.get_running_loop()
            _loop.set_default_executor(ThreadPoolExecutor(max_workers=16))
            activate_log_queue_handler()
            _stop_event = asyncio.Event()
            try:
                await mass.start()
                _state = "running"
                logging.getLogger("xonos").info("Music Assistant pronto sul telefono")
                selftest = None
                if (Path(data_dir) / SELFTEST_FILE).is_file():
                    # Solo per le prove in CI (il file lo mette il workflow): Soloist e PulseAudio col codice vero.
                    import xonos_selftest  # noqa: PLC0415

                    selftest = asyncio.create_task(xonos_selftest.run(mass))
                await _stop_event.wait()
                if selftest is not None:
                    selftest.cancel()
            finally:
                await mass.stop()

        asyncio.run(run_mass())
        _state = "stopped"
    except BaseException as err:  # noqa: BLE001 - qualunque cosa lo fermi, Xonos deve sapere perché
        _state = "failed"
        _error = f"{type(err).__name__}: {err}"
        logging.getLogger("xonos").exception("Music Assistant si è fermato")
    finally:
        _loop = None
        _stop_event = None


def _setup_logging(data_dir: str, log_level: str) -> None:
    root = logging.getLogger()
    if getattr(root, "_xonos", False):
        return
    # stderr arriva a Xonos, che lo mette nel logcat; il file resta nella cartella dei dati.
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
    logging.captureWarnings(True)
    file_handler = RotatingFileHandler(
        os.path.join(data_dir, "musicassistant.log"), maxBytes=LOG_FILE_BYTES, backupCount=1
    )
    file_handler.setFormatter(logging.Formatter(LOG_FORMAT))
    root.addHandler(file_handler)
    logging.getLogger("music_assistant").setLevel(log_level.upper())
    root._xonos = True  # type: ignore[attr-defined]


if __name__ == "__main__":
    sys.exit(main())
