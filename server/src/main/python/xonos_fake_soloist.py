"""
Solo per l'autotest in CI (xonos_selftest, prova soloist-sessione): un finto Soloist.

Serve a provare la sessione che resta accesa tra un brano e l'altro (_soloist_live e _soloist_connect in
server/ma/prepare.py) senza un account Spotify. Parla lo stesso protocollo locale di Soloist: la WebSocket su
127.0.0.1, l'indirizzo e la porta nei file ws.addr e ws.port della cartella dei dati, gli eventi (auth_state,
track_changed, playback_state, playback_changed, position_sync) e la conferma di ogni comando (command_result). Al posto
della musica scrive nel tubo del pipe sink di Music Assistant un valore per brano e per secondo (sample_value):
l'autotest riconosce di quale brano, e da quale secondo, è l'audio che gli arriva.

Due modi, come Soloist:
- con --single-track <uri> suona quel brano e accetta pausa, ripresa e seek (anche in pausa); un play di un altro brano
  lo chiude, come il Soloist vero sul telefono dell'utente ("websocket connection closed"); a fine brano passa
  all'autoplay e si chiude;
- senza --single-track (Spotify Connect) parte senza brano, entra, accetta casuale e ripeti, e play(uri) cambia brano;
  a fine brano passa all'autoplay e lo suona finché non lo si mette in pausa, o (XONOS_FAKE_AUTOPLAY=0) si ferma alla
  fine, in pausa; non si chiude mai da solo.

La coda, come in Spotify: add_to_queue mette un brano in coda (provenienza "queue"), get_queue risponde con queue_changed
(i brani in coda, poi l'autoplay), skip_next passa al primo della coda (o all'autoplay), e a fine brano si passa al primo
della coda prima dell'autoplay. Un play(uri) non toglie niente dalla coda (come nelle app di Spotify). Un brano in coda da
almeno PRELOAD_S è già caricato: skip_next lo fa partire in QUEUED_START_S, mentre il play di un altro brano aspetta
XONOS_FAKE_DELAY_PLAY (sotto).

Il sink di PulseAudio sospeso (Music Assistant lo sospende mentre aspetta, e quando cambia brano) non scrive niente nel
tubo: il finto Soloist lo guarda dal socket dei comandi di PulseAudio (list-sinks) e intanto non scrive, come il vero
Soloist che resta fermo quando PulseAudio non prende audio. Senza il socket dei comandi scrive sempre, come prima.

I brani con "short" nell'uri durano 2,5 s, quelli con "sparse" 16 s, gli altri XONOS_FAKE_DURATION_MS (30 s di base).
I brani con "sparse" mandano la posizione solo coi cambi di stato (niente position_sync ogni secondo), e a fine brano
passano alla copia in coda dello stesso brano senza track_changed: così il Soloist vero potrebbe fare, e Xonos deve
riconoscere lo stesso il brano ricominciato. Altre variabili d'ambiente:
XONOS_FAKE_NO_JUMP=1 (in modalità Spotify Connect il play di un altro brano non fa niente), XONOS_FAKE_LEAK=1 (alla
pausa smette di suonare, 0,2 s dopo scrive ancora fino a 0,2 s del brano nel tubo, riga "finto soloist: resto N", e solo
allora conferma: chi svuotasse il tubo prima di aspettare la conferma se lo ritroverebbe nel brano dopo),
XONOS_FAKE_DELAY_PLAY=<ms> (il play di un altro brano parte dopo tanto, come un brano che si carica piano),
XONOS_FAKE_CONNECT_FAIL=1 (in modalità Spotify Connect non entra mai in Spotify), XONOS_FAKE_TAKEOVER=1 (dopo il primo
brano entra un altro account, come da un'altra app nella rete locale: settings/Users/altro-user nella cartella dei dati).
In modalità Spotify Connect, il primo play di un Soloist appena partito: XONOS_FAKE_COLD_MS=<ms> (come il Soloist vero a
freddo sul telefono: subito uno stato "playing" senza il brano chiesto e la conferma, il brano chiesto e l'audio solo
dopo tanto; vale al posto di XONOS_FAKE_DELAY_PLAY), XONOS_FAKE_STUCK=1 (lo stesso, ma il brano non parte mai).
Con --single-track, XONOS_FAKE_SINGLE_FAIL=1: non entra in Spotify e lo dice con "login failed".

Una pausa a un Soloist già fermo riceve un errore e nessuna conferma: il Soloist vero può fare così (il plugin Spotify
Connect di Music Assistant tollera "a rejected or unacknowledged pause"), e Xonos non deve contarci.
"""

from __future__ import annotations

