"""
Il server di Music Assistant dentro Xonos.

Xonos lo avvia come processo a sé, dal pacchetto scaricato con "Fai diventare Xonos il tuo server
Music Assistant": `python3 -m xonos_server --data <cartella> --cache <cartella>`. Gira con un ciclo asyncio
suo e risponde solo sul telefono stesso (127.0.0.1, porta 8095 per l'API e 8097 per i flussi) come un
Music Assistant qualsiasi: Xonos, e myCar attraverso Xonos, ci si collegano come a quello di casa. I dati
(account, servizi, libreria) stanno nella cartella dell'app; non si annuncia in rete.

Su stdout dice a Xonos a che punto è, una riga per volta: "XONOS_STATE starting", "running", "stopped" o
"failed <perché>". SIGTERM lo ferma come si deve (e se non ci riesce in STOP_LIMIT_S secondi, chiude da sé).

Il registro intero va in musicassistant.log, nella cartella dei dati (la persona lo condivide a mano). Su stderr, che
Xonos mette nel logcat, solo le righe di questo file (il logger "xonos"), una riga per record: "XONOS_LOG <livello>
<prima riga del testo>". Nel logcat niente titoli, uri, nomi e indirizzi che Music Assistant scrive nel suo registro.
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
import time
from concurrent.futures import ThreadPoolExecutor
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FORMAT = "%(asctime)s.%(msecs)03d %(levelname)s (%(threadName)s) [%(name)s] %(message)s"
LOG_FILE_BYTES = 2_000_000
STATE_PREFIX = "XONOS_STATE"
LOG_PREFIX = "XONOS_LOG"
# Il logger delle righe di questo file: le sole che vanno anche su stderr.
OUR_LOGGER = "xonos"
# Una riga per il logcat non più lunga di così (sotto i 4096 byte una write in un tubo è atomica).
LOG_LINE_MAX = 2000
# Il livello VERBOSE di Music Assistant (VERBOSE_LOG_LEVEL): il nome lo registra il suo __main__, che qui non gira, e
# senza nome setLevel("VERBOSE") di un servizio darebbe errore.
VERBOSE_LOG_LEVEL = 5
# I logger troppo loquaci, zittiti come fa il __main__ di Music Assistant.
QUIET_LOGGERS = (
    "asyncio", "aiosqlite", "databases", "requests", "urllib3", "aiohttp.access", "httpx", "charset_normalizer"
)
SELFTEST_FILE = "xonos-selftest"
# Dopo SIGTERM (o quando Xonos non c'è più) Music Assistant ha questi secondi per fermarsi, poi il server chiude da
# sé. Xonos manderebbe SIGKILL dopo 15 s, ma può essere congelato o morto.
STOP_LIMIT_S = 12.0

_lock = threading.Lock()
_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
# Il thread del ciclo asyncio di Music Assistant e Music Assistant stesso, quando gira: per le righe del processore.
_loop_ident: int | None = None
_mass: object | None = None
_stop_event: asyncio.Event | None = None
_state = "stopped"
_error = ""
# Il registro di Music Assistant (musicassistant.log), quando è pronto.
_log_file = ""
# Vero quando Xonos, che legge stdout e stderr, non c'è più: allora non ci si scrive.
_pipe_gone = False


def main(argv: list[str] | None = None) -> int:
    """Avvia il server, dice a Xonos come va e si ferma con SIGTERM (o SIGINT)."""
    parser = argparse.ArgumentParser(prog="xonos_server")
    parser.add_argument("--data", required=True, help="Dove il server tiene impostazioni e libreria")
    parser.add_argument("--cache", required=True, help="Dove tiene quello che può riscaricare")
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args(argv)
    # Chi ha avviato il server (Xonos): se cambia, Xonos è morto e il server si ferma da solo.
    parent = os.getppid()
    _tools_through_linker()
    _linux_programs(args.data, args.cache)
    threading.Thread(target=_watch_cpu, name="xonos-cpu", daemon=True).start()
    stopping = threading.Event()
    threading.Thread(target=_stop_guard, args=(stopping,), name="xonos-stop", daemon=True).start()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopping.set())
    _say("starting")
    start(args.data, args.cache, log_level=args.log_level)
    said = "starting"
    while True:
        if stopping.wait(0.2):
            stop()
            break
        if os.getppid() != parent:
            _orphaned(stopping)
            continue
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
    Se Xonos non c'è più (il tubo è chiuso) non scrive niente.
    """
    if _pipe_gone:
        return
    try:
        sys.stdout.write(text.replace("\r", " ").replace("\n", " ") + "\n")
        sys.stdout.flush()
    except (OSError, ValueError):
        pass


