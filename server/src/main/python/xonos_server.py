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
    _line(f"{STATE_PREFIX} {what}")


def _line(text: str) -> None:
    """
    Una riga per Xonos con una write sola, che in un tubo (sotto i 4096 byte) è atomica. Con print() il testo e l'a
    capo erano due write, e una riga del registro di Music Assistant, scritta da un altro thread nello stesso tubo,
    poteva finire in mezzo: "XONOS_STATE running2026-11-01 ...", e Xonos non vedeva mai che il server era partito.
    """
    sys.stdout.write(text.replace("\r", " ").replace("\n", " ") + "\n")
    sys.stdout.flush()


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
# Ogni quanto il registro dice quanto processore usano proot e i programmi Linux, mentre girano, e ogni quanto
# li si guarda (un Soloist per brano: guardando di rado, quello di un brano finito non si vedeva).
CPU_EVERY_S = 60
CPU_SAMPLE_S = 5

# I gruppi di processi dei programmi Linux: uno per ogni proot avviato, con il numero di proot (vedi _linux_programs).
_guest_groups: set[int] = set()
# Quanti processi Linux di un server di prima erano rimasti accesi (tolti all'avvio, detto nel registro).
_leftovers = 0


def _linux_programs(data_dir: str, cache_dir: str) -> None:
    """
    Soloist (il motore ufficiale di Spotify) e PulseAudio, a cui dà l'audio, sono programmi per Linux con glibc:
    girano dentro proot, in un piccolo sistema Linux che sta nel pacchetto (rootfs.tar, estratto la prima volta).
    proot è nella cartella delle librerie dell'app, l'unico posto da cui Android lascia avviare un programma:
    Xonos dice dove (XONOS_PROOT e XONOS_PROOT_LOADER). Ogni processo che Music Assistant avvia con pulseaudio,
    pactl o un programma della sua cartella dei dati (Soloist, che scarica lui) passa da proot. Le cartelle dei
    dati e della cache si vedono dentro allo stesso percorso: socket, tubi e file restano gli stessi.

    proot ignora SIGINT e SIGTERM (aspetta il ^C di un terminale, che arriva a tutto il gruppo di processi) e, se
    muore, il programma che seguiva resta acceso. Music Assistant invece ferma Soloist e PulseAudio con SIGINT, poi
    SIGKILL, al solo processo che ha avviato, cioè a proot: Soloist non si fermava mai ("could not be stopped").
    Per questo ogni proot parte in un gruppo suo e i segnali per lui vanno a tutto il gruppo (_group_kill).
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
    # Su Android /data/user/0 è un collegamento a /data/data: Music Assistant usa i percorsi come glieli si passano,
    # quindi dentro proot si vedono tutti e due.
    folders = list(dict.fromkeys([os.path.abspath(data_dir), data, os.path.abspath(cache_dir), cache]))
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
        *[arg for folder in folders for arg in ("-b", folder)],
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

    # Un server fermato con SIGKILL lascia accesi i suoi programmi Linux (in un gruppo loro): via prima di partire.
    _end_leftovers(proot, tuple(dict.fromkeys([os.path.abspath(data_dir) + os.sep, data + os.sep])))

    previous = subprocess.Popen

    class Popen(previous):  # type: ignore[misc, valid-type]
        def __init__(self, args, *rest, **kwargs):  # type: ignore[no-untyped-def]
            pairing = False
            program = None
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
                    # Un gruppo di processi suo, con il numero di proot: i segnali vanno a tutto il gruppo.
                    if "process_group" not in kwargs:
                        kwargs["start_new_session"] = True
                    # L'uscita che nessuno legge non va nel tubo verso Xonos (PulseAudio di Music Assistant la eredita):
                    # un programma Linux rimasto acceso dopo Python lo terrebbe aperto, e Xonos non vedrebbe mai che
                    # il server si è fermato. stdout ed stderr sono il 4° e il 5° argomento dopo args.
                    if len(rest) < 4 and kwargs.get("stdout") is None:
                        kwargs["stdout"] = subprocess.DEVNULL
                    if len(rest) < 5 and kwargs.get("stderr") is None:
                        kwargs["stderr"] = subprocess.DEVNULL
                    pairing = "--pair" in args
                    args = [*base, "-w", inside, program, *map(os.fsdecode, args[1:])]
            super().__init__(args, *rest, **kwargs)
            if program is not None and kwargs.get("start_new_session"):
                _guest_groups.add(self.pid)
            if pairing:
                # Mentre Soloist si fa trovare dall'app Spotify (mDNS), il telefono deve ricevere il multicast.
                _say_multicast(True)
                threading.Thread(target=_multicast_off_at_exit, args=(self.pid,), daemon=True).start()

    subprocess.Popen = Popen  # type: ignore[misc]
    # Popen.send_signal, terminate e kill (anche quelli di asyncio) passano da os.kill, come lo SIGKILL diretto
    # di Music Assistant (AsyncProcess.close): per un proot il segnale va a tutto il suo gruppo.
    real_kill = os.kill

    def kill(pid: int, sig: int) -> None:
        if pid in _guest_groups:
            # Solo se pid è ancora il proot che guida il gruppo (un numero si può riusare); se proot non c'è più il
            # segnale va a chi resta del suo gruppo.
            try:
                leader = os.getpgid(pid) == pid
            except ProcessLookupError:
                leader = True
            except OSError:
                leader = False
            if leader and _group_kill(pid, sig):
                return
        real_kill(pid, sig)

    kill.__wrapped__ = real_kill  # type: ignore[attr-defined]
    os.kill = kill
    threading.Thread(target=_watch_cpu, name="xonos-cpu", daemon=True).start()


def _group_kill(group: int, sig: int) -> bool:
    """Manda [sig] a tutto il gruppo di un proot; False se il gruppo non c'è più (allora lo si dimentica)."""
    try:
        os.killpg(group, sig)
    except ProcessLookupError:
        _guest_groups.discard(group)
        return False
    except OSError:
        return False
    return True


