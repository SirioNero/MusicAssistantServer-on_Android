"""
Solo per le prove in CI: Soloist e PulseAudio sul telefono, con il codice vero di Music Assistant.

xonos_server lo avvia dopo la partenza del server quando nella cartella dei dati c'è il file "xonos-selftest"
(lo mette tools/server/emulator.sh; sul telefono di una persona non c'è). Senza account Spotify si arriva fin dove
si può: Music Assistant scarica Soloist (col consenso) e lo prova, avvia PulseAudio come per un brano, crea il
"pipe sink" e ci passa un suono, poi accende Soloist con una chiave finta finché aspetta l'abbinamento.
Poi lo ferma come lo ferma Music Assistant (AsyncProcess.close), guarda quanto processore usano proot e PulseAudio
con dieci secondi di audio a tempo reale, con e senza la memoria condivisa di PulseAudio (memfd e srbchannel), e che
PulseAudio si fermi quando non serve più. Prima di tutto, senza Spotify, il blocco di Spotify nel throttler di Music
Assistant (spotify-blocco), e un servizio che non si carica per la rete: riprova presto, il play che lo ricarica, il
cambio di rete (servizio-senza-rete). Poi il brano dopo che va nella coda di Soloist (soloist-brano-dopo, coi calcoli veri
di Music Assistant) e quanto audio tiene PulseAudio per chi suona (pulse-livello, per la riga "dentro il salto").
Nel registro di Music Assistant: righe "XONOS_SELFTEST <prova> ok|fail <dettagli>" e alla fine
"XONOS_SELFTEST fine <riuscite>/<prove>".
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import signal
import sys
import threading
import time
from contextlib import nullcontext, suppress
from pathlib import Path
from typing import Any

LOGGER = logging.getLogger("xonos.selftest")
SOLOIST_WAIT_S = 30
GO_WAIT_S = 900
FIFO_READ_S = 4
CPU_AUDIO_S = 10
# pulse-memfd: secondi di audio per ognuna delle due prove, e la latenza chiesta da pacat (pacchetti piccoli, come Soloist)
PULSE_AB_S = 4
PULSE_AB_LATENCY_MS = 20
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

    # Il blocco di Spotify (Retry-After lungo) nel throttler di Music Assistant, con un servizio finto: un secondo.
    try:
        block_ok, detail = await _spotify_block()
        say("spotify-blocco", block_ok, detail)
    except Exception as err:  # noqa: BLE001
        say("spotify-blocco", False, repr(err))

    # Un servizio che non si carica per un errore di rete (pacchetto 2.10.8-13): si riprova presto, un play lo fa ricaricare
    # subito, un cambio di rete pure. Con un servizio finto sul codice vero di Music Assistant: una ventina di secondi.
    try:
        retry_ok, detail = await _provider_retry()
        say("servizio-senza-rete", retry_ok, detail)
    except Exception as err:  # noqa: BLE001
        say("servizio-senza-rete", False, repr(err))

    # Il profilo di Python che xonos_server scrive quando Python lavora tanto: thread, lavori di Music Assistant e
    # dove sta il ciclo. Qui con un ciclo occupato a scrivere JSON e thread che nascono e finiscono: tre secondi.
    try:
        profile_ok, detail = await _profile_check(_server_module())
        say("profilo", profile_ok, detail)
    except Exception as err:  # noqa: BLE001
        say("profilo", False, repr(err))

    binary = None
    try:
        from music_assistant.providers.spotify_connect.soloist import SoloistBinaryManager  # noqa: PLC0415

        manager = SoloistBinaryManager(mass)
        binary = await manager.ensure_fresh(consent=True)
        say("soloist-download", True, f"{binary} {manager.diagnostics()}")
        # Le opzioni di Soloist: in modalità Spotify Connect si annuncia nella rete locale (zeroconf), e un'opzione per
        # non farlo (come --disable-discovery di librespot) chiuderebbe la porta agli altri account. Solo per saperlo.
        say("soloist-opzioni", True, await _soloist_help(binary))
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

    # Il brano dopo che va nella coda di Soloist, dalla coda di Music Assistant (con ripeti, brani non disponibili, brani
    # presi da un altro servizio): i calcoli veri di Music Assistant su una coda finta.
    try:
        next_ok, detail = _next_item_check()
        say("soloist-brano-dopo", next_ok, detail)
    except Exception as err:  # noqa: BLE001
        say("soloist-brano-dopo", False, repr(err))

    if server is not None:
        # La sessione di Soloist che resta accesa tra un brano e l'altro, col codice vero di Music Assistant e un
        # finto Soloist (xonos_fake_soloist.py): play, salto, ripresa, spegnimento, fine brano, salto rifiutato, e il
        # brano dopo nella coda di Soloist (avanti con skip_next, fine brano verso la coda, brano rimasto in coda).
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
        # Lo stesso con poca latenza, senza e con la memoria condivisa di PulseAudio: cosa cambia per proot.
        try:
            played, detail = await _pulse_memfd(server, sink, Path(mass.cache_path))
            say("pulse-memfd", played, detail)
        except Exception as err:  # noqa: BLE001
            say("pulse-memfd", False, repr(err))
        # Quanto audio tiene PulseAudio per chi suona sul sink, letto come lo legge la riga "dentro il salto" di Soloist.
        try:
            level_ok, detail = await _pulse_level(server, sink)
            say("pulse-livello", level_ok, detail)
        except Exception as err:  # noqa: BLE001
            say("pulse-livello", False, repr(err))

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


async def _spotify_block() -> tuple[bool, str]:
    """
    Il blocco di Spotify nel throttler di Music Assistant (_spotify_limits in prepare.py), con un servizio finto.

    Un primo 429 con Retry-After 3555 s: errore subito, coi minuti che mancano e i testi di Spotify, e chi usa il
    throttler lo viene a sapere una volta. Poi una chiamata della riproduzione (BYPASS_THROTTLER) si ferma senza
    chiamare il servizio. Fuori da un blocco la riproduzione non aspetta un blocco breve, ma prende un posto nel limite:
    con un limite di 3 al secondo, 6 chiamate di fila ci mettono circa un secondo.
    """
    from music_assistant_models.errors import RateLimited, RetriesExhausted  # noqa: PLC0415

    from music_assistant.helpers.throttle_retry import (  # noqa: PLC0415
        BYPASS_THROTTLER,
        ThrottlerManager,
        throttle_with_retries,
    )

    class Service:
        """Un servizio finto con il throttler di Music Assistant: conta le chiamate, la prima può ricevere un 429."""

        def __init__(self, rate_limit: int, refuse_first: bool = False) -> None:
            self.logger = logging.getLogger("xonos.selftest.throttle")
            self.throttler = ThrottlerManager(rate_limit=rate_limit, period=1)
            self.calls = 0
            self.refuse_first = refuse_first

        @throttle_with_retries
        async def get(self) -> int:
            self.calls += 1
            if self.refuse_first and self.calls == 1:
                raise RateLimited("429 finto", backoff_time=3555)
            return self.calls

    async def error_of(call: Any) -> BaseException | None:
        try:
            await call
        except Exception as err:  # noqa: BLE001 - è quello che si guarda
            return err
        return None

    def key(err: BaseException | None) -> str:
        if err is None:
            return "nessun errore"
        args = getattr(err, "translation_args", None)
        return f"{type(err).__name__} {getattr(err, 'translation_key', None)} {args} {getattr(err, 'translation_owner', None)}"

    problems: list[str] = []
    blocked = Service(2, refuse_first=True)
    told: list[float] = []
    blocked.throttler.xonos_on_block = told.append
    blocked.throttler.xonos_owner = "provider.spotify"
    first = await error_of(blocked.get())
    wanted = isinstance(first, RetriesExhausted) and first.translation_key == "xonos_rate_limited"
    if not (wanted and first.translation_args == [59] and first.translation_owner == "provider.spotify"):
        problems.append(f"primo errore: {key(first)}")
    if told != [3555]:
        problems.append(f"avvisi del blocco: {told}")
    token = BYPASS_THROTTLER.set(True)
    try:
        second = await error_of(blocked.get())
        free = Service(3)
        free.throttler.set_cooldown(30)  # un blocco breve: la riproduzione non lo aspetta
        start = time.monotonic()
        await free.get()
        short = time.monotonic() - start
        start = time.monotonic()
        await asyncio.gather(*(free.get() for _ in range(5)))
        burst = time.monotonic() - start
    finally:
        BYPASS_THROTTLER.reset(token)
    if not (isinstance(second, RetriesExhausted) and second.translation_key == "xonos_rate_limited"):
        problems.append(f"riproduzione durante il blocco: {key(second)}")
    if blocked.calls != 1:
        problems.append(f"il servizio chiamato {blocked.calls} volte invece di una")
    if short > 0.5:
        problems.append(f"la riproduzione ha aspettato un blocco breve ({short:.2f} s)")
    if free.calls != 6 or burst < 0.8:
        problems.append(f"fuori dal blocco: {free.calls} chiamate in {burst:.2f} s (attese 6 in circa 1 s)")
    detail = (
        f"429 con Retry-After 3555 s: {key(first)}, avvisi {told}; la riproduzione durante il blocco: {key(second)},"
        f" chiamate al servizio {blocked.calls}; fuori dal blocco: blocco breve aspettato {short:.2f} s,"
        f" 6 chiamate col limite di 3 al secondo in {burst:.2f} s"
    )
    return not problems, f"{'; '.join(problems)} | {detail}" if problems else detail


async def _provider_retry() -> tuple[bool, str]:  # noqa: PLR0915
    """
    I servizi che non si caricano per un errore di rete (_provider_retry in prepare.py), col codice vero di Music
    Assistant: MusicAssistant.load_provider (con la riprova), xonos_revive, il controllo dei cambi di rete e
    QueueLoaderMixin._xonos_load_item, il pezzo di play_index che carica un brano. Su un Music Assistant di prova (creato
    e non avviato: niente database, niente servizi veri) con un servizio finto: il suo caricamento (load_provider_config)
    alza gli errori che si vogliono, come ClientConnectorDNSError di Spotify senza rete. Senza rete vera e senza Spotify.

    - un errore di rete si riprova dopo 10 s, con "(will be retried later)" nel registro di Music Assistant e la riga di
      Xonos (col dominio, mai il nome dato dalla persona né l'istanza); al tentativo dopo (il timer vero) il servizio si
      carica e i tentativi si azzerano;
    - tre errori di rete di fila: 10, 20, 40 s, e la traccia dell'errore nel registro solo la prima volta; un LoginFailed
      con dentro un errore di rete come la rete; un SetupFailedError senza rete dopo 120 s come Music Assistant, anche
      col limite di Music Assistant a un passo del caricamento, ma con il timeout di una chiamata come la rete; un
      ValueError, un file che manca e un indirizzo senza http:// non si riprovano;
    - un brano col servizio che aspetta di riprovare: il play lo fa caricare subito (prima del timer), il brano si
      riprova e si carica; due play insieme fanno un caricamento solo;
    - il servizio che resta senza rete: l'errore xonos_service_offline col nome del servizio, e un altro play subito dopo
      non fa un altro caricamento (al massimo uno ogni 10 s); un brano segnato non disponibile col servizio caricato si
      prova davvero se è quello scelto, e resta un errore di Music Assistant se il play è già passato oltre;
    - un cambio di rete (Xonos riscrive il file del DNS) fa riprovare subito un servizio che aspetta;
    - un servizio ricaricato da fuori (load_provider_config, come config/providers/reload) senza rete si riprova anche lui;
    - play_media vero su una coda finta (_play_media_cases);
    - il caricamento chiesto da un play parte senza il suo contesto (BYPASS_THROTTLER spento).
    """
    import shutil  # noqa: PLC0415
    import socket  # noqa: PLC0415
    import tempfile  # noqa: PLC0415
    from functools import partial  # noqa: PLC0415
    from types import SimpleNamespace  # noqa: PLC0415

    import aiohttp  # noqa: PLC0415
    from music_assistant_models.errors import (  # noqa: PLC0415
        LoginFailed,
        MediaNotFoundError,
        SetupFailedError,
    )
    from music_assistant_models.enums import QueueOption  # noqa: PLC0415
    from music_assistant_models.helpers import get_global_cache_value  # noqa: PLC0415
    from music_assistant_models.media_items import Album, Playlist, ProviderMapping, Track  # noqa: PLC0415
    from music_assistant_models.queue_item import QueueItem  # noqa: PLC0415

    from music_assistant import mass as mass_module  # noqa: PLC0415
    from music_assistant.controllers.player_queues.queue_loader import QueueLoaderMixin  # noqa: PLC0415
    from music_assistant.helpers.throttle_retry import BYPASS_THROTTLER  # noqa: PLC0415

    instance = "xonos_prova--rete"
    domain = "xonos_prova"
    other = "xonos_altro--sempre"  # un servizio sempre caricato, per le liste con un brano che si suona subito
    folder = Path(tempfile.mkdtemp(prefix="xonos-servizio-"))
    dns_file = folder / "resolv.conf"
    dns_file.write_text("# xonos: rete prova\nnameserver 1.1.1.1\n")
    test = mass_module.MusicAssistant(str(folder), str(folder))
    test.loop = asyncio.get_running_loop()
    test.loop_thread_id = threading.get_ident()
    test._xonos_dns_file = str(dns_file)
    # cosa alzano i prossimi caricamenti (None: riesce), quanto durano, quando sono partiti
    errors: list[BaseException | None] = []
    load_s = [0.0]
    calls: list[float] = []
    bypass: list[bool] = []  # se il caricamento è partito con BYPASS_THROTTLER acceso (il contesto di un play)

    def mark(name: str, loaded: bool) -> None:
        # come _update_available_providers_cache di Music Assistant: MediaItem.available guarda questo insieme, che nel
        # server vero c'è (un nome finto in più non tocca i suoi brani)
        providers = get_global_cache_value("available_providers")
        if isinstance(providers, set):
            if loaded:
                providers.add(name)
            else:
                providers.discard(name)

    class Config:
        """Le impostazioni del servizio finto."""

        async def get_provider_config(self, instance_id: str) -> Any:
            if instance_id != instance:
                raise KeyError(instance_id)
            return SimpleNamespace(
                instance_id=instance, domain=domain, name="Il mio servizio", enabled=True, last_error=None
            )

        def update_provider_last_error(self, instance_id: str, error: Any) -> None:
            pass

    async def load_provider_config(prov_conf: Any) -> None:
        calls.append(time.monotonic())
        bypass.append(BYPASS_THROTTLER.get())
        if load_s[0]:
            await asyncio.sleep(load_s[0])
        error = errors.pop(0) if errors else None
        if error is not None:
            raise error
        test._providers[instance] = SimpleNamespace(
            instance_id=instance,
            domain=domain,
            name="Il mio servizio",
            available=True,
            manifest=SimpleNamespace(depends_on=None),
        )
        mark(instance, True)

    test.config = Config()
    test.load_provider_config = load_provider_config

    class Catch(logging.Handler):
        """Le righe del registro di Music Assistant e di Xonos durante la prova."""

        def __init__(self) -> None:
            super().__init__()
            self.lines: list[tuple[str, str]] = []
            self.traced: list[bool] = []  # per ogni "Error loading provider": se c'è la traccia dell'errore

        def emit(self, record: logging.LogRecord) -> None:
            with suppress(Exception):
                self.lines.append((record.name, record.getMessage()))
                if record.name == "music_assistant" and record.getMessage().startswith("Error loading provider"):
                    self.traced.append(record.exc_info is not None)

    catch = Catch()
    watched = [logging.getLogger("music_assistant"), logging.getLogger("xonos.servizi")]
    for logger in watched:
        logger.addHandler(catch)

    def dns_error() -> BaseException:
        return aiohttp.ClientConnectorDNSError(
            SimpleNamespace(host="accounts.spotify.com", port=443, ssl=True),
            socket.gaierror(7, "No address associated with hostname"),
        )

    def timer() -> float | None:
        handle = test._tracked_timers.get(f"load_provider_{instance}")
        return None if handle is None else round(handle.when() - test.loop.time(), 1)

    def unload() -> None:
        test._providers.pop(instance, None)
        mark(instance, False)

    def reset() -> None:
        unload()
        test.cancel_timer(f"load_provider_{instance}")
        test._xonos_tries.pop(instance, None)
        test._xonos_offline.discard(instance)
        test._xonos_revive_at.clear()
        errors.clear()
        load_s[0] = 0.0

    def near(value: float | None, wanted: float) -> bool:
        return value is not None and wanted - 1.5 <= value <= wanted + 0.5

    def entry(number: int) -> QueueItem:
        track = Track(
            item_id=str(number),
            provider="library",
            name=f"brano {number}",
            provider_mappings={ProviderMapping(item_id=f"b{number}", provider_domain=domain, provider_instance=instance)},
        )
        return QueueItem(
            queue_id="coda",
            queue_item_id=f"voce{number}",
            name=f"brano {number}",
            duration=200,
            media_item=track,
            available=False,
        )

    item_loads: list[str] = []

    async def load_item(queue_item: QueueItem, is_start: bool = False, seek_position: int = 0, fade_in: bool = False) -> None:
        item_loads.append(queue_item.queue_item_id)
        if not queue_item.available or test.get_provider(instance) is None:
            raise MediaNotFoundError(f"Item {queue_item.uri} is not available")

    loader = SimpleNamespace(mass=test, _load_item=load_item, logger=logging.getLogger("music_assistant.xonos_prova"))
    play = partial(QueueLoaderMixin._xonos_load_item, loader)

    async def play_error(item: QueueItem, down: dict[str, object]) -> BaseException | None:
        try:
            await play(item, down, is_start=True)
        except Exception as err:  # noqa: BLE001 - è quello che si guarda
            return err
        return None

    problems: list[str] = []
    seen: list[str] = []
    saved = (mass_module._XONOS_NET_CHECK_S, mass_module._XONOS_NET_SETTLE_S)
    try:
        # 1. un errore di rete: riprova dopo 10 s, poi il timer vero lo carica
        errors[:] = [dns_error()]
        failed_at = time.monotonic()
        await test.load_provider(instance, allow_retry=True)
        first = timer()
        retried = any("(will be retried later)" in text for name, text in catch.lines if name == "music_assistant")
        ours = [text for name, text in catch.lines if name == "xonos.servizi"]
        if not near(first, 10) or not retried:
            problems.append(f"primo errore di rete: riprova fra {first} s, 'will be retried later' {retried}")
        if not ours or "tentativo 1" not in ours[0] or "10 s" not in ours[0]:
            problems.append(f"riga di Xonos: {ours[:1]}")
        while test.get_provider(instance) is None and time.monotonic() - failed_at < 14:
            await asyncio.sleep(0.2)
        later = time.monotonic() - failed_at
        if test.get_provider(instance) is None or not 9 <= later <= 12 or instance in test._xonos_tries or timer():
            problems.append(f"tentativo dopo: caricato {test.get_provider(instance) is not None} dopo {later:.1f} s")
        seen.append(f"errore di rete: riprova fra {first} s, caricato al tentativo dopo ({later:.1f} s)")

        # 2. tre errori di rete di fila: 10, 20, 40 s, e la traccia dell'errore nel registro solo la prima volta
        reset()
        delays = []
        traced_from = len(catch.traced)
        for _ in range(3):
            errors[:] = [dns_error()]
            await test.load_provider(instance, allow_retry=True)
            delays.append(timer())
        traced = catch.traced[traced_from:]
        if not all(near(got, wanted) for got, wanted in zip(delays, (10, 20, 40), strict=True)):
            problems.append(f"tre errori di rete: {delays}")
        if traced != [True, False, False]:
            problems.append(f"traccia dell'errore nei tre tentativi: {traced}")
        seen.append(f"tre di fila {delays}, traccia {traced}")

        # 3. gli altri errori
        kinds = []
        step_limit = TimeoutError()
        step_limit.xonos_step_limit = True  # come lo segna _provider_load_step quando scade il suo limite
        for what, error, cause, wanted in (
            ("LoginFailed con la rete dentro", LoginFailed("accesso"), dns_error(), 10),
            ("SetupFailedError", SetupFailedError("finto"), None, 120),
            ("ValueError", ValueError("finto"), None, None),
            ("file che manca", FileNotFoundError(2, "No such file or directory"), None, None),
            ("indirizzo senza http://", LoginFailed("accesso"), aiohttp.NonHttpUrlClientError("192.168.1.10:8096"), None),
            ("limite di un passo del caricamento", SetupFailedError("finto"), step_limit, 120),
            ("timeout di una chiamata", SetupFailedError("finto"), TimeoutError(), 10),
        ):
            reset()
            error.__cause__ = cause
            errors[:] = [error]
            await test.load_provider(instance, allow_retry=True)
            got = timer()
            if (got is None) != (wanted is None) or (wanted is not None and not near(got, wanted)):
                problems.append(f"{what}: riprova fra {got} s invece di {wanted}")
            kinds.append(f"{what} {got}")
        seen.append(", ".join(kinds))

        # 4. un brano col servizio che aspetta di riprovare: il play lo carica subito
        reset()
        errors[:] = [dns_error()]
        await test.load_provider(instance, allow_retry=True)
        load_s[0] = 0.3
        before = len(calls)
        start = time.monotonic()
        first_item = entry(1)
        error = await play_error(first_item, {})
        took = time.monotonic() - start
        if error is not None or len(calls) != before + 1 or not first_item.available or took > 2 or timer():
            problems.append(f"play che ricarica: {error!r}, caricamenti {len(calls) - before}, {took:.2f} s")
        seen.append(f"play che ricarica: {took:.2f} s, caricamenti {len(calls) - before}")

        # due play insieme: un caricamento solo
        reset()
        errors[:] = [dns_error()]
        await test.load_provider(instance, allow_retry=True)
        load_s[0] = 0.5
        before = len(calls)
        both = await asyncio.gather(play_error(entry(2), {}), play_error(entry(3), {}))
        if any(x is not None for x in both) or len(calls) != before + 1:
            problems.append(f"due play insieme: {both}, caricamenti {len(calls) - before}")
        seen.append(f"due play insieme: caricamenti {len(calls) - before}")

        # 5. il servizio resta senza rete
        reset()
        errors[:] = [dns_error(), dns_error()]
        await test.load_provider(instance, allow_retry=True)
        before = len(calls)
        down: dict[str, object] = {}
        error = await play_error(entry(4), down)
        key = getattr(error, "translation_key", None)
        args = getattr(error, "translation_args", None)
        if key != "xonos_service_offline" or args != [domain] or down.get("error") is not error or len(calls) != before + 1:
            problems.append(f"senza rete: {error!r} {key} {args}, caricamenti {len(calls) - before}")
        start = time.monotonic()
        again = await play_error(entry(5), {})
        took = time.monotonic() - start
        if getattr(again, "translation_key", None) != "xonos_service_offline" or len(calls) != before + 1 or took > 0.5:
            problems.append(f"subito dopo: {again!r}, caricamenti {len(calls) - before}, {took:.2f} s")
        seen.append(f"senza rete: {key}, subito dopo nessun caricamento in più ({took:.2f} s)")
        # un brano segnato non disponibile col servizio caricato: quello scelto (il primo del play) si prova davvero, il
        # segno può venire da un errore che non c'è più; uno dopo (il play è già passato oltre) dà l'errore di Music
        # Assistant come prima; nessun caricamento
        reset()
        await test.load_provider(instance, allow_retry=True)
        before = len(calls)
        chosen = entry(6)
        chosen_error = await play_error(chosen, {})
        plain = await play_error(entry(7), {"chosen": True})
        if chosen_error is not None or not chosen.available:
            problems.append(f"brano scelto segnato non disponibile: {chosen_error!r}, disponibile {chosen.available}")
        if not isinstance(plain, MediaNotFoundError) or str(plain.translation_key).startswith("xonos_") or len(calls) != before:
            problems.append(f"servizio caricato: {plain!r}, caricamenti {len(calls) - before}")
        seen.append("brano scelto segnato non disponibile: riprovato; il brano dopo: errore di Music Assistant")

        # 6. un cambio di rete fa riprovare subito
        reset()
        if test._xonos_watch is not None:
            test._xonos_watch.cancel()
            with suppress(BaseException):
                await test._xonos_watch
        mass_module._XONOS_NET_CHECK_S = 0.3
        mass_module._XONOS_NET_SETTLE_S = 0.2
        errors[:] = [dns_error()]
        await test.load_provider(instance, allow_retry=True)
        before = len(calls)
        await asyncio.sleep(0.5)
        fresh = folder / "resolv.conf.new"
        fresh.write_text("# xonos: rete nuova\nnameserver 9.9.9.9\n")
        os.replace(fresh, dns_file)
        changed_at = time.monotonic()
        while test.get_provider(instance) is None and time.monotonic() - changed_at < 5:
            await asyncio.sleep(0.1)
        took = time.monotonic() - changed_at
        if test.get_provider(instance) is None or len(calls) != before + 1 or took > 3:
            problems.append(f"cambio di rete: caricato {test.get_provider(instance) is not None} in {took:.1f} s")
        seen.append(f"cambio di rete: caricato in {took:.1f} s")

        # 7. ricaricato da fuori (config/providers/reload, che Xonos usa quando Soloist è occupato) senza rete: si
        # riprova anche lì, e l'errore arriva comunque a chi ha chiesto
        reset()

        async def broken_load(prov_conf: Any) -> None:
            raise dns_error()

        test._load_provider = broken_load
        outside: BaseException | None = None
        try:
            await mass_module.MusicAssistant.load_provider_config(test, await test.config.get_provider_config(instance))
        except Exception as err:  # noqa: BLE001 - è quello che si guarda
            outside = err
        finally:
            del test._load_provider
        got = timer()
        if not isinstance(outside, aiohttp.ClientConnectorDNSError) or not near(got, 10):
            problems.append(f"ricaricato da fuori: {outside!r}, riprova fra {got} s")
        seen.append(f"ricaricato da fuori senza rete: riprova fra {got} s")

        # 8. play_media col codice vero (QueueLoaderMixin._handle_play_media) su una coda finta
        pm_ok, pm_detail = await _play_media_cases(
            SimpleNamespace(
                test=test,
                instance=instance,
                other=other,
                domain=domain,
                errors=errors,
                load_s=load_s,
                calls=calls,
                bypass=bypass,
                reset=reset,
                dns_error=dns_error,
                mark=mark,
                loader=QueueLoaderMixin,
                models=SimpleNamespace(Album=Album, Playlist=Playlist, ProviderMapping=ProviderMapping, Track=Track),
                option=QueueOption,
            )
        )
        if not pm_ok:
            problems.append(pm_detail)
        seen.append(pm_detail if pm_ok else "play_media: vedi sopra")

        # 9. il caricamento chiesto da un play non prende il suo contesto (BYPASS_THROTTLER acceso): la sincronizzazione
        # e i timer che crea passerebbero davanti ai limiti di Spotify come la riproduzione
        reset()
        errors[:] = [dns_error()]
        await test.load_provider(instance, allow_retry=True)
        bypass.clear()

        async def from_play() -> bool:
            BYPASS_THROTTLER.set(True)  # come _load_item e _handle_play_media
            return await test.xonos_revive(instance)

        revived = await asyncio.ensure_future(from_play())
        if not revived or bypass != [False]:
            problems.append(f"contesto del caricamento da un play: caricato {revived}, BYPASS_THROTTLER {bypass}")
        seen.append(f"caricamento da un play senza BYPASS_THROTTLER ({bypass})")

        # nelle righe di Xonos (il logcat) solo il dominio
        ours = [text for name, text in catch.lines if name == "xonos.servizi"]
        leaked = [text for text in ours if "Il mio servizio" in text or instance in text]
        if leaked:
            problems.append(f"nel logcat il nome o l'istanza: {leaked[:2]}")
        seen.append(f"{len(ours)} righe di Xonos")
    finally:
        mass_module._XONOS_NET_CHECK_S, mass_module._XONOS_NET_SETTLE_S = saved
        mark(instance, False)
        mark(other, False)
        for logger in watched:
            logger.removeHandler(catch)
        for handle in list(test._tracked_timers.values()):
            handle.cancel()
        test._tracked_timers.clear()
        for task in list(test._tracked_tasks.values()):
            task.cancel()
        shutil.rmtree(folder, ignore_errors=True)
    detail = "; ".join(seen)
    return not problems, f"{'; '.join(problems)} | {detail}" if problems else detail


async def _play_media_cases(kit: Any) -> tuple[bool, str]:  # noqa: PLR0915
    """
    play_media (QueueLoaderMixin._handle_play_media, il codice vero di Music Assistant con le modifiche di Xonos) con
    il servizio finto di _provider_retry che aspetta di riprovare, su una coda finta: get_item_by_uri e il resolver finti
    danno brani disponibili solo se il servizio è caricato, e il resolver toglie quelli non disponibili come quello vero.

    - un album della libreria (i brani li ha già tolti il resolver: il servizio si trova nelle mappature dell'album),
      aggiunto in coda (ADD) a una coda con una fonte: il servizio si ricarica, il play si rifà, e fonti ed elementi
      chiesti ci sono una volta sola;
    - una playlist di Music Assistant (mappatura builtin: non dice da quali servizi vengono i brani): si ricarica il
      servizio che aspetta la rete;
    - il servizio che resta senza rete: xonos_service_offline, un caricamento solo, e un play subito dopo non ne fa un
      altro;
    - una lista con un brano che si suona subito e uno del servizio che si ricarica: con la rete il servizio torna e ci
      sono tutti e due, con un caricamento lento si aspettano al massimo 3 s e si suona quello che c'è.

    Un brano è disponibile come in Music Assistant: se il suo servizio è nell'insieme available_providers della cache
    globale (MediaItem.available), che il server vero ha sempre; il servizio finto ci entra quando si carica.
    """
    from types import SimpleNamespace  # noqa: PLC0415

    from music_assistant_models.helpers import get_global_cache_value  # noqa: PLC0415

    test = kit.test
    models = kit.models
    queue_option = kit.option

    def song(number: int, service: str | None = None) -> Any:
        service = service or kit.instance
        kit.mark(service, test.get_provider(service) is not None)
        return models.Track(
            item_id=str(number),
            provider="library",
            name=f"brano {number}",
            provider_mappings={
                models.ProviderMapping(
                    item_id=f"b{number}", provider_domain=service.split("--")[0], provider_instance=service
                )
            },
        )

    album = models.Album(
        item_id="1",
        provider="library",
        name="album",
        provider_mappings={
            models.ProviderMapping(item_id="a1", provider_domain=kit.domain, provider_instance=kit.instance)
        },
    )
    playlist = models.Playlist(
        item_id="2",
        provider="library",
        name="playlist",
        provider_mappings={models.ProviderMapping(item_id="p2", provider_domain="builtin", provider_instance="builtin")},
    )

    async def by_uri(uri: str) -> Any:
        kind, _, number = uri.removeprefix("library://").partition("/")
        if kind == "album":
            return album
        if kind == "playlist":
            return playlist
        return song(99, kit.other) if number == "altro" else song(int(number))

    async def resolve(item: Any, start_item: Any, **kwargs: Any) -> list[Any]:
        if isinstance(item, models.Track):
            return [item]
        # come il resolver di Music Assistant: i brani non disponibili restano fuori
        return [track for track in (song(11), song(12), song(13)) if track.available]

    class Queues:
        """Una coda finta attorno a _handle_play_media vero."""

        _handle_play_media = kit.loader._handle_play_media
        _xonos_waiting_service = kit.loader._xonos_waiting_service
        _reset_enqueued_media_items = kit.loader._reset_enqueued_media_items

        def __init__(self, sources: list[Any] | None = None) -> None:
            self.mass = test
            self.logger = logging.getLogger("music_assistant.xonos_prova")
            self.queue = SimpleNamespace(
                queue_id="coda",
                is_dynamic=False,
                shuffle_enabled=False,
                smart_shuffle_active=False,
                extra_attributes={},
            )
            self.data = SimpleNamespace(
                queue=self.queue,
                source_items=list(sources or []),
                enqueued_media_items=[],
                credited_albums=set(),
                userid=None,
                play_action_refcount=0,
            )
            self._queue_data = {"coda": self.data}
            self._media_resolver = SimpleNamespace(_resolve_media_items=resolve)
            self.enqueued: list[list[str]] = []

        def get(self, queue_id: str) -> Any:
            return self.queue

        def _set_transitioning(self, queue_id: str, value: bool) -> None:
            pass

        # per handle_play_action, che avvolge _handle_play_media (il lucchetto del lettore e lo stato della coda)
        def signal_update(self, queue_id: str) -> None:
            pass

        def on_player_update(self, player: Any, changed: dict[str, Any]) -> None:
            pass

        async def _apply_shuffle(self, queue_id: str, option: Any, shuffle: Any) -> None:
            pass

        def store_sources(self, queue: Any, items: list[Any]) -> None:
            self.data.source_items = items

        def is_smart_shuffle_active(self, queue: Any) -> bool:
            return False

        async def _enqueue_with_option(self, queue_id: str, queue_items: list[Any], option: Any, pin_first: bool = False) -> None:
            self.enqueued.append([x.media_item.item_id for x in queue_items])

    async def play(queues: Queues, media: Any, option: Any) -> BaseException | None:
        async def go() -> BaseException | None:
            try:
                await queues._handle_play_media("coda", media, option=option)
            except Exception as err:  # noqa: BLE001 - è quello che si guarda
                return err
            return None

        # in un task suo: _handle_play_media accende BYPASS_THROTTLER nel suo contesto, che resta lì
        return await asyncio.ensure_future(go())

    async def waiting_again(slow: float = 0.0, fails: int = 1) -> None:
        kit.reset()
        kit.errors[:] = [kit.dns_error() for _ in range(fails)]
        await test.load_provider(kit.instance, allow_retry=True)
        kit.load_s[0] = slow

    def key(err: BaseException | None) -> str:
        if err is None:
            return "nessun errore"
        if hasattr(err, "translation_key"):
            return f"{type(err).__name__} {err.translation_key}"
        # un errore che non è di Music Assistant è un guasto della coda finta: dove e cosa
        import traceback  # noqa: PLC0415
        frames = traceback.extract_tb(err.__traceback__)[-3:]
        where = " < ".join(f"{Path(f.filename).name}:{f.lineno} {f.name}" for f in reversed(frames))
        return f"{type(err).__name__} ({str(err)[:160]}) a {where}"

    if not isinstance(get_global_cache_value("available_providers"), set):
        return False, "play_media: available_providers non c'è nella cache globale (il server non ha servizi caricati?)"
    problems: list[str] = []
    seen: list[str] = []
    saved = (getattr(test, "players", None), getattr(test, "music", None))
    test.players = SimpleNamespace(
        get_player=lambda queue_id, raise_unavailable=False: SimpleNamespace(extra_data={}),
        # il lucchetto del lettore (e del gruppo) di handle_play_action: qui c'è una coda sola
        get_group_and_player_lock=lambda player_id: nullcontext(),
    )
    test.music = SimpleNamespace(get_item_by_uri=by_uri)
    test._providers[kit.other] = SimpleNamespace(
        instance_id=kit.other, domain="xonos_altro", name="altro", available=True, manifest=SimpleNamespace(depends_on=None)
    )
    kit.mark(kit.other, True)
    try:
        # un album della libreria aggiunto in coda: il servizio si ricarica e il play si rifà, fonti una volta sola
        await waiting_again()
        queues = Queues([song(98, kit.other)])
        before = len(kit.calls)
        error = await play(queues, "library://album/1", queue_option.ADD)
        sources = [x.item_id for x in queues.data.source_items]
        asked = [x.item_id for x in queues.data.enqueued_media_items]
        loads = len(kit.calls) - before
        if error is not None or queues.enqueued != [["11", "12", "13"]] or loads != 1:
            problems.append(f"album: {key(error)}, in coda {queues.enqueued}, caricamenti {loads}")
        if sources != ["98", "1"] or asked != ["1"]:
            problems.append(f"album rifatto: fonti {sources}, elementi chiesti {asked} (attesi 98, 1 e 1)")
        seen.append(f"album: in coda {queues.enqueued}, fonti {sources}, caricamenti {loads}")

        # una playlist di Music Assistant: si ricarica il servizio che aspetta la rete
        await waiting_again()
        queues = Queues()
        before = len(kit.calls)
        error = await play(queues, "library://playlist/2", queue_option.PLAY)
        loads = len(kit.calls) - before
        if error is not None or queues.enqueued != [["11", "12", "13"]] or loads != 1:
            problems.append(f"playlist di Music Assistant: {key(error)}, in coda {queues.enqueued}, caricamenti {loads}")
        seen.append(f"playlist di Music Assistant: in coda {queues.enqueued}")

        # il servizio resta senza rete: l'errore dice quale e perché; un play subito dopo non fa un altro caricamento
        await waiting_again(fails=2)
        queues = Queues()
        before = len(kit.calls)
        error = await play(queues, "library://album/1", queue_option.PLAY)
        start = time.monotonic()
        again = await play(queues, "library://album/1", queue_option.PLAY)
        took = time.monotonic() - start
        loads = len(kit.calls) - before
        wanted = "xonos_service_offline"
        if getattr(error, "translation_key", None) != wanted or getattr(again, "translation_key", None) != wanted:
            problems.append(f"senza rete: {key(error)}, subito dopo {key(again)}")
        if loads != 1 or took > 0.5 or queues.enqueued:
            problems.append(f"senza rete: caricamenti {loads}, secondo play {took:.2f} s, in coda {queues.enqueued}")
        seen.append(f"album senza rete: {wanted}, caricamenti {loads}, secondo play {took:.2f} s")

        # una lista con un brano che si suona subito: con la rete il servizio torna e ci sono tutti e due
        await waiting_again(slow=0.3)
        queues = Queues()
        start = time.monotonic()
        error = await play(queues, ["library://track/altro", "library://track/21"], queue_option.PLAY)
        took = time.monotonic() - start
        if error is not None or queues.enqueued != [["99", "21"]] or took > 2:
            problems.append(f"lista, servizio che torna: {key(error)}, in coda {queues.enqueued}, {took:.2f} s")
        seen.append(f"lista col servizio che torna: in coda {queues.enqueued} in {took:.2f} s")

        # con un caricamento lento si aspettano al massimo 3 s, poi si suona quello che c'è
        await waiting_again(slow=5.0)
        queues = Queues()
        start = time.monotonic()
        error = await play(queues, ["library://track/altro", "library://track/22"], queue_option.PLAY)
        took = time.monotonic() - start
        if error is not None or queues.enqueued != [["99"]] or not 2.5 <= took <= 4.5:
            problems.append(f"lista, caricamento lento: {key(error)}, in coda {queues.enqueued}, {took:.2f} s")
        seen.append(f"lista col caricamento lento: in coda {queues.enqueued} dopo {took:.2f} s")
        # il caricamento continua da solo: finisce prima della prova dopo
        if pending := list(test._xonos_loads.values()):
            await asyncio.wait(pending, timeout=8)
    finally:
        kit.reset()
        test._providers.pop(kit.other, None)
        kit.mark(kit.other, False)
        test.players, test.music = saved
    detail = "play_media: " + "; ".join(seen)
    return not problems, f"{'; '.join(problems)} | {detail}" if problems else detail


async def _profile_check(module: Any) -> tuple[bool, str]:
    """Il profilo di Python: il ciclo che lavora (json) si vede, e thread che nascono e finiscono non fermano niente."""
    stop = time.monotonic() + 3
    payload = [{"n": i, "items": list(range(30))} for i in range(200)]

    async def busy() -> None:
        while time.monotonic() < stop:
            end = time.perf_counter() + 0.012
            while time.perf_counter() < end:
                json.dumps(payload)
            await asyncio.sleep(0.008)

    def churn() -> None:
        while time.monotonic() < stop:
            short = threading.Thread(target=lambda: sum(range(20000)), name="breve")
            short.start()
            short.join()

    work = asyncio.create_task(busy())
    threading.Thread(target=churn, daemon=True).start()
    ticks = os.sysconf("SC_CLK_TCK") or 100
    first = module._thread_ticks()
    tasks = await asyncio.to_thread(module._running_tasks)
    sample = await asyncio.to_thread(module._loop_sample, None, 2.0)
    threads = module._threads_line(first, module._thread_ticks(), ticks, 2.0)
    await work
    ok = "json/encoder.py" in sample and "xonos_selftest" in sample and "ha risposto" in tasks
    return ok, f"{threads} | {tasks} | {sample}"


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
    return received >= expected // 2, (
        f"{parts}; letti {received} byte su {expected} in {seconds:.0f} s"
        f" (pacat con la latenza predefinita; client.conf del sistema Linux: {_client_conf()})"
    )


def _client_conf() -> str:
    """La memoria condivisa nel client.conf del sistema Linux del pacchetto (quello che usano pacat e Soloist)."""
    path = Path(os.environ.get("XONOS_BIN", "")).parent / "rootfs" / "etc" / "pulse" / "client.conf"
    try:
        lines = [line.strip() for line in path.read_text().splitlines()]
    except OSError as err:
        return f"non letto ({err.strerror})"
    return ", ".join(line for line in lines if line.startswith(("enable-shm", "enable-memfd"))) or "senza righe sulla memoria"


async def _pulse_memfd(server: Any, sink: Any, cache: Path) -> tuple[bool, str]:
    """
    PulseAudio senza e con la memoria condivisa (memfd e srbchannel), col demone di Music Assistant.

    pacat suona con poca latenza (--latency-msec=PULSE_AB_LATENCY_MS: pacchetti piccoli, come Soloist) per PULSE_AB_S
    secondi, letti dal tubo come li legge Music Assistant: prima con un client.conf senza memoria condivisa (com'era il
    sistema Linux del pacchetto prima), poi con quello del sistema Linux. Per ognuna: quanto processore usano
    PulseAudio, pacat e i loro proot, quante volte al secondo si fermano i proot (cambi di contesto da /proc/<pid>/status:
    ogni chiamata che proot intercetta ne costa almeno due), cosa dice PulseAudio dell'accordo (SHM, srbchannel) e
    quanti memfd ha aperto pacat. Va bene se in tutte e due l'audio di pacat arriva nel tubo; i numeri servono a capire.
    """
    plain = cache / "selftest-client-noshm.conf"
    plain.write_text("autospawn = no\nenable-shm = no\nenable-memfd = no\n")
    # Le righe sull'accordo PulseAudio le scrive a livello debug: le prove di prima, che leggono il tubo senza freni (un
    # "underrun" a ogni pacchetto), possono averlo fatto passare a info (_xonos_pulse_chatty in prepare.py). Qui il tubo
    # si legge al ritmo di Music Assistant; poi PulseAudio si ferma (pulseaudio-ferma).
    if (send := getattr(server._controller, "_cli_send", None)) is not None:
        await asyncio.to_thread(send, "set-log-level 4")
    played_both = True
    parts: list[str] = []
    for label, conf in (("senza memoria condivisa", plain), ("con quella del sistema Linux", None)):
        played, detail = await _pulse_run(server, sink, conf)
        played_both = played_both and played
        parts.append(f"{label}: {detail}")
    return played_both, " || ".join(parts)


async def _pulse_run(server: Any, sink: Any, client_conf: Path | None) -> tuple[bool, str]:
    """Una prova di _pulse_memfd: pacat per PULSE_AB_S secondi con il client.conf dato (None: quello del sistema Linux)."""
    from music_assistant.helpers import pulse_capture  # noqa: PLC0415

    module = _server_module()
    loop = asyncio.get_running_loop()
    ticks_per_s = os.sysconf("SC_CLK_TCK") or 100
    env = server.child_env(sink.sink_name)
    if client_conf is not None:
        env["PULSE_CLIENTCONFIG"] = str(client_conf)
    since = time.monotonic()
    fd = os.open(sink.fifo_path, os.O_RDONLY | os.O_NONBLOCK)
    # quello che è rimasto nel tubo dalle prove di prima: via, sennò sembrerebbe di questo pacat
    with suppress(BlockingIOError):
        for _ in range(64):
            if not os.read(fd, 65536):
                break
    received = 0
    sound = False
    memfds = ("?", "?")
    proc = None
    feeder = None
    try:
        proc = await asyncio.create_subprocess_exec(
            "pacat", "--raw", "--format=s16le", "--rate=44100", "--channels=2",
            f"--latency-msec={PULSE_AB_LATENCY_MS}",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            env=env,
        )

        async def feed() -> None:
            assert proc is not None and proc.stdin is not None
            for _ in range((PULSE_AB_S + 3) * 10):
                proc.stdin.write(os.urandom(44100 * 4 // 10))
                await proc.stdin.drain()

        feeder = asyncio.create_task(feed())
        await asyncio.sleep(1)
        before = _guest_usage(module)
        started = loop.time()
        while (elapsed := loop.time() - started) < PULSE_AB_S:
            allowed = int(elapsed * FIFO_BYTES_PER_S * 1.1) - received
            if allowed > 0:
                try:
                    chunk = os.read(fd, min(allowed, 65536))
                except BlockingIOError:
                    chunk = b""
                received += len(chunk)
                # il rumore di pacat, non il silenzio che il sink scrive quando nessuno suona
                sound = sound or bool(chunk.strip(b"\0"))
            await asyncio.sleep(0.02)
        seconds = loop.time() - started
        after = _guest_usage(module)
        memfds = (_memfds(module, "pacat", proc.pid), _memfds(module, "pulseaudio"))
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
    used: dict[str, list[float]] = {}
    for pid, (name, ticks, switches) in after.items():
        old = before.get(pid, (name, 0, 0))
        total = used.setdefault(name, [0.0, 0.0])
        total[0] += (ticks - old[1]) / ticks_per_s
        total[1] += switches - old[2]
    ranked = sorted(used.items(), key=lambda item: -item[1][0])
    usage = ", ".join(
        f"{name} {100 * cpu / seconds:.0f}%" + (f" ({switches / seconds:.0f} cambi/s)" if name.startswith("proot") else "")
        for name, (cpu, switches) in ranked
    )
    said = [_pulse_text(line) for when, line in list(pulse_capture._xonos_pulse_seen) if when >= since]
    agreed = "; ".join(line for line in said if "SHM" in line or "srbchannel" in line or "memory pool" in line)
    if not agreed:
        agreed = "nessuna riga sull'accordo"
    expected = int(seconds * FIFO_BYTES_PER_S)
    heard = "sì" if sound else "no"
    return sound, (
        f"{usage}; letti {received} byte su {expected} in {seconds:.0f} s, audio di pacat: {heard};"
        f" PulseAudio: {agreed}; zone memfd di pacat {memfds[0]}, di pulseaudio {memfds[1]}"
    )


async def _pulse_level(server: Any, sink: Any) -> tuple[bool, str]:
    """
    Quanto audio tiene PulseAudio per chi suona sul sink (list-sink-inputs dal socket dei comandi), letto come lo legge la
    riga "dentro il salto" di Soloist (_xonos_input_latency in prepare.py): pacat suona 2 s di rumore nel sink, letto dal
    tubo al ritmo di Music Assistant. Va bene se con pacat si legge un numero (senza flusso deve dire None).
    """
    from music_assistant.providers.spotify.backends import soloist as solo  # noqa: PLC0415

    send = getattr(server._controller, "_cli_send", None)
    if send is None:
        return False, "il socket dei comandi di PulseAudio non c'è"

    async def level() -> float | None:
        return solo._xonos_input_latency(await asyncio.to_thread(send, "list-sink-inputs"), sink.sink_name)

    empty = await level()
    loop = asyncio.get_running_loop()
    fd = os.open(sink.fifo_path, os.O_RDONLY | os.O_NONBLOCK)
    proc = None
    feeder = None
    levels: list[float | None] = []
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
            for _ in range(40):
                proc.stdin.write(os.urandom(44100 * 4 // 10))
                await proc.stdin.drain()

        feeder = asyncio.create_task(feed())
        started = loop.time()
        received = 0
        polled = started
        while (elapsed := loop.time() - started) < 2.5:
            allowed = int(elapsed * FIFO_BYTES_PER_S * 1.1) - received
            if allowed > 0:
                with suppress(BlockingIOError):
                    received += len(os.read(fd, min(allowed, 65536)))
            if loop.time() - polled >= 0.2:
                polled = loop.time()
                levels.append(await level())
            await asyncio.sleep(0.02)
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
    seen = [value for value in levels if value is not None]
    shown = ", ".join("%.0f ms" % value for value in seen[:12]) or "niente"
    return bool(seen), f"senza flusso: {empty}; con pacat: {shown} ({len(levels)} letture)"


def _next_item_check() -> tuple[bool, str]:
    """
    Il brano dopo che va nella coda di Soloist (SoloistBackend._xonos_next_uri in prepare.py).

    Con le classi vere di Music Assistant (QueueItem, Track, ProviderMapping, StreamDetails) e i suoi calcoli del brano
    dopo (get_next_item e _get_next_index, che tengono conto di ripeti e dei brani non disponibili) su una coda finta:
    il brano dopo, un brano non disponibile saltato, la fine della coda (niente; con ripeti tutto il primo), ripeti un
    brano (niente), un brano che Music Assistant prende dai file del telefono o da un altro servizio Spotify (niente), il
    brano di questa sessione cercato in tutta la coda.
    """
    from functools import partial  # noqa: PLC0415
    from types import SimpleNamespace  # noqa: PLC0415

    from music_assistant_models.enums import ContentType, RepeatMode  # noqa: PLC0415
    from music_assistant_models.media_items import AudioFormat, ProviderMapping, Track  # noqa: PLC0415
    from music_assistant_models.queue_item import QueueItem  # noqa: PLC0415
    from music_assistant_models.streamdetails import StreamDetails  # noqa: PLC0415

    from music_assistant.controllers.player_queues.controller import PlayerQueuesController  # noqa: PLC0415
    from music_assistant.controllers.player_queues.queue_loader import QueueLoaderMixin  # noqa: PLC0415
    from music_assistant.providers.spotify.backends import soloist as solo  # noqa: PLC0415

    ours = "spotify--xonosprova"
    spotify = AudioFormat(content_type=ContentType.OGG, bit_rate=320)
    files = AudioFormat(content_type=ContentType.FLAC, sample_rate=44100, bit_depth=16)

    def mapping(instance: str, item_id: str, audio: AudioFormat = spotify) -> ProviderMapping:
        return ProviderMapping(
            item_id=item_id, provider_domain=instance.split("--")[0], provider_instance=instance, audio_format=audio
        )

    def entry(index: int, *mappings: ProviderMapping, available: bool = True) -> QueueItem:
        track = Track(item_id=str(index), provider="library", name=f"brano {index}", provider_mappings=set(mappings))
        return QueueItem(
            queue_id="coda",
            queue_item_id=f"voce{index}",
            name=f"brano {index}",
            duration=200,
            media_item=track,
            available=available,
        )

    items = [
        entry(0, mapping(ours, "zero")),
        entry(1, mapping(ours, "uno")),
        entry(2, mapping(ours, "due"), available=False),
        entry(3, mapping(ours, "tre")),
    ]
    queue = SimpleNamespace(repeat_mode=RepeatMode.OFF, index_in_buffer=0, current_index=0)
    queues = SimpleNamespace(_queue_data={"coda": SimpleNamespace(queue=queue, items=items)})
    queues.get = lambda queue_id: queue if queue_id == "coda" else None
    queues.queue_data_or_none = queues._queue_data.get
    queues.get_item = partial(PlayerQueuesController.get_item, queues)
    queues.index_by_id = partial(PlayerQueuesController.index_by_id, queues)
    queues.get_next_item = partial(PlayerQueuesController.get_next_item, queues)
    queues._get_next_index = partial(QueueLoaderMixin._get_next_index, queues)
    backend = SimpleNamespace(mass=SimpleNamespace(player_queues=queues), provider=SimpleNamespace(instance_id=ours))

    def after(index: int, repeat: Any = RepeatMode.OFF, *, near: bool = True) -> str | None:
        details = StreamDetails(provider=ours, item_id=f"brano{index}", audio_format=spotify)
        for position, item in enumerate(items):
            item.streamdetails = details if position == index else None
        queue.repeat_mode = repeat
        queue.index_in_buffer = queue.current_index = index if near else None
        run = SimpleNamespace(_xonos_details=details, queue_id="coda")
        return solo.SoloistBackend._xonos_next_uri(backend, run)

    cases = [
        ("il dopo", after(0), "spotify:track:uno"),
        ("non disponibile saltato", after(1), "spotify:track:tre"),
        ("fine della coda", after(3), None),
        ("ripeti tutto alla fine", after(3, RepeatMode.ALL), "spotify:track:zero"),
        ("ripeti un brano", after(0, RepeatMode.ONE), None),
        ("cercato in tutta la coda", after(0, near=False), "spotify:track:uno"),
    ]
    items[1] = entry(1, mapping(ours, "uno"), mapping("filesystem_local--telefono", "uno.flac", files))
    cases.append(("dai file del telefono", after(0), None))
    items[1] = entry(1, mapping("spotify--altro", "uno"))
    cases.append(("da un altro servizio Spotify", after(0), None))
    wrong = [f"{what}: {got} invece di {wanted}" for what, got, wanted in cases if got != wanted]
    return not wrong, "; ".join(wrong) or f"{len(cases)} casi giusti"


def _guest_usage(server: Any) -> dict[int, tuple[str, int, int]]:
    """
    I processi dei gruppi di proot: pid -> (nome, tick di processore, cambi di contesto). Un proot si chiama
    proot[<programma che segue>]: ogni proot parte in un gruppo suo, col programma che segue.
    """
    groups = set(server._guest_groups)
    processes = {pid: info for pid, info in server._processes().items() if info[1] in groups}
    followed = {
        group: os.path.basename(argv[0])
        for argv, group, _ in processes.values()
        if not os.path.basename(argv[0]).startswith("libproot")
    }
    found: dict[int, tuple[str, int, int]] = {}
    for pid, (argv, group, ticks) in processes.items():
        name = os.path.basename(argv[0])
        if name.startswith("libproot"):
            name = f"proot[{followed.get(group, '?')}]"
        found[pid] = (name, ticks, _switches(pid))
    return found


def _switches(pid: int) -> int:
    """I cambi di contesto di un processo (volontari e no), da /proc/<pid>/status; 0 se non si legge."""
    try:
        with open(f"/proc/{pid}/status") as file:
            return sum(
                int(line.split()[1])
                for line in file
                if line.startswith(("voluntary_ctxt_switches:", "nonvoluntary_ctxt_switches:"))
            )
    except (OSError, ValueError, IndexError):
        return 0


def _memfds(server: Any, name: str, group: int | None = None) -> str:
    """
    Quante zone di memoria condivisa di PulseAudio (memfd, "/memfd:pulseaudio" in /proc/<pid>/maps) ha il programma Linux
    [name] (del gruppo [group], se c'è). In un chroot col PulseAudio del pacchetto: pacat 0 senza, 4 con.
    """
    for pid, (argv, owner, _) in server._processes().items():
        if os.path.basename(argv[0]) == name and (group is None or owner == group):
            try:
                with open(f"/proc/{pid}/maps") as file:
                    return str(sum("/memfd:" in line for line in file))
            except OSError as err:
                return f"non lette ({err.strerror})"
    return "processo non trovato"


def _pulse_text(line: str) -> str:
    """Una riga di PulseAudio senza il livello, il thread e il file davanti ("D: [pulseaudio] protocol-native.c: ")."""
    return re.sub(r"^[A-Z]: \[[^]]*\] [\w.-]+: ", "", line.strip())


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


async def _soloist_help(binary: Path) -> str:
    """L'aiuto di Soloist (--help, poi quello dei comandi ctl), su una riga: opzioni e comandi."""
    lines: list[str] = []
    for args in (("--help",), ("ctl", "--help")):
        flag = " ".join(args)
        try:
            proc = await asyncio.create_subprocess_exec(
                str(binary), *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
            )
            out, _ = await asyncio.wait_for(proc.communicate(), 20)
        except Exception as err:  # noqa: BLE001 - nel risultato
            lines.append(f"{flag}: {err!r}")
            continue
        lines.append(f"{flag} ({proc.returncode}): " + " | ".join(out.decode(errors="replace").split("\n")))
    return " || ".join(lines)[:8000]


async def _soloist_session(mass: Any, server: Any) -> tuple[bool, str]:
    """
    La sessione di Soloist che resta accesa tra un brano e l'altro (_soloist_live e _soloist_connect in prepare.py).

    Col codice vero di Music Assistant (SoloistBackend, PulseAudio, il tubo, il ritmo di lettura) e un finto Soloist
    (xonos_fake_soloist.py, avviato col Python del pacchetto al posto del programma di Spotify). Con un processo per
    brano (--single-track): il salto riparte da capo (Soloist vero si chiude a un play di un altro brano), la ripresa a
    1 s resta sullo stesso processo e parte dal punto chiesto (Soloist in pausa è più avanti), lo spegnimento dopo la
    pausa, la fine naturale. In modalità Spotify Connect: il salto sullo stesso processo, la ripresa, la fine naturale
    con l'autoplay e senza (Soloist resta acceso e il brano dopo suona sulla stessa sessione), un cambio annullato a metà
    (Soloist si spegne), un Soloist che non accetta salti (allora si riparte da capo), i salti riprovati dopo un po'
    quando ne sono falliti due, e la modalità Spotify Connect che non parte (allora un processo per brano). Poi il brano
    dopo nella coda di Soloist (_soloist_preload in prepare.py; il brano dopo di Music Assistant lo dà la prova, nexts),
    con un play lento (600 ms) e il brano in coda già caricato: avanti con skip_next (più veloce del play), un salto a
    un brano non in coda (play, e quello in coda resta), il brano rimasto in coda (a fine brano Soloist ci passa: niente
    suo audio, poi il brano dopo con play) e la fine di un brano verso quello in coda (ripresa, senza play). L'audio che
    arriva deve essere sempre e solo del brano chiesto, dal secondo chiesto: il finto Soloist scrive un valore per brano
    e per secondo, e a ogni pausa lascia nel tubo un po' del brano di prima.
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
    # come sul telefono: l'abbinamento a Spotify c'è (senza, un Soloist che non entra sarebbe "abbinamento perso")
    backend._has_stored_session = lambda: True

    class FakeManager:
        """Il programma di Soloist c'è già: è il finto."""

        def __init__(self, _mass: Any) -> None:
            pass

        async def ensure_fresh(self, _consent: bool, **_kwargs: Any) -> Path:
            return Path(python)

    real = {
        name: getattr(solo, name)
        for name in (
            "SoloistBinaryManager",
            "_XONOS_PARK_S",
            "_XONOS_SWITCH_S",
            "_JUMP_TIMEOUT_S",
            "_XONOS_RETRY_S",
            "_XONOS_CONNECT",
            "_XONOS_CONNECT_LOGIN_S",
            "_XONOS_CONNECT_START_S",
            "_XONOS_PRELOAD_AFTER_S",
        )
    }
    solo.SoloistBinaryManager = FakeManager
    plain_args = backend._session_args
    # come modulo, come xonos_server: nel pacchetto il codice di Xonos può esserci solo compilato (.pyc)
    backend._session_args = lambda uri: [python, "-m", "xonos_fake_soloist", *plain_args(uri)[1:]]
    # il brano dopo nella coda di Music Assistant: qui lo dice la prova (la coda finta non è in Music Assistant)
    nexts: dict[str, str] = {}
    backend._xonos_next_uri = lambda run: nexts.get(run.spotify_uri)

    def mark(uri: str) -> int:
        """Il valore del brano, come lo scrive il finto Soloist (in milioni)."""
        return sum(uri.encode()) % 900 + 100

    early: set[int] = set()

    async def take(uri: str, name: str, seek: int = 0, seconds: float = 1.0, duration: int = 30) -> tuple:
        """
        Uno stream come lo chiede Music Assistant: secondi al primo audio, i brani dei valori arrivati, byte.

        In early i secondi del brano dei primi 0,3 s di audio. Al massimo 30 s: se la fine di un brano non si riconosce,
        la prova non resta appesa (e le prove dopo partono).
        """
        details = SimpleNamespace(uri=f"spotify--xonos://track/{name}", queue_id="xonos_fake_queue", duration=duration)
        start = time.monotonic()
        first = -1.0
        values: set[int] = set()
        early.clear()
        got = 0
        gen = backend.stream_spotify_uri(uri, seek, streamdetails=details)

        async def read() -> None:
            nonlocal first, got
            async for chunk in gen:
                if first < 0:
                    first = time.monotonic() - start
                samples = array("i", chunk[: len(chunk) // 4 * 4])
                values.update(value // 1_000_000 for value in samples)
                if got < 0.3 * FIFO_BYTES_PER_S:
                    early.update(value % 1_000_000 // 1000 for value in samples)
                got += len(chunk)
                if got >= seconds * FIFO_BYTES_PER_S:
                    break

        try:
            async with asyncio.timeout(30):
                await read()
        finally:
            await gen.aclose()
        return first, values, got

    def alive(run: Any) -> bool:
        return run is not None and run._proc is not None and run._proc.returncode is None

    async def drop() -> None:
        """Spegne il Soloist acceso: il prossimo brano parte con un processo nuovo (e le variabili d'ambiente nuove)."""
        backend._xonos_cancel_park()
        async with backend._run_lock:
            if (left := backend._run) is not None:
                await left.stop()
                backend._run = None

    failed: list[str] = []

    def check(what: str, ok: bool, seen: Any = "") -> None:
        if not ok:
            failed.append(f"{what} ({seen})" if seen != "" else what)

    a, b, c, d, e, g, j, k, m, n, o, p, q, r, s = (f"spotify:track:xonos{x}" for x in "abcdegjkmnopqrs")
    f, h = "spotify:track:xonosshortf", "spotify:track:xonosshorth"
    first: dict[str, float] = {}
    try:
        os.environ["XONOS_FAKE_LEAK"] = "1"
        os.environ["XONOS_FAKE_DURATION_MS"] = "30000"

        # --- un processo per brano (--single-track), come in Music Assistant ---
        solo._XONOS_CONNECT = False
        first["da capo"], values, _ = await take(a, "a", seconds=1.5)
        run = backend._run
        check("audio del primo brano", values == {mark(a)}, values)
        check("in pausa e acceso dopo lo stream", alive(run) and run.xonos_parked)
        first["salto da capo"], values, _ = await take(b, "b", seconds=3.0)
        run_b = backend._run
        check(
            "salto con un processo per brano: da capo",
            run_b is not run and not alive(run) and values == {mark(b)} and early == {0},
            (values, early),
        )
        check("salto da capo senza tentativi", backend._xonos_failures["salto"] == 0, backend._xonos_failures)
        # Soloist in pausa è oltre i 3 s (letti, più il tubo e il resto della pausa): la ripresa a 1 s deve partire
        # da lì, non dal punto in cui Soloist si era fermato
        solo._XONOS_PARK_S = 1.0
        first["ripresa"], values, _ = await take(b, "b", seek=1, seconds=1.0)
        check("ripresa sullo stesso processo", backend._run is run_b, backend._run)
        check("solo audio del brano ripreso", values == {mark(b)}, values)
        check("ripresa dal punto chiesto", early == {1}, early)
        # il resto della pausa è entrato nel tubo (sennò le prove "solo audio del brano..." non dicono niente)
        rests = [r for r in records if "finto soloist: resto " in r]
        check("resto della pausa nel tubo", any(not r.endswith(" 0") for r in rests), rests)
        await asyncio.sleep(2.5)
        check("spento dopo la pausa", backend._run is None and not alive(run_b))
        solo._XONOS_PARK_S = real["_XONOS_PARK_S"]
        _, values, got = await take(f, "f", seconds=10, duration=3)
        await asyncio.sleep(0.5)
        check("fine naturale del brano", got > 0 and values == {mark(f)} and backend._run is None, (got, values))

        # --- modalità Spotify Connect: una sessione sola ---
        solo._XONOS_CONNECT = True
        first["Connect da capo"], values, _ = await take(c, "c", seconds=1.5)
        run = backend._run
        check(
            "Spotify Connect: primo brano",
            run is not None and run._xonos_connect and values == {mark(c)} and early == {0},
            (getattr(run, "_xonos_connect", None), values, early),
        )
        rests_before = len([r for r in records if "finto soloist: resto " in r])
        first["Connect salto"], values, _ = await take(d, "d", seconds=3.0)
        check(
            "Spotify Connect: salto sullo stesso processo",
            backend._run is run and alive(run) and values == {mark(d)} and early == {0},
            (values, early),
        )
        rests = [r for r in records if "finto soloist: resto " in r][rests_before:]
        check("Spotify Connect: resto della pausa nel tubo", any(not r.endswith(" 0") for r in rests), rests)
        first["Connect ripresa"], values, _ = await take(d, "d", seek=1, seconds=1.0)
        check(
            "Spotify Connect: ripresa dal punto chiesto",
            backend._run is run and values == {mark(d)} and early == {1},
            (values, early),
        )
        # fine naturale con l'autoplay: Soloist passa a un altro brano, va in pausa e resta acceso
        _, values, got = await take(h, "h", seconds=10, duration=3)
        await asyncio.sleep(0.5)
        check("Spotify Connect: fine naturale, solo il brano", got > 0 and values == {mark(h)}, (got, values))
        check("Spotify Connect: acceso dopo la fine", backend._run is run and alive(run) and run.xonos_parked)
        first["Connect dopo la fine"], values, _ = await take(e, "e", seconds=1.0)
        check(
            "Spotify Connect: brano dopo sulla stessa sessione",
            backend._run is run and values == {mark(e)} and early == {0},
            (values, early),
        )
        # fine naturale senza autoplay: Soloist si ferma alla fine del brano, in pausa
        await drop()
        os.environ["XONOS_FAKE_AUTOPLAY"] = "0"
        _, values, got = await take(f, "f", seconds=10, duration=3)
        await asyncio.sleep(0.5)
        run = backend._run
        check(
            "Spotify Connect senza autoplay: fine del brano",
            got > 0 and values == {mark(f)} and alive(run) and run.xonos_parked,
            (got, values, run),
        )
        os.environ.pop("XONOS_FAKE_AUTOPLAY")
        # un cambio annullato a metà (il brano chiesto si carica piano): Soloist si spegne, il brano dopo da capo
        await drop()
        os.environ["XONOS_FAKE_DELAY_PLAY"] = "1500"
        await take(g, "g", seconds=1.0)
        run_g = backend._run
        cancelled = asyncio.create_task(take(j, "j", seconds=1.0))
        await asyncio.sleep(0.3)
        cancelled.cancel()
        with suppress(asyncio.CancelledError):
            await cancelled
        for _ in range(40):
            if backend._run is None and not alive(run_g):
                break
            await asyncio.sleep(0.05)
        check("spento dopo un cambio annullato", backend._run is None and not alive(run_g), backend._run)
        os.environ.pop("XONOS_FAKE_DELAY_PLAY")
        # un Soloist che non accetta salti: dopo l'attesa si riparte da capo, e il fallimento si conta
        os.environ["XONOS_FAKE_NO_JUMP"] = "1"
        solo._XONOS_SWITCH_S = 2.0
        solo._JUMP_TIMEOUT_S = 2.0
        await take(k, "k", seconds=1.0)
        run_k = backend._run
        first["da capo dopo un salto rifiutato"], values, _ = await take(m, "m", seconds=1.0)
        check("da capo dopo un salto rifiutato", backend._run is not run_k and values == {mark(m)}, values)
        check("salto rifiutato contato", backend._xonos_failures["salto"] == 1, backend._xonos_failures)
        # due salti falliti di fila: per un po' niente tentativi (si riparte subito da capo)...
        backend._xonos_failures["salto"] = 2
        failed_at = backend._xonos_failed_at["salto"] = solo._xonos_now()
        run_m = backend._run
        first["senza tentativo"], values, _ = await take(n, "n", seconds=1.0)
        check(
            "niente tentativi dopo due salti falliti",
            backend._run is not run_m
            and values == {mark(n)}
            and backend._xonos_retries["salto"] == 0
            and backend._xonos_failed_at["salto"] == failed_at,
            (values, backend._xonos_retries, backend._xonos_failed_at),
        )
        # ... e passato quel tempo si riprova
        solo._XONOS_RETRY_S = 0.0
        first["tentativo dopo un po'"], values, _ = await take(o, "o", seconds=1.0)
        check(
            "salto riprovato dopo un po'",
            values == {mark(o)}
            and first["tentativo dopo un po'"] >= 2.0
            and backend._xonos_failures["salto"] == 2
            and backend._xonos_retries["salto"] == 1,
            (first["tentativo dopo un po'"], backend._xonos_failures, backend._xonos_retries),
        )
        os.environ.pop("XONOS_FAKE_NO_JUMP")
        # i tempi veri dei cambi (le prove qui sopra li accorciano)
        solo._XONOS_SWITCH_S = real["_XONOS_SWITCH_S"]
        solo._JUMP_TIMEOUT_S = real["_JUMP_TIMEOUT_S"]
        # la modalità Spotify Connect che non entra in Spotify: un processo per brano, e per un po' resta così
        await drop()
        os.environ["XONOS_FAKE_CONNECT_FAIL"] = "1"
        solo._XONOS_CONNECT_LOGIN_S = 1.5
        first["ripiego"], values, _ = await take(p, "p", seconds=1.0)
        run = backend._run
        check(
            "Spotify Connect che non parte: un processo per brano",
            values == {mark(p)} and run is not None and not run._xonos_connect,
            (values, getattr(run, "_xonos_connect", None)),
        )
        check(
            "Spotify Connect che non parte: per un po' niente tentativi",
            backend._xonos_connect_blocked_until > solo._xonos_now() and backend._xonos_connect_fails == 1,
            (backend._xonos_connect_blocked_until, backend._xonos_connect_fails),
        )
        await drop()
        _, values, _ = await take(q, "q", seconds=1.0)
        check(
            "dopo il ripiego un processo per brano",
            values == {mark(q)} and not backend._run._xonos_connect,
            values,
        )
        os.environ.pop("XONOS_FAKE_CONNECT_FAIL")
        await _preload_cases(backend, solo, take, nexts, records, check, mark, first, lambda: early)
        await _cold_cases(backend, solo, take, real, records, check, mark, first, drop)
        # un altro account Spotify entra in Soloist dalla rete locale (modalità Spotify Connect): il brano dopo non
        # suona su quell'account, si chiede di rifare l'abbinamento. Per ultima: dopo, questo Soloist non si usa più.
        await drop()
        backend._xonos_connect_blocked_until = 0.0
        os.environ["XONOS_FAKE_TAKEOVER"] = "1"
        _, values, _ = await take(r, "r", seconds=1.0)
        check("prima della presa", values == {mark(r)}, values)
        refused = ""
        try:
            await take(s, "s", seconds=1.0)
        except Exception as err:  # noqa: BLE001 - è quello che si aspetta
            refused = repr(err)
        check("un altro account: niente brano, abbinamento da rifare", "LoginFailed" in refused, refused or "suonato")
    except Exception as err:  # noqa: BLE001 - nel risultato, con le ultime righe del motore
        failed.append(repr(err))
    finally:
        log.removeHandler(keep)
        for key in (
            "XONOS_FAKE_LEAK",
            "XONOS_FAKE_DURATION_MS",
            "XONOS_FAKE_NO_JUMP",
            "XONOS_FAKE_DELAY_PLAY",
            "XONOS_FAKE_AUTOPLAY",
            "XONOS_FAKE_CONNECT_FAIL",
            "XONOS_FAKE_TAKEOVER",
            "XONOS_FAKE_COLD_MS",
            "XONOS_FAKE_STUCK",
            "XONOS_FAKE_SINGLE_FAIL",
        ):
            os.environ.pop(key, None)
        for name, value in real.items():
            setattr(solo, name, value)
        await drop()
    detail = "primo audio: " + ", ".join(f"{what} {seconds:.2f} s" for what, seconds in first.items())
    if failed:
        detail += "; non va: " + ", ".join(failed) + " | motore: " + " / ".join(records[-15:])
    return not failed, detail


async def _cold_cases(
    backend: Any,
    solo: Any,
    take: Any,
    real: dict[str, Any],
    records: list[str],
    check: Any,
    mark: Any,
    first: dict[str, float],
    drop: Any,
) -> None:
    """
    soloist-sessione, il primo brano di un Soloist appena partito in modalità Spotify Connect, coi tempi veri di Xonos.

    Il Soloist vero dice il brano chiesto solo quando ne ha l'audio: a freddo, sul telefono dell'utente, 5,2-5,5 s dopo
    l'accesso (registro della 0.1.56). Con i 5 s dei salti l'avvio non riusciva e la modalità Spotify Connect si spegneva
    per 30 minuti. Il finto fa lo stesso con XONOS_FAKE_COLD_MS, e con XONOS_FAKE_STUCK un primo brano che non parte mai.
    """
    for name in ("_XONOS_SWITCH_S", "_JUMP_TIMEOUT_S", "_XONOS_CONNECT_LOGIN_S", "_XONOS_CONNECT_START_S"):
        setattr(solo, name, real[name])

    def fresh() -> None:
        backend._xonos_connect_blocked_until = 0.0
        backend._xonos_connect_fails = 0
        backend._xonos_failures = {"salto": 0, "ripresa": 0}
        backend._xonos_retries = {"salto": 0, "ripresa": 0}
        backend._xonos_unconfirmed = None

    one, two, three, four = (f"spotify:track:xonoscold{x}" for x in "1234")

    def at(tail: list[str], text: str) -> int:
        return next((i for i, line in enumerate(tail) if text in line), -1)

    try:
        await drop()
        fresh()
        os.environ["XONOS_FAKE_COLD_MS"] = "5500"
        first["Connect da capo a freddo"], values, _ = await take(one, "cold1", seconds=1.0)
        run = backend._run
        check(
            "a freddo: resta in modalità Spotify Connect",
            values == {mark(one)}
            and run is not None
            and run._xonos_connect
            and backend._xonos_connect_blocked_until <= solo._xonos_now()
            and backend._xonos_connect_fails == 0,
            (values, getattr(run, "_xonos_connect", None), backend._xonos_connect_fails),
        )
        check(
            "a freddo: il primo audio dopo il caricamento",
            5.4 <= first["Connect da capo a freddo"] <= 8.5,
            first["Connect da capo a freddo"],
        )
        os.environ.pop("XONOS_FAKE_COLD_MS")
        first["Connect salto dopo un avvio a freddo"], values, _ = await take(two, "cold2", seconds=1.0)
        check(
            "dopo l'avvio a freddo il salto sulla stessa sessione",
            values == {mark(two)}
            and backend._run is run
            and backend._xonos_failures["salto"] == 0
            and first["Connect salto dopo un avvio a freddo"] < 1.5,
            (values, backend._run is run, backend._xonos_failures, first["Connect salto dopo un avvio a freddo"]),
        )
        await drop()
        fresh()
        os.environ["XONOS_FAKE_STUCK"] = "1"
        count = len(records)
        first["Connect che non parte, ripiego"], values, _ = await take(three, "cold3", seconds=1.0)
        tail = records[count:]
        run = backend._run
        check(
            "primo brano che non parte: un processo per brano",
            values == {mark(three)} and run is not None and not run._xonos_connect,
            (values, getattr(run, "_xonos_connect", None)),
        )
        blocked, fallback = at(tail, "minuti un processo per brano"), at(tail, "provo un processo per brano")
        check(
            "primo brano che non parte: modalità Spotify Connect ferma prima del ripiego",
            backend._xonos_connect_blocked_until > solo._xonos_now()
            and backend._xonos_connect_fails == 1
            and 0 <= blocked < fallback,
            (backend._xonos_connect_blocked_until, backend._xonos_connect_fails, blocked, fallback, tail[-6:]),
        )
        check(
            "primo brano che non parte: il ripiego (finto, parte subito) dopo gli 8 s di attesa",
            8.0 <= first["Connect che non parte, ripiego"] < 13.0,
            first["Connect che non parte, ripiego"],
        )
        # e se non parte nemmeno il processo per brano (brano non disponibile, niente rete...): non è colpa della modalità
        # Spotify Connect, e il blocco messo prima del ripiego si toglie
        await drop()
        fresh()
        os.environ["XONOS_FAKE_SINGLE_FAIL"] = "1"
        count = len(records)
        refused = ""
        try:
            await take(four, "cold4", seconds=1.0)
        except Exception as err:  # noqa: BLE001 - è quello che si aspetta
            refused = repr(err)
        tail = records[count:]
        check(
            "non parte nemmeno un processo per brano: la modalità Spotify Connect resta accesa",
            bool(refused)
            and backend._xonos_connect_blocked_until <= solo._xonos_now()
            and backend._xonos_connect_fails == 0
            and at(tail, "la modalità Spotify Connect resta accesa") >= 0,
            (refused or "suonato", backend._xonos_connect_blocked_until, backend._xonos_connect_fails, tail[-6:]),
        )
    finally:
        os.environ.pop("XONOS_FAKE_COLD_MS", None)
        os.environ.pop("XONOS_FAKE_STUCK", None)
        os.environ.pop("XONOS_FAKE_SINGLE_FAIL", None)
        await drop()
        fresh()


async def _preload_cases(
    backend: Any, solo: Any, take: Any, nexts: dict[str, str], records: list[str], check: Any, mark: Any,
    first: dict[str, float], early: Any,
) -> None:
    """
    soloist-sessione, il brano dopo nella coda di Soloist (modalità Spotify Connect): vedi _soloist_session.

    Il play di un brano non in coda aspetta 600 ms (XONOS_FAKE_DELAY_PLAY), un brano in coda da almeno 0,5 s parte con
    skip_next in 50 ms: i tempi del primo audio ("Connect avanti in coda" e "Connect salto lento") mostrano il guadagno.
    """

    def since(count: int, text: str) -> bool:
        return any(text in line for line in records[count:])

    async with backend._run_lock:
        if (left := backend._run) is not None:
            await left.stop()
            backend._run = None
    backend._xonos_cancel_park()
    # da capo: le prove di prima lasciano salti falliti e la modalità Spotify Connect bloccata
    backend._xonos_connect_blocked_until = 0.0
    backend._xonos_failures = {"salto": 0, "ripresa": 0}
    backend._xonos_retries = {"salto": 0, "ripresa": 0}
    os.environ["XONOS_FAKE_DELAY_PLAY"] = "600"
    solo._XONOS_PRELOAD_AFTER_S = 0.3
    one, two, three, four, six = (f"spotify:track:xonospre{x}" for x in "12346")
    five, seven = "spotify:track:xonosshortpre5", "spotify:track:xonosshortpre7"
    nexts.update({one: two, two: three, five: six, seven: one})
    try:
        # un brano, e dopo poco il suo dopo va nella coda di Soloist
        first["Connect lento da capo"], values, _ = await take(one, "pre1", seconds=3.0)
        run = backend._run
        check("precarico: primo brano", run is not None and run._xonos_connect and values == {mark(one)}, values)
        check("precarico: il dopo in coda", backend._xonos_queued_uri == two, backend._xonos_queued_uri)
        # avanti verso il brano in coda: skip_next, e Soloist l'ha già caricato
        count = len(records)
        first["Connect avanti in coda"], values, _ = await take(two, "pre2", seconds=3.0)
        check(
            "precarico: avanti col brano in coda",
            backend._run is run and values == {mark(two)} and early() == {0},
            (values, early()),
        )
        check(
            "precarico: skip_next sul brano già caricato",
            since(count, "comando skip_next") and since(count, "già caricato")
            and since(count, "Soloist: avanti col brano in coda (precaricato)")
            and not since(count, "comando play spotify:track:xonospre2"),
            records[count:][-6:],
        )
        check("precarico: riga dentro l'avanti", since(count, "Soloist: dentro l'avanti col brano in coda"))
        # un salto a un brano che non è in coda: play (lento), e il brano in coda (pre3) resta lì
        count = len(records)
        first["Connect salto lento"], values, _ = await take(four, "pre4", seconds=1.0)
        check(
            "precarico: salto a un brano non in coda con play",
            values == {mark(four)} and early() == {0} and not since(count, "comando skip_next"),
            (values, early()),
        )
        check(
            "precarico: avanti in coda più veloce del play",
            first["Connect avanti in coda"] + 0.3 < first["Connect salto lento"],
            (first["Connect avanti in coda"], first["Connect salto lento"]),
        )
        # il brano rimasto in coda: niente precarico, e a fine brano Soloist ci passa; non se ne sente niente, e il brano
        # dopo di Music Assistant parte con play
        count = len(records)
        _, values, got = await take(five, "pre5", seconds=10, duration=3)
        await asyncio.sleep(0.3)
        check("precarico: brano rimasto in coda, fine del brano", got > 0 and values == {mark(five)}, (got, values))
        check("precarico: brano rimasto in coda nel registro", since(count, "c'è già un brano che non è il dopo"))
        _, values, _ = await take(six, "pre6", seconds=1.0)
        check(
            "precarico: dopo il brano rimasto, il brano dopo con play",
            values == {mark(six)} and early() == {0} and since(count, "comando play spotify:track:xonospre6"),
            (values, early()),
        )
        # a fine brano Soloist passa al brano in coda: si riprende da lì (seek a 0), senza play
        count = len(records)
        _, values, got = await take(seven, "pre7", seconds=10, duration=3)
        await asyncio.sleep(0.3)
        check("precarico: fine del brano verso la coda", got > 0 and values == {mark(seven)}, (got, values))
        first["Connect brano dopo pronto"], values, _ = await take(one, "pre1", seconds=1.0)
        check(
            "precarico: brano dopo già pronto",
            values == {mark(one)} and early() == {0} and since(count, "brano dopo già pronto in Soloist")
            and not since(count, "comando play spotify:track:xonospre1"),
            (values, early(), records[count:][-6:]),
        )
        # lo stesso brano anche nella coda di Soloist (messo lì non da Xonos), suonato con play: alla sua fine Soloist
        # ricomincia la copia in coda, con lo stesso uri. Il brano deve finire lì, non dopo altri 5 s del suo inizio.
        eight = "spotify:track:xonosshortpre8"
        run = backend._run
        if run is not None and run._client is not None:
            await run._client.add_to_queue(eight, await_result=True)
        count = len(records)
        _, values, got = await take(eight, "pre8", seconds=10, duration=3)
        check(
            "precarico: lo stesso brano in coda non si ripete alla fine",
            values == {mark(eight)} and 0 < got <= 4.5 * FIFO_BYTES_PER_S,
            (round(got / FIFO_BYTES_PER_S, 2), values, records[count:][-4:]),
        )
        # lo stesso con un Soloist che dice la posizione di rado e passa alla copia senza track_changed (brani "sparse",
        # 16 s): la fine si riconosce dall'audio letto
        nine = "spotify:track:xonossparsepre9"
        run = backend._run
        if run is not None and run._client is not None:
            await run._client.add_to_queue(nine, await_result=True)
        count = len(records)
        _, values, got = await take(nine, "pre9", seconds=40, duration=16)
        check(
            "precarico: posizioni rare, lo stesso brano in coda non si ripete alla fine",
            values == {mark(nine)} and 15 * FIFO_BYTES_PER_S <= got <= 17.5 * FIFO_BYTES_PER_S
            and since(count, "over at its end") and since(count, "senza track_changed"),
            (round(got / FIFO_BYTES_PER_S, 2), values, records[count:][-4:]),
        )
    finally:
        os.environ.pop("XONOS_FAKE_DELAY_PLAY", None)
        nexts.clear()


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