def _stop_guard(stopping: threading.Event) -> None:
    """
    Quando il server comincia a fermarsi (SIGTERM, o Xonos non c'è più) aspetta STOP_LIMIT_S secondi; se il processo
    è ancora qui, SIGKILL ai programmi Linux rimasti e uscita. Xonos manderebbe SIGKILL dopo 15 s, ma può essere
    congelato o morto, e Python può restare appeso alla fine a un thread che non finisce. Il thread parte subito e
    aspetta: niente thread nuovi nel gestore di un segnale.
    """
    stopping.wait()
    time.sleep(STOP_LIMIT_S)
    try:
        ended = _end_guests()
    except Exception:  # noqa: BLE001 - si esce comunque
        ended = -1
    _note_in_file(
        f"Music Assistant non si è fermato in {STOP_LIMIT_S:.0f} s: il server esce da solo "
        f"(gruppi di programmi Linux fermati: {ended})"
    )
    os._exit(1)


def _orphaned(stopping: threading.Event) -> None:
    """Xonos, che ha avviato il server, non c'è più (il genitore è cambiato): il server si ferma come per SIGTERM."""
    global _pipe_gone
    _pipe_gone = True
    logging.getLogger(OUR_LOGGER).warning("Xonos non c'è più (il processo che ha avviato il server): mi fermo")
    stopping.set()


def _note_in_file(text: str) -> None:
    """Una riga scritta direttamente in musicassistant.log, senza passare dal registro (che può essere bloccato)."""
    if not _log_file:
        return
    try:
        now = time.time()
        stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now))
        ms = int(now * 1000) % 1000
        with open(_log_file, "a", encoding="utf-8") as file:
            file.write(f"{stamp},{ms:03d}.{ms:03d} WARNING (xonos-stop) [{OUR_LOGGER}] {text}\n")
    except OSError:
        pass


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
# Ogni quanto il registro dice quanto processore usano i processi del server (Python, ffmpeg, proot e i programmi
# Linux, l'app), e ogni quanto li si guarda (un Soloist per brano: guardando di rado, quello di un brano finito non si
# vedeva).
CPU_EVERY_S = 60
CPU_SAMPLE_S = 5
# Senza programmi Linux e con meno di questa parte di un core in tutto, la riga si scrive solo ogni CPU_QUIET_EVERY_S:
# è quanto costa il server a riposo.
CPU_BUSY = 0.05
CPU_QUIET_EVERY_S = 900
# Python sopra questa parte di un core in un minuto: una riga in più dice dove lavora (_profile), al più ogni
# PROFILE_PAUSE_S.
PROFILE_ABOVE = 0.20
PROFILE_PAUSE_S = 600
# Per PROFILE_SECONDS, ogni PROFILE_EVERY_S, si guarda dove si trova il thread del ciclo di Music Assistant.
PROFILE_SECONDS = 4.0
PROFILE_EVERY_S = 0.02
PROFILE_TOP = 6
# Il codice "nostro": per ogni occhiata, la prima funzione che sta qui (dalla più interna) dice chi fa il lavoro.
PROFILE_OWNERS = ("/music_assistant/", "/aiosendspin/", "/aiohttp/", "/xonos_")
# Le funzioni in cui il ciclo aspetta invece di lavorare: quelle occhiate non contano.
PROFILE_IDLE = {("selectors.py", "select"), ("threading.py", "wait"), ("queue.py", "get")}

# I gruppi di processi dei programmi Linux: uno per ogni proot avviato, con il numero di proot (vedi _linux_programs).
_guest_groups: set[int] = set()
# Quanti processi Linux di un server di prima erano rimasti accesi (tolti all'avvio, detto nel registro).
_leftovers = 0


# Perché i programmi Linux non possono girare (ptrace negato), se è così: lo si scrive nel registro all'avvio.
_ptrace_blocked = ""
# Se proot segue i programmi Linux con l'aiuto di seccomp (una riga per il registro, "" se la prova non è partita).
_seccomp = ""
# Cosa scrive proot con "-v 1" quando seccomp lo aiuta (tracee/event.c): solo le chiamate di sistema che gli
# servono fermano il programma. Senza, si ferma in proot ogni chiamata (read, write, futex...).
SECCOMP_NOTE = "ptrace acceleration (seccomp mode 2"
# Le righe di proot con "-v 1" cominciano così (cli/note.c).
PROOT_INFO = b"proot info:"