def _end_guests() -> int:
    """Alla fine del server: SIGKILL ai programmi Linux ancora accesi. Dice quanti gruppi erano rimasti."""
    alive = {group for _, group, _ in _processes().values()}
    ended = 0
    for group in list(_guest_groups):
        if group in alive and _group_kill(group, signal.SIGKILL):
            ended += 1
        _guest_groups.discard(group)
    return ended


def _processes() -> dict[int, tuple[list[str], int, int]]:
    """I processi che si vedono (su Android solo quelli dell'app): pid -> (argomenti, gruppo, tick di processore)."""
    found: dict[int, tuple[list[str], int, int]] = {}
    for entry in os.scandir("/proc"):
        if not entry.name.isdigit():
            continue
        try:
            with open(f"/proc/{entry.name}/stat", "rb") as file:
                stat = file.read().decode(errors="replace")
            with open(f"/proc/{entry.name}/cmdline", "rb") as file:
                argv = [os.fsdecode(part) for part in file.read().split(b"\0") if part]
        except OSError:
            continue
        # Dopo "pid (nome) ": stato, genitore, gruppo, ... utime e stime sono il 12° e il 13° campo.
        fields = stat[stat.rfind(")") + 2 :].split()
        # Uno zombie (Z, X) è già morto: aspetta solo chi lo raccolga, non usa processore e non si ferma.
        if len(fields) < 13 or fields[0] in ("Z", "X", "x"):
            continue
        if not argv:
            argv = [stat[stat.find("(") + 1 : stat.rfind(")")]]
        found[int(entry.name)] = (argv, int(fields[2]), int(fields[11]) + int(fields[12]))
    return found


