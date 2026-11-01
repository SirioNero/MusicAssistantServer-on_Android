"""
Solo per le prove in CI: Soloist e PulseAudio sul telefono, con il codice vero di Music Assistant.

xonos_server lo avvia dopo la partenza del server quando nella cartella dei dati c'è il file "xonos-selftest"
(lo mette tools/server/emulator.sh; sul telefono di una persona non c'è). Senza account Spotify si arriva fin dove
si può: Music Assistant scarica Soloist (col consenso) e lo prova, avvia PulseAudio come per un brano, crea il
"pipe sink" e ci passa un suono, poi accende Soloist con una chiave finta finché aspetta l'abbinamento.
Nel registro di Music Assistant: righe "XONOS_SELFTEST <prova> ok|fail <dettagli>" e alla fine
"XONOS_SELFTEST fine <riuscite>/<prove>".
"""

from __future__ import annotations

import asyncio
import logging
import os
from contextlib import suppress
from pathlib import Path
from typing import Any

LOGGER = logging.getLogger("xonos.selftest")
SOLOIST_WAIT_S = 30
GO_WAIT_S = 900
FIFO_READ_S = 4


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
    except Exception as err:  # noqa: BLE001
        say("pulseaudio", False, f"{err!r}; {await _pulseaudio_alone(Path(mass.cache_path))}")

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

    if sink is not None:
        with suppress(Exception):
            await sink.unload()
    if server is not None:
        with suppress(Exception):
            await server.release()
    LOGGER.info("XONOS_SELFTEST fine %d/%d", sum(results), len(results))


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
    Cerca i dispositivi Spotify Connect come l'app Spotify: una domanda mDNS (_spotify-connect._tcp.local) in
    multicast e a 127.0.0.1, poi la pagina getInfo del primo che risponde (il suo indirizzo e CPath dal TXT).
    """
    import socket  # noqa: PLC0415

    loop = asyncio.get_running_loop()
    found: dict[str, Any] = {"seen": []}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    sock.bind(("0.0.0.0", 0))
    with suppress(OSError):
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    query = _mdns_query("_spotify-connect._tcp.local")
    deadline = loop.time() + 10
    next_send = 0.0
    try:
        while loop.time() < deadline and "port" not in found:
            if loop.time() >= next_send:
                for target in (("224.0.0.251", 5353), ("127.0.0.1", 5353)):
                    with suppress(OSError):
                        sock.sendto(query, target)
                next_send = loop.time() + 1
            try:
                data, sender = sock.recvfrom(9000)
            except BlockingIOError:
                await asyncio.sleep(0.1)
                continue
            try:
                records = _dns_records(data)
            except Exception:  # noqa: BLE001
                continue
            for kind, name, value in records:
                found["seen"].append(f"{kind} {name} {value}"[:120])
                if "spotify-connect" in name and kind == "SRV":
                    found["port"], found["instance"], found["from"] = value, name, sender[0]
                if "spotify-connect" in name and kind == "TXT":
                    found["txt"] = value
    finally:
        sock.close()
    found["seen"] = found["seen"][:12]
    if "port" in found:
        path = next((item.split("=", 1)[1] for item in found.get("txt", []) if item.lower().startswith("cpath=")), "/")
        found["getinfo"] = await _http_get("127.0.0.1", int(found["port"]), f"{path}?action=getInfo")
    return found


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
    """Un GET HTTP/1.0 semplice: il corpo della risposta (al massimo 4 KB), o l'errore."""
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
        writer.write(f"GET {path} HTTP/1.0\r\nHost: {host}:{port}\r\n\r\n".encode())
        await writer.drain()
        response = await asyncio.wait_for(reader.read(4096), 5)
        writer.close()
    except Exception as err:  # noqa: BLE001
        return f"errore: {err!r}"
    return response.decode(errors="replace").split("\r\n\r\n", 1)[-1]
