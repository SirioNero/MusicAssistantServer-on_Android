package it.xonos.ma.server

/**
 * Cosa dell'uscita del server sul telefono va nel logcat (tag XonosServer), una riga alla volta. Il registro di Music
 * Assistant ha titoli, uri, nomi di utenti e di servizi, indirizzi: sta in musicassistant.log, che la persona condivide
 * a mano, e nel logcat non deve arrivare.
 *
 * - I pacchetti dal 2.10.8-10 scrivono su stderr solo le righe di xonos_server.py (il logger "xonos"), una per record:
 *   "XONOS_LOG <livello> <testo>". Passano senza il prefisso.
 * - Quelli di prima ci scrivevano tutto il registro, nel formato LOG_FORMAT di xonos_server.py
 *   ("2026-11-03 04:58:05,637.637 INFO (thread) [logger] testo"). Un record e le righe che lo seguono (tracce, testi su
 *   più righe) restano fuori. Dei record del logger "xonos" passano livello e prima riga; di avvisi ed errori degli
 *   altri solo il livello e la prima parte del nome del logger, senza il testo.
 * - Il resto (Python o il linker che non partono, prima che cominci il registro; un programma che scrive qui per conto
 *   suo, come ffmpeg) passa, accorciato e con "<url>" al posto degli indirizzi. Sono anche le sole righe che restano
 *   per il messaggio d'errore ([last]).
 *
 * Le righe XONOS_STATE e XONOS_MULTICAST le guarda prima PhoneServer. Senza Android: il banco di prova
 * (ServerLinesCheck) la prova con un'uscita finta.
 */
class ServerLines(private val keep: Int = LAST_LINES) {

    /** Vero dopo un record del registro: le righe senza intestazione che seguono sono sue. */
    private var inRecord = false
    private val raw = ArrayDeque<String>()

    /** Le ultime righe fuori dal registro di Music Assistant, per dire perché il server si è fermato. */
    val last: List<String> get() = raw.toList()

    /** La riga [line] dell'uscita del server: cosa scriverne nel logcat, o null se niente. */
    fun see(line: String): String? {
        if (line.startsWith(LOG_PREFIX)) {
            // Una riga sola per record: dopo, una riga senza intestazione non è il seguito di un record.
            inRecord = false
            return line.removePrefix(LOG_PREFIX).trim().take(MAX).ifEmpty { null }
        }
        val record = RECORD.find(line)
        if (record == null) {
            if (inRecord || line.isBlank()) return null
            // Gli indirizzi via: un programma che scrive qui per conto suo (ffmpeg: "Error opening input file
            // https://utente:password@...") metterebbe nel logcat nome utente, password e il percorso del brano.
            val short = line.replace(URL, "<url>").take(MAX)
            raw.addLast(short)
            while (raw.size > keep) raw.removeFirst()
            return short
        }
        inRecord = true
        val (level, logger, message) = record.destructured
        return when {
            logger == OURS || logger.startsWith("$OURS.") -> "$level $message".trim().take(MAX)
            level in LOUD -> "$level di ${SOURCE.find(logger)?.value ?: "?"} (il testo è in musicassistant.log)"
            else -> null
        }
    }

    companion object {
        /** Il prefisso delle righe di xonos_server.py (LOG_PREFIX lì). */
        const val LOG_PREFIX = "XONOS_LOG"

        /** Quanto di una riga va nel logcat. */
        const val MAX = 300

        /** Quante righe fuori dal registro si tengono per il messaggio d'errore. */
        const val LAST_LINES = 20

        /**
         * L'intestazione di un record di LOG_FORMAT ("%(asctime)s.%(msecs)03d %(levelname)s (%(threadName)s) [%(name)s]
         * %(message)s"), anche in mezzo a una riga (un'altra scritta senza a capo). Il livello può essere "Level 5"; il
         * nome del thread può avere parentesi ("Thread-3 (worker)"), quello del logger anche spazi e parentesi.
         */
        private val RECORD = Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\S* (\S+(?: \d+)?) \(.*?\) \[([^\]]*)\] ?(.*)$""")

        /**
         * La prima parte del nome di un logger ("music_assistant", "aiosendspin", "py"): un nome di modulo, in minuscolo.
         * Il resto può avere il nome di un servizio scelto dalla persona ("music_assistant.Spotify (mario.rossi)").
         */
        private val SOURCE = Regex("""^[a-z_][a-z0-9_]*(?=\.|$)""")
        private val LOUD = setOf("WARNING", "ERROR", "CRITICAL")
        private const val OURS = "xonos"

        /** Un indirizzo con il suo schema (https://, rtsp://...), fino al primo spazio. */
        private val URL = Regex("""[a-z][a-z0-9+.-]*://\S+""", RegexOption.IGNORE_CASE)
    }
}
