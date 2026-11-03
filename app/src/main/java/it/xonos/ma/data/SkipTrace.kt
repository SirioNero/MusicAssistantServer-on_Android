package it.xonos.ma.data

/**
 * L'orologio di un salto, per il registro: si azzera al tocco (avanti, indietro, un brano della coda) e ogni passo
 * dopo (il comando a Music Assistant, la sua risposta, Sendspin, l'uscita audio, la musica che esce) scrive quanti ms
 * sono passati. Così dal registro di una persona si vede dove va il tempo tra un brano e l'altro.
 *
 * La musica "fuori" è quella scritta sull'uscita; si sente più tardi, dopo il buffer e il resto della strada
 * (altoparlante, Bluetooth): quando, lo dice [heard], con i tempi dell'uscita.
 *
 * Senza Android: si prova sulla JVM. Si legge da più thread (principale, rete, audio); tocco e musica fuori arrivano
 * dal thread principale.
 */
object SkipTrace {
    /** Oltre questo un evento non c'entra più con il tocco. */
    const val WINDOW_MS = 30_000L

    /**
     * Dopo la musica fuori il salto resta aperto ancora un po': l'uscita audio può dire i suoi tempi veri qualche
     * centinaio di ms dopo (il Bluetooth). Poi gli eventi (una pausa, un play) non sono più del salto.
     */
    const val AFTER_OUT_MS = 5_000L

    /** In millisecondi, solo per misurare intervalli; nelle prove si può cambiare. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Il tocco, e dopo la musica fuori fin quando vale (in un oggetto solo: chi legge da un altro thread li vede insieme). */
    private class Mark(val at: Long, val endsAt: Long?)

    @Volatile
    private var mark: Mark? = null

    /** Un tocco: da qui si conta. */
    fun touch() {
        mark = Mark(clock(), null)
    }

    /** La musica è uscita: i ms dal tocco, una volta sola per tocco (null senza un salto in corso). */
    fun musicOut(): Long? {
        val current = mark ?: return null
        if (current.endsAt != null) return null
        val ms = sinceTouch() ?: return null
        mark = Mark(current.at, clock() + AFTER_OUT_MS)
        return ms
    }

    /** I ms dall'ultimo tocco, se il salto è in corso e il tocco è di meno di [WINDOW_MS] fa; altrimenti null. */
    fun sinceTouch(): Long? {
        val current = mark ?: return null
        val now = clock()
        if (current.endsAt != null && now > current.endsAt) return null
        return (now - current.at).takeIf { it in 0..WINDOW_MS }
    }

    /**
     * La musica del salto si sente fra [inMs] ms (meno di zero: si sente già da tanto): i ms dal tocco a quel momento,
     * per la riga "musica udibile"; null senza un salto in corso.
     */
    fun heard(inMs: Long): Long? = sinceTouch()?.let { it + inMs }

    /** ", N ms dal tocco" per le righe del registro, o niente senza un salto in corso. */
    fun suffix(): String = sinceTouch()?.let { ", $it ms dal tocco" }.orEmpty()
}
