"""
Solo per le prove in CI: Soloist e PulseAudio sul telefono, con il codice vero di Music Assistant.

xonos_server lo avvia dopo la partenza del server quando nella cartella dei dati c'è il file "xonos-selftest"
(lo mette tools/server/emulator.sh; sul telefono di una persona non c'è). Senza account Spotify si arriva fin dove
si può: Music Assistant scarica Soloist (col consenso) e lo prova, avvia PulseAudio come per un brano, crea il
"pipe sink" e ci passa un suono, poi accende Soloist con una chiave finta finché aspetta l'abbinamento.
Poi lo ferma come lo ferma Music Assistant (AsyncProcess.close), guarda quanto processore usano proot e PulseAudio
con dieci secondi di audio a tempo reale, e che PulseAudio si fermi quando non serve più.
Nel registro di Music Assistant: righe "XONOS_SELFTEST <prova> ok|fail <dettagli>" e alla fine
"XONOS_SELFTEST fine <riuscite>/<prove>".
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys
import time
from contextlib import suppress
from pathlib import Path
from typing import Any

LOGGER = logging.getLogger("xonos.selftest")
SOLOIST_WAIT_S = 30
GO_WAIT_S = 900
FIFO_READ_S = 4
CPU_AUDIO_S = 10
# Il tubo del pipe sink di Music Assistant: s32le, 44,1 kHz, stereo.
FIFO_BYTES_PER_S = 44100 * 8


async def run(mass: Any) -> None:
    """Le prove, una dopo l'altra; ognuna dice com'è andata anche se le altre falliscono."""
    # Si parte quando il workflow mette anche "xonos-selftest-go", dopo le sue prove: niente pesi in parallelo.
    go = Path(mass.storage_path) / "xonos-selftest-go"
    for _ in range(GO_WAIT_S // 2):
        if go.exists():
            break
        await asyncio.sleep(2)
    results: list[bool] = []

    def say(what: str, ok: bool, detail: str = "") -> None:
        results.append(ok)
        LOGGER.info("XONOS_SELFTEST %s %s %s", what, "ok" if ok else "fail", detail)

    if not os.environ.get("XONOS_PROOT"):
        say("proot", False, "XONOS_PROOT non c'è (manca proot nell'app o il sistema Linux nel pacchetto)")
        LOGGER.info("XONOS_SELFTEST fine 0/1")
        return

    binary = None
    try:
        from music_assistant.providers.spotify_connect.soloist import SoloistBinaryManager  # noqa: PLC0415

        manager = SoloistBinaryManager(mass)
        binary = await manager.ensure_fresh(consent=True)
        say("soloist-download", True, f"{binary} {manager.diagnostics()}")
    except Exception as err:  # noqa: BLE001
        say("soloist-download", False, repr(err))

    from music_assistant.helpers.pulse_capture import PipeSink, get_pulse_capture_server  # noqa: PLC0415

    server = None
    sink = None
    try:
        server = await get_pulse_capture_server(mass).acquire()
        say("pulseaudio", True, server.server_address)
        sink = await PipeSink.create(server, "xonos_selftest")
        await sink.set_volume(100)
        await sink.resume()
        say("pipe-sink", True, str(sink.fifo_path))
        received = await _play_into(server, sink)
        say("audio-nel-tubo", received > 0, f"{received} byte in {FIFO_READ_S} s")
        # I passi di PulseAudio di ogni brano di Soloist: col socket dei comandi (dal pacchetto 2.10.8-7) e con
        # pactl dentro proot, come prima.
        commands_ok, detail = await _pulse_commands(server)
        say("pulse-comandi", commands_ok, detail)
    except Exception as err:  # noqa: BLE001
        say("pulseaudio", False, f"{err!r}; {await _pulseaudio_alone(Path(mass.cache_path))}")

    if server is not None:
        # La sessione di Soloist che resta accesa tra un brano e l'altro, col codice vero di Music Assistant e un
        # finto Soloist (xonos_fake_soloist.py): play, salto, ripresa, spegnimento, fine brano, salto rifiutato.
        try:
            session_ok, detail = await _soloist_session(mass, server)
            say("soloist-sessione", session_ok, detail)
        except Exception as err:  # noqa: BLE001
            say("soloist-sessione", False, repr(err))

    if binary is not None and server is not None and sink is not None:
        try:
            lines, found = await _soloist(binary, server, sink, Path(mass.cache_path))
            waiting = any("waiting for login" in line for line in lines)
            say("soloist-acceso", waiting, " | ".join(lines[:10]))
            # Come la cerca l'app Spotify per l'abbinamento: mDNS, poi la pagina getInfo di Spotify Connect.
            say("soloist-mdns", found.get("port") is not None, str(found))
            say("soloist-getinfo", bool(found.get("getinfo")), str(found.get("getinfo"))[:300])
        except Exception as err:  # noqa: BLE001
            say("soloist-acceso", False, repr(err))
        try:
            ignored, stopped, detail = await _soloist_stop(binary, server, sink, Path(mass.cache_path))
            # Fino al pacchetto 2.10.8-4 i segnali di Music Assistant andavano al solo proot: rifatto qui, Soloist
            # deve restare acceso (proot li ignora). È il perché di "The Spotify Soloist daemon could not be stopped".
            what = "resta acceso" if ignored else "si ferma"
            say("proot-ignora-sigint", ignored, f"SIGINT al solo proot: Soloist {what}")
            say("soloist-ferma", stopped, detail)
        except Exception as err:  # noqa: BLE001
            say("soloist-ferma", False, repr(err))

    if server is not None and sink is not None:
        try:
            flowing, detail = await _cpu_with_audio(server, sink)
            say("cpu-audio", flowing, detail)
        except Exception as err:  # noqa: BLE001
            say("cpu-audio", False, repr(err))

    if sink is not None:
        with suppress(Exception):
            await sink.unload()
    if server is not None:
        with suppress(Exception):
            await server.release()
        # Music Assistant ferma PulseAudio (SIGINT) quando nessuno lo usa più: non deve restare acceso.
        left = await _still_running("pulseaudio")
        say("pulseaudio-ferma", not left, f"processi rimasti: {len(left)}")
    LOGGER.info("XONOS_SELFTEST fine %d/%d", sum(results), len(results))


def _server_module() -> Any:
    """Il modulo xonos_server già caricato (avviato con "python -m" si chiama __main__)."""
    for name in ("xonos_server", "__main__"):
        module = sys.modules.get(name)
        if module is not None and hasattr(module, "_guest_groups"):
            return module
    raise RuntimeError("xonos_server non è caricato")


async def _still_running(name: str, wait_s: float = 5) -> list[int]:
    """I processi Linux che si chiamano [name] ancora accesi dopo al massimo [wait_s] secondi."""
    server = _server_module()
    left: list[int] = []
    for _ in range(int(wait_s * 2) + 1):
        left = [pid for pid, (argv, _, _) in server._processes().items() if os.path.basename(argv[0]) == name]
        if not left:
            break
        await asyncio.sleep(0.5)
    return left


def _group_ticks(server: Any) -> dict[int, tuple[str, int]]:
    """I processi dei gruppi di proot: pid -> (nome, tick di processore)."""
    groups = set(server._guest_groups)
    found: dict[int, tuple[str, int]] = {}
    for pid, (argv, group, ticks) in server._processes().items():
        if group in groups:
            name = os.path.basename(argv[0])
            found[pid] = ("proot" if name.startswith("libproot") else name, ticks)
    return found


async def _soloist_stop(binary: Path, server: Any, sink: Any, cache: Path) -> tuple[bool, bool, str]:
    """
    Soloist con la chiave finta, avviato e fermato come in un brano di Music Assistant: AsyncProcess, un lettore che
    legge la sua uscita intanto, poi close(). Prima però SIGINT al solo proot, come fino al pacchetto 2.10.8-4.
    Dice se con quello Soloist è rimasto acceso, se close() l'ha fermato del tutto, e i dettagli.
    """
    from music_assistant.helpers.process import AsyncProcess  # noqa: PLC0415

    module = _server_module()
    loop = asyncio.get_running_loop()
    folder = cache / "selftest-soloist-ferma"
    folder.mkdir(parents=True, exist_ok=True)
    proc = AsyncProcess(
        [str(binary), "--device-name", "Xonos prova", "--api-key", "prova",
         "--data-dir", str(folder / "data"), "--cache-dir", str(folder / "cache")],
        stdout=True,
        stderr=asyncio.subprocess.STDOUT,
        name="soloist[prova]",
        env=server.child_env(sink.sink_name),
    )
    await proc.start()
    assert proc.proc is not None
    pid = proc.proc.pid
    waiting = asyncio.Event()

    async def read() -> None:
        async for line in proc.iter_stdout():
            if "waiting for login" in line:
                waiting.set()

    reader = asyncio.create_task(read())
    try:
        await asyncio.wait_for(waiting.wait(), SOLOIST_WAIT_S)
        real_kill = getattr(os.kill, "__wrapped__", os.kill)
        real_kill(pid, signal.SIGINT)
        await asyncio.sleep(3)
        ignored = proc.returncode is None
        started = loop.time()
        await proc.close()
        took = loop.time() - started
        await asyncio.sleep(0.5)
        left = [other for other, (_, group, _) in module._processes().items() if group == pid]
        stopped = proc.returncode is not None and not left
        return ignored, stopped, f"close() in {took:.1f} s, codice {proc.returncode}, rimasti {len(left)}"
    finally:
        reader.cancel()
        with suppress(BaseException):
            await reader
        if proc.returncode is None:
            with suppress(Exception):
                await proc.kill()


async def _cpu_with_audio(server: Any, sink: Any) -> tuple[bool, str]:
    """
    CPU_AUDIO_S secondi di rumore nel pipe sink con pacat (dentro proot), letti dal tubo come li legge Music
    Assistant per Soloist (poco sopra il tempo reale): quanto processore usano proot, PulseAudio e pacat intanto
    (100% = un core intero). Va bene se l'audio scorre; i numeri sono per capire quanto pesa proot.
    """
    module = _server_module()
    loop = asyncio.get_running_loop()
    ticks_per_s = os.sysconf("SC_CLK_TCK") or 100
    fd = os.open(sink.fifo_path, os.O_RDONLY | os.O_NONBLOCK)
    received = 0
    proc = None
    feeder = None
    try:
        proc = await asyncio.create_subprocess_exec(
            "pacat", "--raw", "--format=s16le", "--rate=44100", "--channels=2",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            env=server.child_env(sink.sink_name),
        )

        async def feed() -> None:
            assert proc is not None and proc.stdin is not None
            for _ in range((CPU_AUDIO_S + 5) * 10):
                proc.stdin.write(os.urandom(44100 * 4 // 10))
                await proc.stdin.drain()

        feeder = asyncio.create_task(feed())
        await asyncio.sleep(1)
        before = _group_ticks(module)
        started = loop.time()
        while (elapsed := loop.time() - started) < CPU_AUDIO_S:
            allowed = int(elapsed * FIFO_BYTES_PER_S * 1.1) - received
            if allowed > 0:
                try:
                    received += len(os.read(fd, min(allowed, 65536)))
                except BlockingIOError:
                    pass
            await asyncio.sleep(0.02)
        seconds = loop.time() - started
        after = _group_ticks(module)
    finally:
        if feeder is not None:
            feeder.cancel()
            with suppress(BaseException):
                await feeder
        if proc is not None and proc.returncode is None:
            proc.kill()
            with suppress(Exception):
                await asyncio.wait_for(proc.wait(), 5)
        os.close(fd)
    used: dict[str, float] = {}
    for pid, (name, ticks) in after.items():
        old = before.get(pid)
        used[name] = used.get(name, 0.0) + (ticks - (old[1] if old else 0)) / ticks_per_s
    expected = int(seconds * FIFO_BYTES_PER_S)
    ranked = sorted(used.items(), key=lambda item: -item[1])
    parts = ", ".join(f"{name} {100 * value / seconds:.0f}%" for name, value in ranked)
    return received >= expected // 2, f"{parts}; letti {received} byte su {expected} in {seconds:.0f} s"


async def _pulse_commands(server: Any, rounds: int = 5) -> tuple[bool, str]:
    """
    I passi di PulseAudio di un brano di Soloist (carica il pipe sink, volume, sospendi, riprendi, togli) con il codice
    di Music Assistant: prima col socket dei comandi, poi con pactl (un processo in proot a passo, come fino al
    pacchetto 2.10.8-6). Riesce se il socket è stato usato ed è più veloce; nei dettagli i tempi per brano.
    """
    from music_assistant.helpers.pulse_capture import PipeSink  # noqa: PLC0415

    controller = server._controller

    async def one(prefix: str) -> float:
        start = time.monotonic()
        sink = await PipeSink.create(server, prefix)
        await sink.set_volume(100)
        await sink.suspend()
        await sink.resume()
        await sink.unload()
        return time.monotonic() - start

    with_socket = sorted([await one("xonos_socket") for _ in range(rounds)])
    used_socket = bool(getattr(controller, "uses_socket", False))
    saved = getattr(controller, "_cli_path", None)
    lock = getattr(controller, "_lock", None)
    if lock is not None:
        with lock:
            controller._cli_path = None
            controller._cli_drop()
    try:
        with_pactl = sorted([await one("xonos_pactl") for _ in range(rounds)])
    finally:
        if lock is not None:
            controller._cli_path = saved
    socket_ms = with_socket[rounds // 2] * 1000
    pactl_ms = with_pactl[rounds // 2] * 1000
    detail = (
        f"per brano (mediana di {rounds}): col socket {socket_ms:.0f} ms, con pactl in proot {pactl_ms:.0f} ms"
        f" (socket usato: {used_socket})"
    )
    return used_socket and socket_ms < pactl_ms, detail


async def _soloist_session(mass: Any, server: Any) -> tuple[bool, str]:
    """
    La sessione di Soloist che resta accesa tra un brano e l'altro (_soloist_live in prepare.py).

    Col codice vero di Music Assistant (SoloistBackend, PulseAudio, il tubo, il ritmo di lettura) e un finto Soloist
    (xonos_fake_soloist.py, avviato col Python del pacchetto al posto del programma di Spotify): un brano, un salto a un
    altro, una ripresa a 5 s, lo spegnimento dopo la pausa, la fine naturale di un brano e un Soloist che non accetta
    salti (allora si riparte da capo). L'audio che arriva deve essere sempre e solo del brano chiesto: il finto
    Soloist scrive un valore fisso per brano, e dopo ogni pausa lascia nel tubo un po' del brano di prima.
    """
    from array import array  # noqa: PLC0415
    from types import SimpleNamespace  # noqa: PLC0415

    from music_assistant.providers.spotify.backends import soloist as solo  # noqa: PLC0415
    from music_assistant.providers.spotify.constants import (  # noqa: PLC0415
        CONF_SOLOIST_API_KEY,
        CONF_SOLOIST_CONSENT,
    )

    python = os.path.join(os.environ.get("XONOS_BIN", ""), "python3")
    setup = {CONF_SOLOIST_API_KEY: "xonos-finta", CONF_SOLOIST_CONSENT: True}
    # Anche le righe di debug del motore (lì finisce l'uscita del finto Soloist): se qualcosa non va, nel risultato.
    records: list[str] = []

    class Keep(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            records.append(record.getMessage()[:200])

    log = logging.getLogger("xonos.selftest.soloist")
    log.setLevel(logging.DEBUG)
    keep = Keep(logging.DEBUG)
    log.addHandler(keep)
    provider = SimpleNamespace(
        mass=mass,
        logger=log,
        instance_id="xonos_fake",
        name="Spotify finto",
        available=True,
        spotify_normalization_configured=False,
        get_setup_value=setup.get,
        config=SimpleNamespace(get_value=lambda _key: None),
        unload_with_error=lambda _err: None,
    )
    backend = solo.SoloistBackend(provider)
    backend._server = server
    backend._binary = Path(python)

    class FakeManager:
        """Il programma di Soloist c'è già: è il finto."""

        def __init__(self, _mass: Any) -> None:
            pass

        async def ensure_fresh(self, _consent: bool, **_kwargs: Any) -> Path:
            return Path(python)

    real_manager = solo.SoloistBinaryManager
    real_park = solo._XONOS_PARK_S
    solo.SoloistBinaryManager = FakeManager
    plain_args = backend._session_args
    # come modulo, come xonos_server: nel pacchetto il codice di Xonos può esserci solo compilato (.pyc)
    backend._session_args = lambda uri: [python, "-m", "xonos_fake_soloist", *plain_args(uri)[1:]]

    def mark(uri: str) -> int:
        """Il valore del brano, come lo scrive il finto Soloist (in milioni)."""
        return sum(uri.encode()) % 900 + 100

    async def take(uri: str, name: str, seek: int = 0, seconds: float = 1.0, duration: int = 30) -> tuple:
        """Uno stream come lo chiede Music Assistant: secondi al primo audio, i brani dei valori arrivati, byte."""
        details = SimpleNamespace(uri=f"spotify--xonos://track/{name}", queue_id="xonos_fake_queue", duration=duration)
        start = time.monotonic()
        first = -1.0
        values: set[int] = set()
        got = 0
        gen = backend.stream_spotify_uri(uri, seek, streamdetails=details)
        try:
            async for chunk in gen:
                if first < 0:
                    first = time.monotonic() - start
                values.update(value // 1_000_000 for value in array("i", chunk[: len(chunk) // 4 * 4]))
                got += len(chunk)
                if got >= seconds * FIFO_BYTES_PER_S:
                    break
        finally:
            await gen.aclose()
        return first, values, got

    def alive(run: Any) -> bool:
        return run is not None and run._proc is not None and run._proc.returncode is None

    failed: list[str] = []

    def check(what: str, ok: bool, seen: Any = "") -> None:
        if not ok:
            failed.append(f"{what} ({seen})" if seen != "" else what)

    a, b, c, d, e = (f"spotify:track:xonos{x}" for x in "abcde")
    first_a = first_b = first_r = first_e = -1.0
    try:
        os.environ["XONOS_FAKE_LEAK"] = "1"
        os.environ["XONOS_FAKE_DURATION_MS"] = "30000"
        first_a, values, _ = await take(a, "a", seconds=1.5)
        run = backend._run
        check("audio del primo brano", values == {mark(a)}, values)
        check("in pausa e acceso dopo lo stream", alive(run) and run.xonos_parked)
        first_b, values, _ = await take(b, "b", seconds=1.5)
        check("salto sullo stesso processo", backend._run is run and alive(run))
        check("solo audio del brano saltato", values == {mark(b)}, values)
        solo._XONOS_PARK_S = 1.0
        first_r, values, _ = await take(b, "b", seek=5, seconds=1.0)
        check("ripresa sullo stesso processo", backend._run is run, backend._run)
        check("solo audio del brano ripreso", values == {mark(b)}, values)
        await asyncio.sleep(2.5)
        check("spento dopo la pausa", backend._run is None and not alive(run))
        solo._XONOS_PARK_S = real_park
        os.environ["XONOS_FAKE_DURATION_MS"] = "2500"
        _, values, got = await take(c, "c", seconds=10, duration=3)
        await asyncio.sleep(0.5)
        check("fine naturale del brano", got > 0 and values == {mark(c)} and backend._run is None, (got, values))
        os.environ["XONOS_FAKE_DURATION_MS"] = "30000"
        os.environ["XONOS_FAKE_NO_JUMP"] = "1"
        await take(d, "d", seconds=1.0)
        run_d = backend._run
        first_e, values, _ = await take(e, "e", seconds=1.0)
        check("da capo dopo un salto rifiutato", backend._run is not run_d and values == {mark(e)}, values)
        check("salto rifiutato contato", backend._xonos_failures["salto"] == 1, backend._xonos_failures)
    except Exception as err:  # noqa: BLE001 - nel risultato, con le ultime righe del motore
        failed.append(repr(err))
    finally:
        log.removeHandler(keep)
        for key in ("XONOS_FAKE_LEAK", "XONOS_FAKE_DURATION_MS", "XONOS_FAKE_NO_JUMP"):
            os.environ.pop(key, None)
        solo.SoloistBinaryManager = real_manager
        solo._XONOS_PARK_S = real_park
        backend._xonos_cancel_park()
        async with backend._run_lock:
            if (left := backend._run) is not None:
                await left.stop()
                backend._run = None
    detail = (
        f"primo audio: da capo {first_a:.2f} s, salto {first_b:.2f} s, ripresa {first_r:.2f} s,"
        f" da capo dopo un salto rifiutato {first_e:.2f} s"
    )
    if failed:
        detail += "; non va: " + ", ".join(failed) + " | motore: " + " / ".join(records[-15:])
    return not failed, detail


async def _pulseaudio_alone(cache: Path) -> str:
    """Quando PulseAudio non parte: lo stesso avvio di Music Assistant, con -v, e le ultime righe che scrive."""
    folder = cache / "pulse_capture"
    config = folder / "pulse_capture.pa"
    folder.mkdir(parents=True, exist_ok=True)
    if not config.exists():
        config.write_text(f"load-module module-native-protocol-unix socket={folder / 'native'} auth-anonymous=1\n")
    env = {**os.environ, "HOME": str(folder), "XDG_RUNTIME_DIR": str(folder), "PULSE_RUNTIME_PATH": str(folder),
           "PULSE_STATE_PATH": str(folder)}
    proc = await asyncio.create_subprocess_exec(
        "pulseaudio", "--daemonize=no", "-n", "--exit-idle-time=0", f"--file={config}", "-v",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, env=env,
    )
    try:
        output, _ = await asyncio.wait_for(proc.communicate(), 8)
    except TimeoutError:
        proc.kill()
        output = b"(dopo 8 s ancora acceso)"
    lines = output.decode(errors="replace").strip().splitlines()
    return f"uscita {proc.returncode}: " + " | ".join(lines[-12:])


async def _play_into(server: Any, sink: Any) -> int:
    """Un secondo di rumore nel pipe sink con pacat (dentro proot); quanti byte escono dal tubo."""
    loop = asyncio.get_running_loop()
    # Il lettore va aperto prima: senza, PulseAudio butta quello che scrive nel tubo.
    fd = os.open(sink.fifo_path, os.O_RDONLY | os.O_NONBLOCK)
    received = 0
    try:
        proc = await asyncio.create_subprocess_exec(
            "pacat", "--raw", "--format=s16le", "--rate=44100", "--channels=2",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            env=server.child_env(sink.sink_name),
        )
        assert proc.stdin is not None
        proc.stdin.write(os.urandom(44100 * 4))
        await proc.stdin.drain()
        proc.stdin.close()
        deadline = loop.time() + FIFO_READ_S
        while loop.time() < deadline:
            try:
                chunk = os.read(fd, 65536)
            except BlockingIOError:
                await asyncio.sleep(0.05)
                continue
            received += len(chunk)
            if not chunk:
                await asyncio.sleep(0.05)
        await asyncio.wait_for(proc.wait(), 15)
    finally:
        os.close(fd)
    return received


async def _soloist(binary: Path, server: Any, sink: Any, cache: Path) -> tuple[list[str], dict[str, Any]]:
    """
    Soloist con PulseAudio e una chiave finta: le prime righe, fino a quando aspetta l'abbinamento, e cosa trova
    di lui chi lo cerca come l'app Spotify (mDNS e getInfo), mentre è acceso.
    """
    folder = cache / "selftest-soloist"
    folder.mkdir(parents=True, exist_ok=True)
    proc = await asyncio.create_subprocess_exec(
        str(binary), "--device-name", "Xonos prova", "--api-key", "prova",
        "--data-dir", str(folder / "data"), "--cache-dir", str(folder / "cache"),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=server.child_env(sink.sink_name),
    )
    lines: list[str] = []
    found: dict[str, Any] = {}
    assert proc.stdout is not None
    try:
        async with asyncio.timeout(SOLOIST_WAIT_S):
            while True:
                raw = await proc.stdout.readline()
                if not raw:
                    break
                text = raw.decode(errors="replace").strip()
                lines.append(text)
                if "waiting for login" in text:
                    break
        if proc.returncode is None:
            found = await _find_spotify_connect()
    except TimeoutError:
        lines.append(f"(dopo {SOLOIST_WAIT_S} s ancora niente)")
    finally:
        if proc.returncode is None:
            proc.terminate()
            try:
                await asyncio.wait_for(proc.wait(), 10)
            except TimeoutError:
                proc.kill()
    return lines, found


async def _find_spotify_connect() -> dict[str, Any]:
    """
    Cerca i dispositivi Spotify Connect come l'app Spotify: un socket sulla porta 5353 nel gruppo 224.0.0.251
    (le risposte mDNS di solito vanno a tutto il gruppo) più uno su una porta qualsiasi (risposte dirette), una
    domanda per _spotify-connect._tcp.local ogni secondo, in multicast e a 127.0.0.1; poi la pagina getInfo del
    primo che risponde (porta dal SRV, CPath dal TXT).
    """
    import socket  # noqa: PLC0415
    import struct  # noqa: PLC0415

    loop = asyncio.get_running_loop()
    found: dict[str, Any] = {"seen": [], "local": _local_ipv4()}
    sockets = []
    group = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        group.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        with suppress(AttributeError, OSError):
            group.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        group.bind(("", 5353))
        membership = struct.pack("4s4s", socket.inet_aton("224.0.0.251"), socket.inet_aton("0.0.0.0"))
        group.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
        group.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
        group.setblocking(False)
        sockets.append(group)
    except OSError as err:
        found["group_error"] = repr(err)
        group.close()
    direct = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    direct.bind(("0.0.0.0", 0))
    direct.setblocking(False)
    with suppress(OSError):
        direct.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    sockets.append(direct)
    query = _mdns_query("_spotify-connect._tcp.local")
    deadline = loop.time() + 12
    next_send = 0.0
    try:
        while loop.time() < deadline and "port" not in found:
            if loop.time() >= next_send:
                for sender_socket in sockets:
                    for target in (("224.0.0.251", 5353), ("127.0.0.1", 5353)):
                        with suppress(OSError):
                            sender_socket.sendto(query, target)
                next_send = loop.time() + 1
            got = False
            for receiver in sockets:
                try:
                    data, sender = receiver.recvfrom(9000)
                except (BlockingIOError, OSError):
                    continue
                got = True
                try:
                    records = _dns_records(data)
                except Exception:  # noqa: BLE001
                    continue
                for kind, name, value in records:
                    found["seen"].append(f"{sender[0]} {kind} {name} {value}"[:140])
                    if "spotify-connect" in name and kind == "SRV":
                        found["port"], found["instance"], found["from"] = value, name, sender[0]
                    if "spotify-connect" in name and kind == "TXT":
                        found["txt"] = value
            if not got:
                await asyncio.sleep(0.1)
    finally:
        for sender_socket in sockets:
            sender_socket.close()
    # Le nostre domande tornano indietro sul gruppo: si tengono solo le prime righe, per capire cosa passa.
    found["seen"] = found["seen"][:12]
    if "port" in found:
        path = next((item.split("=", 1)[1] for item in found.get("txt", []) if item.lower().startswith("cpath=")), "/")
        for host in dict.fromkeys(["127.0.0.1", found.get("from", "127.0.0.1")]):
            found[f"getinfo {host}"] = (await _http_get(host, int(found["port"]), f"{path}?action=getInfo"))[:300]
        found["getinfo"] = next((value for key, value in found.items() if key.startswith("getinfo ") and "errore" not in value), None)
    return found


def _local_ipv4() -> str:
    """L'indirizzo IPv4 del telefono sulla rete di default (connect su UDP non manda niente)."""
    import socket  # noqa: PLC0415

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 53))
        return probe.getsockname()[0]
    except OSError as err:
        return repr(err)
    finally:
        probe.close()