def _end_leftovers(proot: str, data: tuple[str, ...]) -> None:
    """
    SIGKILL ai proot e ai programmi Linux di un server di prima ancora accesi (mai a questo processo): proot, i
    programmi del sistema Linux e quelli della cartella dei dati [data] (i suoi due percorsi, con "/" in fondo).
    """
    global _leftovers
    guests = {f"/usr/bin/{name}" for name in LINUX_PROGRAMS}
    for pid, (argv, _, _) in _processes().items():
        program = argv[0]
        if pid != os.getpid() and (program == proot or program in guests or program.startswith(data)):
            try:
                os.kill(pid, signal.SIGKILL)
                _leftovers += 1
            except OSError:
                pass


def _watch_cpu() -> None:
    """
    Ogni CPU_EVERY_S secondi, se girano programmi Linux, scrive nel registro quanto processore hanno usato nel
    frattempo (100% = un core intero), per nome: proot, soloist, pulseaudio... Serve a capire, dal registro di una
    persona, se proot pesa troppo sul telefono. Guarda ogni CPU_SAMPLE_S secondi: Music Assistant avvia un Soloist
    per brano, e uno finito tra due righe (quando si guardava solo una volta al minuto) non compariva affatto.
    """
    import time  # noqa: PLC0415

    try:
        ticks = os.sysconf("SC_CLK_TCK") or 100
    except (ValueError, OSError):
        ticks = 100
    logger = logging.getLogger("xonos")
    window = _CpuWindow(time.monotonic())
    before: dict[int, int] = {}
    while True:
        time.sleep(CPU_SAMPLE_S)
        try:
            before = _cpu_sample(ticks, before, window)
            now = time.monotonic()
            if now - window.started >= CPU_EVERY_S:
                window.log(logger, now)
                window = _CpuWindow(now)
        except Exception:  # noqa: BLE001 - solo un aiuto per capire: non deve mai fermarsi
            logger.debug("Conto del processore non riuscito", exc_info=True)


class _CpuWindow:
    """Il processore usato dai programmi Linux in un intervallo, per nome, e quanti ne giravano insieme al massimo."""

    def __init__(self, started: float) -> None:
        self.started = started
        self.used: dict[str, float] = {}
        self.most: dict[str, int] = {}

    def log(self, logger: logging.Logger, now: float) -> None:
        if not self.used:
            return
        seconds = max(now - self.started, 1.0)
        parts = [
            f"{name} {100 * value / seconds:.0f}%" + (f" ({self.most[name]} processi)" if self.most[name] > 1 else "")
            for name, value in sorted(self.used.items(), key=lambda item: -item[1])
        ]
        logger.info("Processore dei programmi Linux negli ultimi %.0f s: %s", seconds, ", ".join(parts))


def _cpu_sample(ticks: int, before: dict[int, int], window: _CpuWindow) -> dict[int, int]:
    """Un'occhiata: aggiunge a [window] il processore usato dall'occhiata prima; dà i tick visti per processo."""
    groups = set(_guest_groups)
    current: dict[int, int] = {}
    alive: set[int] = set()
    together: dict[str, int] = {}
    for pid, (argv, group, total) in _processes().items():
        if group not in groups:
            continue
        alive.add(group)
        current[pid] = total
        name = os.path.basename(argv[0])
        name = "proot" if name.startswith("libproot") else name
        # Un processo nato nel frattempo (al più CPU_SAMPLE_S secondi fa) conta tutto il suo tempo.
        window.used[name] = window.used.get(name, 0.0) + (total - before.get(pid, 0)) / ticks
        together[name] = together.get(name, 0) + 1
    for name, count in together.items():
        window.most[name] = max(window.most.get(name, 0), count)
    for group in groups - alive:
        _guest_groups.discard(group)
    return current


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
    _line(f"{MULTICAST_PREFIX} {'on' if on else 'off'}")


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
                if _leftovers:
                    logging.getLogger("xonos").warning(
                        "Tolti %d processi Linux rimasti accesi da prima (proot, Soloist, PulseAudio)", _leftovers
                    )
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
                if ended := _end_guests():
                    logging.getLogger("xonos").warning("Fermati %d programmi Linux ancora accesi alla fine", ended)

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
