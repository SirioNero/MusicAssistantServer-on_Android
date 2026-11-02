"""
Solo per l'autotest in CI (xonos_selftest, prova soloist-sessione): un finto Soloist.

Serve a provare la sessione che resta accesa tra un brano e l'altro (_soloist_live in server/ma/prepare.py) senza un
account Spotify. Parla lo stesso protocollo locale di Soloist: la WebSocket su 127.0.0.1, l'indirizzo e la porta nei
file ws.addr e ws.port della cartella dei dati, gli eventi (auth_state, track_changed, playback_state,
playback_changed, position_sync) e la conferma di ogni comando (command_result). Al posto della musica scrive nel tubo
del pipe sink di Music Assistant un valore fisso per brano (marker): l'autotest riconosce di quale brano è l'audio
che gli arriva. Come Soloist con --single-track suona un brano, accetta pausa, ripresa, seek e play di un altro brano,
e a fine brano passa a un altro (autoplay) e si chiude.

Variabili d'ambiente: XONOS_FAKE_DURATION_MS (durata dei brani, 30 s di base), XONOS_FAKE_NO_JUMP=1 (il play di un
altro brano non fa niente, come un Soloist che non accettasse salti), XONOS_FAKE_LEAK=1 (dopo la pausa scrive ancora
0,2 s del brano nel tubo, come audio rimasto indietro).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import struct
import time
from pathlib import Path

from aiohttp import WSMsgType, web

RATE = 44100
# s32le, due canali
FRAME = 8
# 512 campioni sono 4096 byte: una scrittura in un tubo fino a 4096 byte è intera o niente, mai a metà di un campione
CHUNK_FRAMES = 512
AUTOPLAY = "spotify:track:xonosautoplay"


def marker(uri: str) -> int:
    """Il valore dei campioni di un brano (lo stesso calcolo è nell'autotest)."""
    return (sum(uri.encode()) % 900 + 100) * 1_000_000


class FakeSoloist:
    """Un brano alla volta, comandato dalla WebSocket come Soloist."""

    def __init__(self, uri: str) -> None:
        self.uri = uri
        self.duration_ms = int(os.environ.get("XONOS_FAKE_DURATION_MS", "30000"))
        self.no_jump = os.environ.get("XONOS_FAKE_NO_JUMP") == "1"
        self.leak = os.environ.get("XONOS_FAKE_LEAK") == "1"
        self.playing = True
        self.position_ms = 0.0
        self.sockets: set[web.WebSocketResponse] = set()
        native = os.environ["PULSE_SERVER"].removeprefix("unix:")
        self.fifo = os.path.join(os.path.dirname(native), os.environ["PULSE_SINK"] + ".pcm")
        self.fd: int | None = None
        self.done = asyncio.Event()

    def item(self) -> dict:
        return {
            "uri": self.uri,
            "entity_type": "track",
            "decorations": {"playback": {"duration_ms": self.duration_ms}},
        }

    def position(self) -> dict:
        return {"position_ms": int(self.position_ms), "timestamp_ms": int(time.time() * 1000), "speed": 1.0}

    async def send(self, event: dict) -> None:
        text = json.dumps(event)
        for ws in list(self.sockets):
            try:
                await ws.send_str(text)
            except (ConnectionError, RuntimeError):
                self.sockets.discard(ws)

    async def state(self, kind: str = "playback_changed") -> None:
        await self.send(
            {
                "type": kind,
                "status": "playing" if self.playing else "paused",
                "item": self.item(),
                "position": self.position(),
                "is_active": True,
            }
        )

    async def handle(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.sockets.add(ws)
        await self.send({"type": "auth_state", "logged_in": True, "is_active": True, "device_name": "Xonos finto"})
        await self.send({"type": "device_changed", "is_active": True})
        await self.send({"type": "track_changed", "item": self.item()})
        await self.state("playback_state")
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                await self.command(json.loads(msg.data))
        self.sockets.discard(ws)
        return ws

    async def command(self, cmd: dict) -> None:
        name = str(cmd.get("command") or "")
        print(f"finto soloist: comando {name} {cmd.get('uri') or cmd.get('position_ms') or ''}", flush=True)
        await self.send({"type": "command_result", "command": name})
        if name == "pause":
            self.playing = False
            await self.state()
            if self.leak:
                self.write(int(RATE * 0.2))
        elif name == "play":
            uri = cmd.get("uri")
            if uri and uri != self.uri:
                if self.no_jump:
                    return
                self.uri = str(uri)
                self.position_ms = 0.0
                self.playing = True
                await self.send({"type": "track_changed", "item": self.item()})
            self.playing = True
            await self.state()
        elif name == "seek":
            self.position_ms = float(cmd.get("position_ms") or 0)
            await self.send({"type": "position_sync", "position": self.position()})

    def write(self, frames: int) -> int:
        """Fino a frames campioni del brano nel tubo; quanti ne sono entrati (il tubo può essere pieno)."""
        if self.fd is None:
            try:
                self.fd = os.open(self.fifo, os.O_WRONLY | os.O_NONBLOCK)
            except OSError:
                return 0
        sample = struct.pack("<ii", marker(self.uri), marker(self.uri))
        written = 0
        while written < frames:
            count = min(CHUNK_FRAMES, frames - written)
            try:
                os.write(self.fd, sample * count)
            except BlockingIOError:
                break
            written += count
        return written

    async def play_loop(self) -> None:
        last_sync = 0.0
        while True:
            if not self.playing or not self.sockets:
                await asyncio.sleep(0.02)
                continue
            frames = self.write(CHUNK_FRAMES)
            self.position_ms += frames * 1000 / RATE
            await asyncio.sleep(0.005 if frames < CHUNK_FRAMES else 0)
            if (now := time.monotonic()) - last_sync > 1:
                last_sync = now
                await self.send({"type": "position_sync", "position": self.position()})
            if self.position_ms >= self.duration_ms:
                # come Soloist --single-track: a fine brano passa all'autoplay e si chiude
                self.uri = AUTOPLAY
                await self.send({"type": "track_changed", "item": self.item()})
                await self.state()
                self.write(CHUNK_FRAMES)
                await asyncio.sleep(0.3)
                self.done.set()
                return


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--single-track", required=True)
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--ws", default="127.0.0.1:0")
    args, _ = parser.parse_known_args()
    fake = FakeSoloist(args.single_track)
    app = web.Application()
    app.router.add_get("/", fake.handle)
    runner = web.AppRunner(app)
    await runner.setup()
    host, port = args.ws.rsplit(":", 1)
    site = web.TCPSite(runner, host, int(port))
    await site.start()
    bound = site._server.sockets[0].getsockname()[1]  # type: ignore[union-attr]
    data = Path(args.data_dir)
    (data / "ws.addr").write_text(host)
    (data / "ws.port").write_text(str(bound))
    print(f"finto soloist: {fake.uri} su {host}:{bound}", flush=True)
    loop = asyncio.create_task(fake.play_loop())
    await fake.done.wait()
    loop.cancel()
    # come Soloist: a fine brano il processo se ne va, anche con la WebSocket ancora aperta
    for ws in list(fake.sockets):
        await ws.close()
    os._exit(0)


if __name__ == "__main__":
    asyncio.run(main())