def _mdns_query(name: str) -> bytes:
    """Una domanda DNS per i PTR di name (classe IN)."""
    import struct  # noqa: PLC0415

    question = b"".join(bytes([len(part)]) + part.encode() for part in name.split(".")) + b"\x00"
    return struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0) + question + struct.pack("!HH", 12, 1)


def _dns_records(data: bytes) -> list[tuple[str, str, Any]]:
    """Le risposte di un pacchetto DNS: (tipo, nome, valore) per PTR, SRV (la porta), TXT e A."""
    import struct  # noqa: PLC0415

    def name_at(offset: int) -> tuple[str, int]:
        labels: list[str] = []
        end = -1
        for _ in range(64):
            length = data[offset]
            if length & 0xC0 == 0xC0:
                if end < 0:
                    end = offset + 2
                offset = ((length & 0x3F) << 8) | data[offset + 1]
                continue
            if length == 0:
                return ".".join(labels), (end if end >= 0 else offset + 1)
            labels.append(data[offset + 1:offset + 1 + length].decode(errors="replace"))
            offset += 1 + length
        raise ValueError("nome DNS troppo lungo")

    _, _, questions, answers, authority, additional = struct.unpack("!HHHHHH", data[:12])
    offset = 12
    for _ in range(questions):
        _, offset = name_at(offset)
        offset += 4
    records: list[tuple[str, str, Any]] = []
    for _ in range(answers + authority + additional):
        name, offset = name_at(offset)
        kind, _, _, length = struct.unpack("!HHIH", data[offset:offset + 10])
        offset += 10
        body = data[offset:offset + length]
        if kind == 12:
            records.append(("PTR", name, name_at(offset)[0]))
        elif kind == 33:
            records.append(("SRV", name, struct.unpack("!HHH", body[:6])[2]))
        elif kind == 16:
            texts, index = [], 0
            while index < len(body):
                size = body[index]
                texts.append(body[index + 1:index + 1 + size].decode(errors="replace"))
                index += 1 + size
            records.append(("TXT", name, texts))
        elif kind == 1 and length == 4:
            records.append(("A", name, ".".join(str(byte) for byte in body)))
        offset += length
    return records


async def _http_get(host: str, port: int, path: str) -> str:
    """Un GET HTTP/1.0 semplice: il corpo della risposta (al massimo 4 KB, anche se arriva a pezzi), o l'errore."""
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
        writer.write(f"GET {path} HTTP/1.0\r\nHost: {host}:{port}\r\n\r\n".encode())
        await writer.drain()
        head = (await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)).decode(errors="replace")
        length = next(
            (int(line.split(":", 1)[1]) for line in head.split("\r\n") if line.lower().startswith("content-length:")),
            None,
        )
        if length is not None:
            body = await asyncio.wait_for(reader.readexactly(min(length, 4096)), 5)
        else:
            body = await asyncio.wait_for(reader.read(4096), 5)
        writer.close()
    except Exception as err:  # noqa: BLE001
        return f"errore: {err!r}"
    return body.decode(errors="replace")