def _ptrace_refused(base: list[str], loader: str, tmp: Path) -> str:
    """
    Una prova sola all'avvio (qualche decimo di secondo): proot fa girare un programma del sistema Linux. Se Android
    non gli lascia seguire i programmi (ptrace negato: su GrapheneOS l'opzione che blocca il debug del codice nativo
    per le app), il messaggio di proot; se va, o se non va per un altro motivo, "".
    Con "-v 1" proot dice anche se seccomp lo aiuta (_seccomp, per il registro).
    """
    global _seccomp
    env = {"PATH": "/usr/bin:/bin", "PROOT_LOADER": loader, "PROOT_TMP_DIR": str(tmp)}
    try:
        done = subprocess.run(
            [*base, "-v", "1", "-w", "/", "/usr/bin/pactl", "--version"],
            env=env, capture_output=True, timeout=30, start_new_session=True, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as err:
        _seccomp = f"proot: non so se seccomp lo aiuta (la prova non è partita: {type(err).__name__})"
        return ""
    _seccomp = _seccomp_said(done.stderr, done.returncode)
    # Le righe "proot info:" ci sono solo per "-v 1" (una dice "ptrace acceleration"): il giudizio è quello di prima.
    said = b"".join(line for line in done.stderr.splitlines(keepends=True) if not line.startswith(PROOT_INFO))
    if done.returncode == 0:
        return ""
    text = " ".join((said + done.stdout).decode(errors="replace").split())
    return text[:300] if "ptrace" in text.lower() else ""


def _seccomp_said(stderr: bytes, code: int) -> str:
    """La riga per il registro: se proot ha detto che seccomp lo aiuta, dall'uscita della prova con "-v 1"."""
    for line in stderr.decode(errors="replace").splitlines():
        if SECCOMP_NOTE in line:
            return f"proot: seccomp lo aiuta ({line[line.index(SECCOMP_NOTE):].strip()})"
    if code == 0:
        return (
            "proot: seccomp NON lo aiuta: ogni chiamata di sistema dei programmi Linux (Soloist, PulseAudio) si ferma "
            "in proot, e costa processore"
        )
    return f"proot: non so se seccomp lo aiuta (la prova è uscita con {code})"


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

    # Se Android non lascia a Xonos seguire i suoi programmi (ptrace), proot non fa girare niente: Music Assistant
    # offrirebbe Soloist, lo scaricherebbe (34 MB) e lo butterebbe a ogni tentativo. Allora resta librespot, e il
    # flusso di Spotify dice cosa permettere (XONOS_PROOT_ERROR).
    global _ptrace_blocked
    _ptrace_blocked = _ptrace_refused(base, loader, tmp)
    if _ptrace_blocked:
        os.environ.pop("XONOS_PROOT", None)
        os.environ["XONOS_PROOT_ERROR"] = "ptrace"
        return

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
    Ogni CPU_EVERY_S secondi scrive nel registro quanto processore hanno usato nel frattempo (100% = un core intero),
    per nome: python (questo processo: Music Assistant), soloist, pulseaudio e i loro proot (con i risvegli al secondo
    di proot: ogni chiamata di sistema che intercetta lo sveglia), ffmpeg e ffprobe, app (Xonos). Serve a capire, dal
    registro di una persona, cosa pesa sul telefono. Guarda ogni CPU_SAMPLE_S secondi: Music Assistant avvia un Soloist
    o un ffmpeg per brano, e uno finito tra due righe conta lo stesso. Senza programmi Linux e con poco processore la
    riga si scrive solo ogni CPU_QUIET_EVERY_S (quanto costa il server a riposo). Se Python ha usato almeno
    PROFILE_ABOVE di un core, una riga in più dice dove (_profile).
    """
    try:
        ticks = os.sysconf("SC_CLK_TCK") or 100
    except (ValueError, OSError):
        ticks = 100
    logger = logging.getLogger(OUR_LOGGER)
    # La prima occhiata fa solo da punto di partenza: sennò la prima riga conterebbe tutta la vita dell'app.
    try:
        before = _cpu_sample(ticks, {}, _CpuWindow(0.0))
    except Exception as err:  # noqa: BLE001 - solo un aiuto per capire: non deve mai fermarsi
        _failed_once(logger, "Conto del processore", err)
        before = {}
    window = _CpuWindow(time.monotonic())
    quiet = _CpuWindow(window.started)
    profiled = float("-inf")
    while True:
        time.sleep(CPU_SAMPLE_S)
        try:
            before = _cpu_sample(ticks, before, window)
            now = time.monotonic()
            if now - window.started < CPU_EVERY_S:
                continue
            seconds = max(now - window.started, 1.0)
            python = window.used.get("python", 0.0) / seconds
            if window.guests or sum(window.used.values()) / seconds >= CPU_BUSY:
                logger.info("%s", window.line(now))
                quiet = _CpuWindow(now)
            else:
                # Un minuto tranquillo: si somma agli altri, e la riga dice la media di CPU_QUIET_EVERY_S.
                quiet.add(window)
                if now - quiet.started >= CPU_QUIET_EVERY_S:
                    logger.info("%s", quiet.line(now))
                    quiet = _CpuWindow(now)
            window = _CpuWindow(now)
            if python >= PROFILE_ABOVE and now - profiled >= PROFILE_PAUSE_S:
                profiled = now
                _profile(logger, python, ticks)
                # I secondi della prova non entrano nel conto (quanto è costata lo dice la sua riga).
                before = _cpu_sample(ticks, before, _CpuWindow(0.0))
                window = _CpuWindow(time.monotonic())
        except Exception as err:  # noqa: BLE001 - solo un aiuto per capire: non deve mai fermarsi
            _failed_once(logger, "Conto del processore", err)


# Gli aiuti per capire che non sono riusciti: lo si dice una volta sola.
_failures: set[str] = set()


def _failed_once(logger: logging.Logger, what: str, err: BaseException) -> None:
    """Dice nel registro, una volta sola per [what], che un aiuto per capire non è riuscito (il perché nel file)."""
    if what in _failures:
        return
    _failures.add(what)
    try:
        logger.warning("%s non riuscito (%s): non lo ridico", what, type(err).__name__, exc_info=err)
    except Exception:  # noqa: BLE001, S110 - il registro stesso non va: niente da fare
        pass


class _CpuWindow:
    """
    Il processore usato in un intervallo, per nome (in secondi), quanti processi con quel nome giravano insieme al
    massimo, i risvegli di proot, e se ha girato un programma Linux.
    """

    def __init__(self, started: float) -> None:
        self.started = started
        self.used: dict[str, float] = {}
        self.most: dict[str, int] = {}
        self.wakeups: dict[str, int] = {}
        self.linux: set[str] = set()

    @property
    def guests(self) -> bool:
        """Se nell'intervallo ha girato un programma Linux (con il suo proot)."""
        return bool(self.linux)

    def add(self, other: _CpuWindow) -> None:
        """Somma a questo intervallo [other], che viene subito dopo."""
        for name, value in other.used.items():
            self.used[name] = self.used.get(name, 0.0) + value
        for name, count in other.wakeups.items():
            self.wakeups[name] = self.wakeups.get(name, 0) + count
        for name, count in other.most.items():
            self.most[name] = max(self.most.get(name, 0), count)
        self.linux |= other.linux

    def line(self, now: float) -> str:
        """
        "Processore negli ultimi 60 s: python 22%, soloist 16%, proot[soloist] 9% (2100 risvegli/s), pulseaudio 13%,
        proot[pulseaudio] 4% (800 risvegli/s), ffmpeg 2%, app 6%": sempre nello stesso ordine (python, ogni programma
        Linux col suo proot, gli altri programmi, app), per leggerla a occhio e con uno script.
        """
        seconds = max(now - self.started, 1.0)
        parts = []
        for name in sorted(self.used, key=self._order):
            extra = []
            if self.most.get(name, 1) > 1:
                extra.append(f"{self.most[name]} processi")
            if name.startswith("proot"):
                extra.append(f"{self.wakeups.get(name, 0) / seconds:.0f} risvegli/s")
            parts.append(f"{name} {100 * self.used[name] / seconds:.0f}%" + (f" ({', '.join(extra)})" if extra else ""))
        return f"Processore negli ultimi {seconds:.0f} s: {', '.join(parts) or 'nessun processo visto'}"

    def _order(self, name: str) -> tuple[int, str, int]:
        if name == "python":
            return (0, "", 0)
        if name == "app":
            return (3, "", 0)
        if name.startswith("proot"):
            return (1, name[6:-1] if name.startswith("proot[") else "~", 1)
        if name in self.linux:
            return (1, name, 0)
        return (2, name, 0)


def _process_name(pid: int, argv: list[str], guest: bool) -> str | None:
    """
    Il nome di un processo nella riga del processore; None per quelli che non contano. [guest]: il processo è nel
    gruppo di un proot (un programma Linux o proot stesso).
    """
    if pid == os.getpid():
        return "python"
    first = os.path.basename(argv[0])
    if guest:
        if first.startswith("libproot"):
            # Col programma che segue (quello dopo "-w <cartella>"): proot[soloist] e proot[pulseaudio] separati, così
            # dal registro si vede per quale dei due proot lavora di più.
            at = argv.index("-w") + 2 if "-w" in argv else len(argv)
            return f"proot[{os.path.basename(argv[at])}]" if at < len(argv) else "proot"
        return first
    if first.startswith("linker") and len(argv) > 1:
        # I programmi del pacchetto partono col linker di sistema: "linker64 <programma> <argomenti>" (ffmpeg, ffprobe).
        return os.path.basename(argv[1])
    if argv[0].startswith("it.xonos.ma"):
        return "app"
    return None


def _wakeups(pid: int) -> int:
    """
    Quante volte il processo si è fermato e poi è ripartito (cambi di contesto). Per proot: quasi una volta per ogni
    chiamata di sistema che intercetta (il programma che segue si ferma, proot si sveglia, lo fa ripartire).
    """
    total = 0
    try:
        with open(f"/proc/{pid}/status", "rb") as file:
            for line in file:
                if line.startswith((b"voluntary_ctxt_switches", b"nonvoluntary_ctxt_switches")):
                    total += int(line.split()[1])
    except (OSError, ValueError, IndexError):
        pass
    return total


def _cpu_sample(
    ticks: int, before: dict[int, tuple[int, int]], window: _CpuWindow
) -> dict[int, tuple[int, int]]:
    """Un'occhiata: aggiunge a [window] il processore usato dall'occhiata prima; dà tick e risvegli per processo."""
    groups = set(_guest_groups)
    current: dict[int, tuple[int, int]] = {}
    alive: set[int] = set()
    together: dict[str, int] = {}
    for pid, (argv, group, total) in _processes().items():
        guest = group in groups
        if guest:
            alive.add(group)
        name = _process_name(pid, argv, guest)
        if name is None:
            continue
        wakeups = _wakeups(pid) if name.startswith("proot") else 0
        current[pid] = (total, wakeups)
        # Un processo nato nel frattempo (al più CPU_SAMPLE_S secondi fa) conta tutto il suo tempo.
        old_total, old_wakeups = before.get(pid, (0, 0))
        window.used[name] = window.used.get(name, 0.0) + max(total - old_total, 0) / ticks
        window.wakeups[name] = window.wakeups.get(name, 0) + max(wakeups - old_wakeups, 0)
        together[name] = together.get(name, 0) + 1
        if guest:
            window.linux.add(name)
    for name, count in together.items():
        window.most[name] = max(window.most.get(name, 0), count)
    for group in groups - alive:
        _guest_groups.discard(group)
    return current


def _profile(logger: logging.Logger, python: float, ticks: int) -> None:
    """
    Python ha usato [python] di un core nell'ultimo minuto: una riga dice dove. Il processore di ogni suo thread (da
    /proc/self/task: niente pthread_getcpuclockid, che su Android con un thread appena finito può fermare tutto il
    processo), i lavori di Music Assistant in corso e, per PROFILE_SECONDS, dove si trova il thread del ciclo asyncio
    ogni PROFILE_EVERY_S (sys._current_frames). Si vede soprattutto il lavoro lungo: chi guarda prende il GIL quando
    il ciclo si ferma ad aspettare o dopo 5 ms di lavoro di fila (l'intervallo del GIL resta quello di sempre).
    """
    cost = time.thread_time()
    first = _thread_ticks()
    started = time.monotonic()
    tasks = _running_tasks()
    loop = _loop_sample()
    seconds = max(time.monotonic() - started, 0.001)
    threads = _threads_line(first, _thread_ticks(), ticks, seconds)
    cost = time.thread_time() - cost
    logger.info(
        "Python al %.0f%% di un core nell'ultimo minuto. Thread in %.0f s: %s. Lavori di Music Assistant in corso: %s. "
        "%s. Costo di questa prova: %.0f ms di processore",
        100 * python, seconds, threads, tasks, loop, 1000 * cost,
    )


def _thread_ticks() -> dict[int, int]:
    """Il processore usato da ogni thread di questo processo (tid -> tick), da /proc/self/task/<tid>/stat."""
    found: dict[int, int] = {}
    try:
        entries = list(os.scandir("/proc/self/task"))
    except OSError:
        return found
    for entry in entries:
        try:
            with open(f"/proc/self/task/{entry.name}/stat", "rb") as file:
                stat = file.read().decode(errors="replace")
            fields = stat[stat.rfind(")") + 2 :].split()
            found[int(entry.name)] = int(fields[11]) + int(fields[12])
        except (OSError, ValueError, IndexError):
            continue
    return found


def _thread_name(tid: int, names: dict[int, str]) -> str:
    """Il nome di un thread: quello di Python (i thread di un pool insieme), sennò quello del sistema (comm)."""
    name = names.get(tid)
    if name is None:
        try:
            with open(f"/proc/self/task/{tid}/comm", "rb") as file:
                name = file.read().decode(errors="replace").strip() or str(tid)
        except OSError:
            name = str(tid)
    # "ThreadPoolExecutor-0_7" e "ThreadPoolExecutor-0_8" sono lo stesso pool.
    head, _, tail = name.rpartition("_")
    return head if head and tail.isdigit() else name


def _threads_line(first: dict[int, int], last: dict[int, int], ticks: int, seconds: float) -> str:
    """"music-assistant 25%, ThreadPoolExecutor-0 (3) 9%": i thread che hanno usato più processore, dal più caro."""
    names = {thread.native_id: thread.name for thread in threading.enumerate() if thread.native_id is not None}
    used: dict[str, float] = {}
    count: dict[str, int] = {}
    for tid, total in last.items():
        delta = total - first.get(tid, total)
        if delta <= 0:
            continue
        name = _thread_name(tid, names)
        used[name] = used.get(name, 0.0) + delta / ticks / seconds
        count[name] = count.get(name, 0) + 1
    parts = [
        name + (f" ({count[name]})" if count[name] > 1 else "") + f" {100 * share:.0f}%"
        for name, share in sorted(used.items(), key=lambda item: -item[1])[:PROFILE_TOP]
    ]
    return ", ".join(parts) or "nessuno ha lavorato"


def _task_kind(task: object) -> str:
    """Che lavoro è, senza nomi della persona (il nome di un lavoro può avere un titolo o il nome di una playlist)."""
    meta = getattr(task, "metadata", None) or {}
    kind = str(meta.get("task_domain") or getattr(task, "translation_key", None) or "altro")
    if kind == "music_sync":
        kind += f" {meta.get('provider_domain', '?')} {meta.get('media_type', '?')}"
    return kind


def _running_tasks() -> str:
    """I lavori di Music Assistant in corso (sincronizzazioni, pulizie...), chiesti al suo ciclo, e quanto ci mette."""
    mass, loop = _mass, _loop
    if mass is None or loop is None:
        return "Music Assistant non è pronto"

    async def running() -> list[str]:
        tasks = mass.tasks.list_tasks_for_user(None)  # type: ignore[attr-defined]
        return [_task_kind(task) for task in tasks if task.status == "running"]

    started = time.monotonic()
    asking = running()
    try:
        future = asyncio.run_coroutine_threadsafe(asking, loop)
    except Exception as err:  # noqa: BLE001 - il ciclo è già chiuso
        asking.close()
        return f"non si possono chiedere ({type(err).__name__})"
    try:
        kinds = future.result(timeout=2)
    except TimeoutError:
        future.cancel()
        # Anche questo dice qualcosa: il ciclo è così occupato che una richiesta da niente aspetta più di 2 s.
        return "il ciclo di Music Assistant non ha risposto in 2 s"
    except Exception as err:  # noqa: BLE001
        return f"non si possono chiedere ({type(err).__name__})"
    waited = 1000 * (time.monotonic() - started)
    counted: dict[str, int] = {}
    for kind in kinds:
        counted[kind] = counted.get(kind, 0) + 1
    names = ", ".join(kind + (f" ×{n}" if n > 1 else "") for kind, n in sorted(counted.items()))
    return f"{names or 'nessuno'} (il ciclo ha risposto in {waited:.0f} ms)"


def _short(path: str) -> str:
    """Il percorso di un file di codice senza le cartelle del telefono: "music_assistant/mass.py", "json/encoder.py"."""
    path = "/" + path.replace(os.sep, "/").lstrip("/")
    for mark in ("/site-packages/", "/python313.zip/", "/python3.13/", "/app/"):
        if mark in path:
            return path.rsplit(mark, 1)[1]
    for mark in PROFILE_OWNERS:
        if mark in path:
            return path[path.index(mark) + 1 :]
    return "/".join(path.rsplit("/", 2)[-2:])


def _ours(path: str) -> bool:
    path = "/" + path.replace(os.sep, "/").lstrip("/")
    return any(mark in path for mark in PROFILE_OWNERS)


def _where(frame: object) -> str:
    """
    "json/encoder.py:iterencode < music_assistant/helpers/json.py:json_dumps": la funzione più interna e, se non è
    "nostra", la prima "nostra" sopra di lei (Music Assistant, aiosendspin, aiohttp, Xonos), che dice chi fa il lavoro.
    """
    code = frame.f_code  # type: ignore[attr-defined]
    key = f"{_short(code.co_filename)}:{code.co_name}"
    if _ours(code.co_filename):
        return key
    up = frame.f_back  # type: ignore[attr-defined]
    for _ in range(100):
        if up is None:
            break
        if _ours(up.f_code.co_filename):
            return f"{key} < {_short(up.f_code.co_filename)}:{up.f_code.co_name}"
        up = up.f_back
    return key


def _loop_sample(ident: int | None = None, seconds: float = PROFILE_SECONDS) -> str:
    """
    Per [seconds], ogni PROFILE_EVERY_S, dove si trova il thread [ident] (di base quello del ciclo di Music
    Assistant): "ciclo al lavoro in 45% di 190 occhiate: 12% json/encoder.py:iterencode < ...; 8% ...".
    Le occhiate in cui aspetta (PROFILE_IDLE) non contano.
    """
    ident = _loop_ident if ident is None else ident
    if ident is None:
        return "Ciclo di Music Assistant non avviato"
    seen: dict[str, int] = {}
    looks = busy = 0
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        time.sleep(PROFILE_EVERY_S)
        frame = sys._current_frames().get(ident)  # noqa: SLF001
        if frame is None:
            break
        looks += 1
        code = frame.f_code
        if (os.path.basename(code.co_filename), code.co_name) not in PROFILE_IDLE:
            busy += 1
            key = _where(frame)
            seen[key] = seen.get(key, 0) + 1
        del frame, code
    if not looks:
        return "Ciclo di Music Assistant: nessuna occhiata"
    top = sorted(seen.items(), key=lambda item: -item[1])[:PROFILE_TOP]
    places = "; ".join(f"{100 * count / looks:.0f}% {key}" for key, count in top)
    return f"Ciclo di Music Assistant al lavoro in {100 * busy / looks:.0f}% di {looks} occhiate" + (
        f": {places}" if places else ""
    )


def _cgroups() -> str:
    """
    In quale gruppo (cgroup) sta Python e in quale l'app che l'ha avviato, e su quali processori può girare: dice se
    Android, quando congela l'app, congela anche il server (stesso gruppo uid_<n>/pid_<n>).
    """

    def read(path: str) -> str:
        try:
            with open(path, encoding="utf-8", errors="replace") as file:
                return " | ".join(line.strip() for line in file if line.strip())[:300]
        except OSError as err:
            return f"non leggibile ({type(err).__name__})"

    parent = os.getppid()
    mine = read("/proc/self/cgroup")
    theirs = read(f"/proc/{parent}/cgroup")
    cpus = "?"
    try:
        with open("/proc/self/status", encoding="utf-8", errors="replace") as file:
            for line in file:
                if line.startswith("Cpus_allowed_list:"):
                    cpus = line.split(":", 1)[1].strip()
    except OSError:
        pass
    same = "uguali" if mine == theirs else "diversi"
    return f"Gruppi (cgroup) di Python: {mine}; dell'app (pid {parent}): {theirs} ({same}); processori: {cpus}"


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
    global _loop, _loop_ident, _mass, _stop_event, _state, _error
    try:
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        _setup_logging(data_dir, log_level)
        try:
            logging.getLogger(OUR_LOGGER).info("%s", _cgroups())
        except Exception as err:  # noqa: BLE001 - solo un aiuto per capire
            _failed_once(logging.getLogger(OUR_LOGGER), "Lettura dei gruppi (cgroup)", err)
        # Importato solo ora: caricare Music Assistant richiede qualche secondo.
        from music_assistant.helpers.logging import activate_log_queue_handler  # noqa: PLC0415
        from music_assistant.mass import MusicAssistant  # noqa: PLC0415

        mass = MusicAssistant(data_dir, cache_dir, False)

        async def run_mass() -> None:
            global _loop, _loop_ident, _mass, _stop_event, _state
            _loop = asyncio.get_running_loop()
            _loop_ident = threading.get_ident()
            _loop.set_default_executor(ThreadPoolExecutor(max_workers=16))
            activate_log_queue_handler()
            _stop_event = asyncio.Event()
            try:
                await mass.start()
                _mass = mass
                _state = "running"
                logging.getLogger("xonos").info("Music Assistant pronto sul telefono")
                if _seccomp and not _ptrace_blocked:
                    logging.getLogger("xonos").info("%s", _seccomp)
                if _leftovers:
                    logging.getLogger("xonos").warning(
                        "Tolti %d processi Linux rimasti accesi da prima (proot, Soloist, PulseAudio)", _leftovers
                    )
                if _ptrace_blocked:
                    logging.getLogger("xonos").warning(
                        "Soloist non può girare: Android non lascia a Xonos seguire i suoi programmi (ptrace: %s). "
                        "Spotify resta con librespot. Su GrapheneOS: nelle informazioni dell'app Xonos permetti il "
                        "debug del codice nativo (Native code debugging), poi Forza interruzione e riapri Xonos.",
                        _ptrace_blocked,
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
                _mass = None
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
        _loop_ident = None
        _mass = None
        _stop_event = None


def _setup_logging(data_dir: str, log_level: str) -> None:
    """
    Tutto il registro (INFO e oltre) va in musicassistant.log, nella cartella dei dati: la persona lo condivide a mano.
    stderr arriva a Xonos, che lo mette nel logcat: lì solo le righe di questo file (il logger "xonos"), una riga per
    record (_OneLine), niente titoli, uri, nomi e indirizzi che Music Assistant scrive nelle sue.

    Music Assistant poi sposta questi gestori dietro una coda (QueueListener senza respect_handler_level): il livello
    di un gestore non conta più, i suoi filtri sì. Per questo stderr ha un filtro (_for_xonos) e non un livello.
    """
    global _log_file
    root = logging.getLogger()
    if getattr(root, "_xonos", False):
        return
    root.setLevel(logging.INFO)
    logging.addLevelName(VERBOSE_LOG_LEVEL, "VERBOSE")
    logging.captureWarnings(True)
    _log_file = os.path.join(data_dir, "musicassistant.log")
    file_handler = RotatingFileHandler(_log_file, maxBytes=LOG_FILE_BYTES, backupCount=1)
    file_handler.setFormatter(logging.Formatter(LOG_FORMAT))
    root.addHandler(file_handler)
    ours = logging.StreamHandler(sys.stderr)
    ours.addFilter(_for_xonos)
    ours.setFormatter(_OneLine())
    root.addHandler(ours)
    for name in QUIET_LOGGERS:
        logging.getLogger(name).setLevel(logging.WARNING)
    logging.getLogger("urllib3.connectionpool").setLevel(logging.ERROR)
    logging.getLogger("music_assistant").setLevel(log_level.upper())
    root._xonos = True  # type: ignore[attr-defined]


def _for_xonos(record: logging.LogRecord) -> bool:
    """Su stderr (il logcat) solo le righe di questo file, e solo finché Xonos le legge."""
    return not _pipe_gone and (record.name == OUR_LOGGER or record.name.startswith(OUR_LOGGER + "."))


class _OneLine(logging.Formatter):
    """Una riga sola per il logcat: "XONOS_LOG <livello> <prima riga del testo>" (il resto, e l'errore, nel file)."""

    def format(self, record: logging.LogRecord) -> str:
        try:
            text = record.getMessage()
        except Exception:  # noqa: BLE001 - argomenti sbagliati: il testo com'è
            text = str(record.msg)
        first = text.split("\n", 1)[0].replace("\r", " ")
        return f"{LOG_PREFIX} {record.levelname} {first}"[:LOG_LINE_MAX]


if __name__ == "__main__":
    sys.exit(main())