import argparse
import asyncio
import fcntl
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
# FIONREAD di Linux (x86_64 e arm64): quanti byte aspettano nel tubo
FIONREAD = 0x541B
# play_loop lascia posto nel tubo, come PulseAudio che rende al ritmo di chi legge: così il resto della pausa ci entra
ROOM = 32768
# alla pausa il resto arriva tanto dopo, poco prima della conferma
LEAK_DELAY_S = 0.2
SHORT_MS = 2500
SPARSE_MS = 16000
# un brano in coda da tanto è già caricato: skip_next lo fa partire subito (come Spotify, che scarica il brano dopo)
PRELOAD_S = 0.5
QUEUED_START_S = 0.05
# ogni quanto si guarda se il sink è sospeso; il prompt del socket dei comandi di PulseAudio dopo "hello"
SINK_POLL_S = 0.03
PROMPT = b">>> "


def marker(uri: str) -> int:
    """Il valore dei campioni di un brano (lo stesso calcolo è nell'autotest)."""
    return (sum(uri.encode()) % 900 + 100) * 1_000_000


def sample_value(uri: str, position_ms: float) -> int:
    """Il valore di un campione: il brano nei milioni, il secondo del brano nelle migliaia."""
    return marker(uri) + int(position_ms // 1000) % 1000 * 1000


def sink_suspended(listing: str, sink: str) -> bool:
    """Da list-sinks di PulseAudio: se il sink con questo nome è sospeso."""
    named = False
    for raw in listing.splitlines():
        line = raw.strip().lstrip("*").strip()
        if line.startswith("index:"):
            named = False
        elif line.startswith("name:"):
            named = line == f"name: <{sink}>"
        elif named and line.startswith("state:"):
            return line.split(":", 1)[1].strip() == "SUSPENDED"
    return False


class FakeSoloist:
    """Un brano alla volta, comandato dalla WebSocket come Soloist."""

    def __init__(self, uri: str | None) -> None:
        self.uri = uri
        # senza --single-track: Spotify Connect
        self.connect = uri is None
        self.no_jump = os.environ.get("XONOS_FAKE_NO_JUMP") == "1"
        self.leak = os.environ.get("XONOS_FAKE_LEAK") == "1"
        self.delay_play_s = int(os.environ.get("XONOS_FAKE_DELAY_PLAY", "0")) / 1000
        self.autoplay = os.environ.get("XONOS_FAKE_AUTOPLAY", "1") != "0"
        self.connect_fail = os.environ.get("XONOS_FAKE_CONNECT_FAIL") == "1"
        self.takeover = os.environ.get("XONOS_FAKE_TAKEOVER") == "1"
        # il primo brano di un Soloist appena partito in modalità Spotify Connect: lento (a freddo), o mai
        self.cold_s = int(os.environ.get("XONOS_FAKE_COLD_MS", "0")) / 1000
        self.stuck = os.environ.get("XONOS_FAKE_STUCK") == "1"
        self.played = False
        self.data_dir: Path | None = None
        # con --single-track: non entra in Spotify ("login failed"), come un brano o una rete che non vanno
        self.single_fail = uri is not None and os.environ.get("XONOS_FAKE_SINGLE_FAIL") == "1"
        self.playing = uri is not None and not self.single_fail
        self.position_ms = 0.0
        self.sockets: set[web.WebSocketResponse] = set()
        native = os.environ["PULSE_SERVER"].removeprefix("unix:")
        self.sink = os.environ["PULSE_SINK"]
        self.fifo = os.path.join(os.path.dirname(native), self.sink + ".pcm")
        self.cli = os.path.join(os.path.dirname(native), "cli")
        self.fd: int | None = None
        self.done = asyncio.Event()
        # la coda: (uri, quando è entrato); il sink sospeso (watch_sink)
        self.queue: list[tuple[str, float]] = []
        self.suspended = False

    @property
    def duration_ms(self) -> int:
        if self.uri and "short" in self.uri:
            return SHORT_MS
        if self.sparse:
            return SPARSE_MS
        return int(os.environ.get("XONOS_FAKE_DURATION_MS", "30000"))

    @property
    def sparse(self) -> bool:
        """Posizioni rare e niente track_changed per la copia in coda dello stesso brano."""
        return bool(self.uri and "sparse" in self.uri)

    def item(self) -> dict | None:
        if self.uri is None:
            return None
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
        status = "playing" if self.playing else ("paused" if self.uri else "stopped")
        await self.send(
            {
                "type": kind,
                "status": status,
                "item": self.item(),
                "position": self.position(),
                "is_active": True,
            }
        )

    async def handle(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.sockets.add(ws)
        if self.single_fail:
            # niente accesso né brano: Music Assistant lo sa dalla riga "login failed"
            async for _msg in ws:
                pass
            self.sockets.discard(ws)
            return ws
        logged_in = not (self.connect and self.connect_fail)
        await self.send({"type": "auth_state", "logged_in": logged_in, "is_active": True, "device_name": "Xonos finto"})
        await self.send({"type": "device_changed", "is_active": True})
        if self.uri is not None:
            await self.send({"type": "track_changed", "item": self.item()})
        await self.state("playback_state")
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                await self.command(json.loads(msg.data))
        self.sockets.discard(ws)
        return ws

    async def command(self, cmd: dict) -> None:
        name = str(cmd.get("command") or "")
        uri = cmd.get("uri")
        print(f"finto soloist: comando {name} {uri or cmd.get('position_ms') or cmd.get('enabled') or ''}", flush=True)
        first = self.connect and name == "play" and bool(uri) and not self.played
        if first:
            self.played = True
        if first and (self.cold_s or self.stuck):
            # come il Soloist vero appena partito: subito uno stato "playing" senza il brano chiesto, poi la conferma;
            # il brano chiesto lo dice solo quando ne ha l'audio
            await self.send(
                {"type": "playback_changed", "status": "playing", "item": self.item(), "position": self.position()}
            )
            await self.send({"type": "command_result", "command": name})
            if self.stuck:
                print("finto soloist: primo brano che non parte mai", flush=True)
                return
            print(f"finto soloist: primo brano a freddo fra {self.cold_s:.1f} s", flush=True)
            await asyncio.sleep(self.cold_s)
            self.uri = str(uri)
            self.position_ms = 0.0
            self.playing = True
            await self.send({"type": "track_changed", "item": self.item()})
            await self.state()
            return
        if name == "play" and uri and uri != self.uri and not self.connect:
            # come il Soloist vero con --single-track: un altro brano lo chiude
            print("finto soloist: un altro brano con --single-track, mi chiudo", flush=True)
            os._exit(0)
        if name == "pause" and not self.playing:
            # già fermo: un errore, nessuna conferma
            await self.send({"type": "error", "command": name, "message": "nothing is playing"})
            return
        if name == "pause" and self.leak and self.playing:
            # Soloist si ferma, ma un po' del brano arriva nel tubo poco prima della conferma
            self.playing = False
            await asyncio.sleep(LEAK_DELAY_S)
            print(f"finto soloist: resto {self.write(int(RATE * 0.2))}", flush=True)
        await self.send({"type": "command_result", "command": name})
        if name == "add_to_queue" and uri:
            self.queue.append((str(uri), time.monotonic()))
        elif name == "get_queue":
            await self.send_queue()
        elif name == "skip_next":
            await self.skip_next()
        if name == "pause":
            self.playing = False
            await self.state()
        elif name == "play":
            if uri and (uri != self.uri or self.position_ms >= self.duration_ms):
                if self.no_jump and self.uri is not None:
                    return
                if self.delay_play_s:
                    await asyncio.sleep(self.delay_play_s)
                self.uri = str(uri)
                self.position_ms = 0.0
                self.playing = True
                await self.send({"type": "track_changed", "item": self.item()})
                if self.takeover and self.data_dir is not None:
                    # un altro account entra dalla rete locale
                    (self.data_dir / "settings" / "Users" / "altro-user").mkdir(parents=True, exist_ok=True)
            if self.uri is None:
                return
            self.playing = True
            await self.state()
        elif name == "seek":
            self.position_ms = float(cmd.get("position_ms") or 0)
            await self.send({"type": "position_sync", "position": self.position()})

    async def send_queue(self) -> None:
        """La coda come la dice Soloist (queue_changed): i brani in coda, poi l'autoplay."""
        entries = [
            {"uid": f"q{index}", "source": "queue", "item": {"uri": uri, "entity_type": "track", "decorations": {}}}
            for index, (uri, _at) in enumerate(self.queue)
        ]
        if self.connect and self.autoplay:
            entries.append(
                {"uid": "a0", "source": "autoplay", "item": {"uri": AUTOPLAY, "entity_type": "track", "decorations": {}}}
            )
        await self.send({"type": "queue_changed", "previous": [], "upcoming": entries})

    async def skip_next(self) -> None:
        """Al primo della coda (già caricato se ci sta da PRELOAD_S), o all'autoplay; poi suona."""
        if self.queue:
            uri, at = self.queue.pop(0)
            loaded = time.monotonic() - at >= PRELOAD_S
            print(f"finto soloist: avanti al brano in coda ({'già caricato' if loaded else 'da caricare'})", flush=True)
            await asyncio.sleep(QUEUED_START_S if loaded else self.delay_play_s)
        else:
            uri = AUTOPLAY
            await asyncio.sleep(self.delay_play_s)
        self.uri = uri
        self.position_ms = 0.0
        self.playing = True
        await self.send({"type": "track_changed", "item": self.item()})
        await self.state()

    async def watch_sink(self) -> None:
        """Se il sink di PulseAudio è sospeso, dal socket dei comandi (list-sinks); senza socket, mai sospeso."""
        while True:
            try:
                reader, writer = await asyncio.open_unix_connection(self.cli)
            except OSError:
                self.suspended = False
                await asyncio.sleep(0.5)
                continue
            try:
                writer.write(b"hello\n")
                await writer.drain()
                await reader.readuntil(PROMPT)
                while True:
                    writer.write(b"list-sinks\n")
                    await writer.drain()
                    listing = (await reader.readuntil(PROMPT)).decode(errors="replace")
                    self.suspended = sink_suspended(listing, self.sink)
                    await asyncio.sleep(SINK_POLL_S)
            except (OSError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
                self.suspended = False
                await asyncio.sleep(0.5)
            finally:
                writer.close()

    def queued(self) -> int:
        """Quanti byte del brano aspettano nel tubo."""
        if self.fd is None:
            return 0
        try:
            return struct.unpack("i", fcntl.ioctl(self.fd, FIONREAD, b"\0\0\0\0"))[0]
        except OSError:
            return 0

    def write(self, frames: int) -> int:
        """Fino a frames campioni del brano nel tubo; quanti ne sono entrati (il tubo può essere pieno)."""
        if self.uri is None:
            return 0
        if self.fd is None:
            try:
                self.fd = os.open(self.fifo, os.O_WRONLY | os.O_NONBLOCK)
            except OSError:
                return 0
        value = sample_value(self.uri, self.position_ms)
        sample = struct.pack("<ii", value, value)
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
            if not self.playing or not self.sockets or self.suspended or self.queued() >= ROOM:
                await asyncio.sleep(0.02)
                continue
            frames = self.write(CHUNK_FRAMES)
            self.position_ms += frames * 1000 / RATE
            await asyncio.sleep(0.005 if frames < CHUNK_FRAMES else 0)
            if (now := time.monotonic()) - last_sync > 1 and not self.sparse:
                last_sync = now
                await self.send({"type": "position_sync", "position": self.position()})
            if self.position_ms < self.duration_ms:
                continue
            if not self.connect:
                # come Soloist --single-track: a fine brano passa all'autoplay e si chiude
                self.uri = AUTOPLAY
                await self.send({"type": "track_changed", "item": self.item()})
                await self.state()
                self.write(CHUNK_FRAMES)
                await asyncio.sleep(0.3)
                self.done.set()
                return
            if self.queue or self.autoplay:
                # Spotify Connect: a fine brano passa al primo della coda, sennò all'autoplay, e lo suona finché non lo
                # si mette in pausa
                following = self.queue.pop(0)[0] if self.queue else AUTOPLAY
                again = following == self.uri and self.sparse
                self.uri = following
                self.position_ms = 0.0
                if again:
                    print("finto soloist: ricomincio la copia in coda senza track_changed", flush=True)
                else:
                    await self.send({"type": "track_changed", "item": self.item()})
                await self.state()
                await asyncio.sleep(0.3)
            else:
                # Spotify Connect senza autoplay: si ferma alla fine del brano, in pausa
                self.playing = False
                self.position_ms = float(self.duration_ms)
                await self.state()


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--single-track", default=None)
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--ws", default="127.0.0.1:0")
    args, _ = parser.parse_known_args()
    fake = FakeSoloist(args.single_track)
    fake.data_dir = Path(args.data_dir)
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
    mode = "Spotify Connect" if fake.connect else fake.uri
    print(f"finto soloist: {mode} su {host}:{bound}", flush=True)
    if fake.single_fail:
        print("finto soloist: login failed (prova)", flush=True)
    loop = asyncio.create_task(fake.play_loop())
    sink = asyncio.create_task(fake.watch_sink())
    await fake.done.wait()
    loop.cancel()
    sink.cancel()
    # come Soloist: a fine brano il processo se ne va, anche con la WebSocket ancora aperta
    for ws in list(fake.sockets):
        await ws.close()
    os._exit(0)


if __name__ == "__main__":
    print("finto soloist: parte", flush=True)
    try:
        asyncio.run(main())
    except BaseException as err:
        import traceback

        traceback.print_exc()
        print(f"finto soloist: si ferma ({err!r})", flush=True)
        raise
