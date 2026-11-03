package it.xonos.ma.data

/**
 * Cosa può finire nel logcat. Il registro di Xonos si condivide ("Condividi il registro del server sul telefono"),
 * quindi niente dati personali: titoli, artisti, album, uri e id dei servizi, parole cercate, nomi di utenti, lettori,
 * server e servizi, indirizzi, token, e nemmeno i testi d'errore del server o della rete, che li possono contenere.
 * I dettagli solo nella build di prova ([details], acceso da XonosApp con BuildConfig.DEBUG), come fa myCar.
 */
object Logged {
    /** Vero solo nella build di prova: allora si scrive tutto. */
    @Volatile
    var details = false

    /** Il tipo di un errore, senza il suo testo: il problema e il codice di Music Assistant, o la classe. */
    fun error(e: Throwable): String {
        val kind = (e as? MaException)?.let { ma ->
            listOfNotNull(ma.problem.name, ma.code?.takeIf { it != 0 }?.toString(), ma.key).joinToString(" ")
        } ?: e.javaClass.simpleName
        return if (details) "$kind: ${e.message}" else kind
    }

    /**
     * Un id del menu o di un elemento: solo la sua forma (la parte prima di `|`) e il tipo dell'uri, se c'è.
     * "play|spotify://track/x" diventa "play track", "find|tracks|vasco" diventa "find".
     */
    fun id(id: String?): String {
        val value = id.orEmpty()
        if (details) return value
        val head = value.substringBefore('|').ifEmpty { "(vuoto)" }
        val type = TYPE.find(value)?.groupValues?.get(1)
        return if (type != null) "$head $type" else head
    }

    /** [full] nella build di prova, [short] nelle altre. */
    fun text(full: String?, short: String): String = if (details) full ?: short else short

    /** Un errore da passare a Log.w/e: nelle build vere con le sole classi e la pila, senza i testi. */
    fun trace(e: Throwable): Throwable = if (details) e else Scrubbed(e, 0)

    private class Scrubbed(e: Throwable, depth: Int) :
        Throwable(e.javaClass.name, e.cause?.takeIf { depth < 8 && it !== e }?.let { Scrubbed(it, depth + 1) }) {
        init {
            stackTrace = e.stackTrace
        }

        override fun fillInStackTrace(): Throwable = this

        override fun toString(): String = message.orEmpty()
    }

    /** Solo i tipi di Music Assistant: una ricerca come "http://mario/" non deve finire nel registro. */
    private val TYPE = Regex(
        """://(track|album|artist|playlist|radio|podcast|podcast_episode|audiobook|genre|folder)/"""
    )
}
