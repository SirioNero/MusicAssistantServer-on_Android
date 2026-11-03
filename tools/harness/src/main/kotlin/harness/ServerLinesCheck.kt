package harness

import it.xonos.ma.server.ServerLines

/**
 * Cosa dell'uscita del server sul telefono arriva nel logcat ([ServerLines]): un'uscita finta con le righe dei
 * pacchetti nuovi (XONOS_LOG), quelle dei pacchetti di prima (tutto il registro di Music Assistant, nel formato
 * LOG_FORMAT di xonos_server.py), quello che Python scrive prima del registro e un errore di ffmpeg con un indirizzo. Le
 * parole piantate nel registro non devono arrivare al logcat; le righe fuori dal registro sì (senza indirizzi), e solo
 * loro restano per il messaggio d'errore. Senza server.
 */
private val PLANTED = listOf(
    "Segreto Album", "Canzone Privata", "SECRET", "mario", "4uLU", "Pixel di Mario", "Rossi", "jf.example", "Piazza",
    "Pa55wd", "nas.casa", "x.flac",
)

private val STREAM = """
Fatal: linker non trovato per libpython3.13.so
Sistema Linux per Soloist non pronto: [Errno 28] No space left on device
XONOS_LOG INFO Music Assistant pronto sul telefono
XONOS_LOG WARNING Processore dei programmi Linux negli ultimi 60 s: soloist 16%, proot[soloist] 14%
Error opening input file https://mario:Pa55wd@nas.casa/Music/Rossi/x.flac.
2026-11-03 07:09:18,493.493 INFO (MainThread) [xonos] Music Assistant pronto sul telefono
2026-11-03 07:09:18,493.493 INFO (MainThread) [music_assistant.player_queues] Fetching tracks to play for album Segreto Album
2026-11-03 07:09:18,494.494 INFO (MainThread) [music_assistant.streams] Error while streaming:
Input #0, mp3, from 'https://jf.example/stream?api_key=SECRET':
  Metadata:
    title           : Canzone Privata
    artist          : Mario Rossi
2026-11-03 07:09:18,495.495 WARNING (MainThread) [music_assistant.Spotify (mario.rossi)] Playback was authorized for mario instead of x
2026-11-03 07:09:18,496.496 ERROR (MainThread) [music_assistant.player_queues] Error in play_media
Traceback (most recent call last):
  File "/data/user/0/it.xonos.ma/no_backup/music-assistant-server/2.10.8-9/app/music_assistant/x.py", line 21, in play
    raise ValueError("track spotify://track/4uLU not found")
ValueError: track spotify://track/4uLU not found

2026-11-03 07:09:18,502.502 WARNING (Thread-3 (worker)) [aiosendspin.server] non-compliant client Pixel di Mario: connected unencrypted (transition mode)
2026-11-03 07:09:18,503.503 Level 5 (MainThread) [music_assistant.ffmpeg] started with args: ffmpeg -i https://x/?token=SECRET
2026-11-03 07:09:18,503.503 VERBOSE (MainThread) [music_assistant.ffmpeg] Piazza Grande
2026-11-03 07:09:18,504.504 INFO (MainThread) [xonos] Processore dei programmi Linux negli ultimi 60 s: soloist 16%, proot[soloist] 14%
2026-11-03 07:09:18,505.505 WARNING (MainThread) [py.warnings] /data/user/0/it.xonos.ma/x.py:31: UserWarning: deprecated for user mario
  warnings.warn("deprecated for user mario")
2026-11-03 07:09:18,506.506 CRITICAL (MainThread) [Rossi] un logger senza punto
Una riga dopo un record è sua: resta fuori (Rossi)
riga a cui si è attaccato un record 2026-11-03 07:09:18,507.507 INFO (MainThread) [music_assistant] Segreto Album
XONOS_LOG ERROR Music Assistant non risponde
Python: fine del programma (codice 1)
""".trimStart('\n').lines()

fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    val lines = ServerLines()
    val out = STREAM.mapNotNull { line -> lines.see(line) }
    println("Nel logcat:")
    out.forEach { println("   $it") }
    val leaks = out.filter { line -> PLANTED.any { line.contains(it, ignoreCase = true) } }
    check("Nessuna parola del registro di Music Assistant nel logcat", leaks.isEmpty(), leaks.joinToString(" | "))

    // L'errore di ffmpeg (fuori dal registro) passa, ma senza l'indirizzo con nome utente, password e percorso.
    val ffmpeg = "Error opening input file <url>"
    val raw = STREAM.take(2) + ffmpeg + STREAM.last { it.isNotBlank() }
    check("Le righe fuori dal registro passano (anche dopo una riga XONOS_LOG)", out.take(2) == STREAM.take(2) && out.last() == raw.last())
    check("Gli indirizzi delle righe fuori dal registro diventano <url>", ffmpeg in out, out.filter { it.startsWith("Error") }.joinToString(" | "))
    check(
        "Anche con lo schema in maiuscolo e in mezzo alla riga",
        ServerLines().see("ffmpeg: HTTP://u:p@h/a b rtsp://cam/x") == "ffmpeg: <url> b <url>",
    )
    check("Le righe XONOS_LOG passano senza il prefisso", "INFO Music Assistant pronto sul telefono" in out && "ERROR Music Assistant non risponde" in out)
    check("Una riga XONOS_LOG vuota non scrive niente", ServerLines().see("XONOS_LOG ") == null && out.none { it.isBlank() })
    check(
        "Le righe del logger xonos dei pacchetti di prima: livello e testo",
        out.count { it == "INFO Music Assistant pronto sul telefono" } == 2 &&
            "INFO Processore dei programmi Linux negli ultimi 60 s: soloist 16%, proot[soloist] 14%" in out,
    )
    val markers = out.filter { it.endsWith("(il testo è in musicassistant.log)") }
    check(
        "Avvisi ed errori di Music Assistant: solo livello e provenienza",
        markers == listOf(
            "WARNING di music_assistant (il testo è in musicassistant.log)",
            "ERROR di music_assistant (il testo è in musicassistant.log)",
            "WARNING di aiosendspin (il testo è in musicassistant.log)",
            "WARNING di py (il testo è in musicassistant.log)",
            "CRITICAL di ? (il testo è in musicassistant.log)",
        ),
        markers.joinToString(" | "),
    )
    check("Le righe INFO, VERBOSE e Level 5 di Music Assistant restano fuori", out.none { it.startsWith("Level") || it.startsWith("VERBOSE") })
    check(
        "Per il messaggio d'errore solo le righe fuori dal registro",
        lines.last == raw,
        lines.last.joinToString(" | "),
    )

    val long = "x".repeat(1_000)
    val short = ServerLines().see(long)
    check("Una riga lunga si accorcia", short?.length == ServerLines.MAX)
    val keep = ServerLines(keep = 3)
    (1..5).forEach { keep.see("riga $it") }
    check("Per il messaggio d'errore solo le ultime righe", keep.last == listOf("riga 3", "riga 4", "riga 5"), keep.last.joinToString())

    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(if (failures == 0) 0 else 1)
}
