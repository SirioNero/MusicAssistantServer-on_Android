package it.xonos.ma.playback

/**
 * Avanti e indietro: dove deve andare la coda di Music Assistant. Senza Media3, così si prova sulla JVM.
 *
 * Media3 calcola il brano dopo e quello prima sulla finestra della coda che [MaPlayer] gli mostra (qualche brano
 * prima di quello in corso e una cinquantina dopo), non sulla coda intera:
 * - alla fine della finestra, o con la finestra ridotta al solo brano in corso (subito dopo un casuale o una coda
 *   nuova), avanti arriva senza indice (C.INDEX_UNSET), e indietro arriva come "il brano in corso da 0";
 * - con ripeti tutto torna a capo della finestra: avanti dall'ultimo brano chiede il primo della finestra, che è
 *   qualche brano prima di quello in corso, e indietro dal primo chiede l'ultimo.
 *
 * player_queues/next e previous di Music Assistant aspettano 1 s prima di far partire il brano (un rimbalzo contro i
 * tocchi ripetuti: call_later(1, play_index) in MA 2.10.8). Quando il brano è noto si salta direttamente
 * (play_index, dal giro di [it.xonos.ma.data.SkipSender]); next e previous restano per quello che sa solo Music
 * Assistant: ripeti tutto e ripeti uno, la fine della coda, "don't stop the music".
 */
object SkipPlan {

    sealed interface Step

    /** Al brano [index] della coda, dal giro dei salti (play_index). */
    data class Jump(val index: Int) : Step

    /** player_queues/next: il brano dopo lo decide Music Assistant. */
    data object Next : Step

    /** player_queues/previous: il brano prima lo decide Music Assistant. */
    data object Previous : Step

    /** Il brano in corso da capo. */
    data object Restart : Step

    /** Niente: avanti dall'ultimo brano della coda mentre un salto è ancora in viaggio. */
    data object Stay : Step

    /**
     * Quello che si sa al momento del tocco.
     *
     * @param shown il brano che Media3 mostra, come indice della finestra: durante un salto è già quello chiesto,
     *   anche se Music Assistant è ancora sul brano di prima (con Soloist il brano nuovo ci mette qualche secondo)
     * @param start dove comincia la finestra nella coda
     * @param size quanti brani ha la coda
     * @param current il brano in corso per Music Assistant (null: niente in corso)
     * @param repeat "off", "one" o "all"
     * @param skipping un salto mandato (o in raccolta) a cui Music Assistant non è ancora arrivato
     */
    data class Queue(
        val shown: Int,
        val start: Int,
        val size: Int,
        val current: Int?,
        val repeat: String,
        val skipping: Boolean,
    ) {
        /** Il brano mostrato, come indice della coda. */
        val showing: Int get() = start + shown

        /** Music Assistant è sul brano mostrato e nessun salto è in viaggio: next e previous partono da lì. */
        val settled: Boolean get() = !skipping && current == showing
    }

    /** Avanti. [requested] è l'indice della finestra che Media3 chiede (null: nessuno). */
    fun next(requested: Int?, q: Queue): Step {
        if (q.current == null) return Next
        if (requested != null && requested > q.shown) return Jump(q.start + requested)
        // Senza indice, o il ritorno a capo della finestra con ripeti tutto.
        val next = q.showing + 1
        if (!q.settled) {
            // next partirebbe dal brano dove Music Assistant è ancora: si fa come farebbe lui da quello mostrato
            // (queue_loader._get_next_index con is_skip in MA 2.10.8: ripeti uno non conta, alla fine ripeti tutto
            // torna al primo), e il salto resta nello stesso giro di quello in viaggio.
            return when {
                next < q.size -> Jump(next)
                q.repeat == "all" -> Jump(0)
                else -> Stay
            }
        }
        if (requested == null && q.repeat == "off" && next < q.size) return Jump(next)
        return Next
    }

    /**
     * Indietro. Media3 chiede il brano prima ([requested], con la posizione di partenza: [defaultPosition]), il ritorno
     * a capo della finestra con ripeti tutto, o il brano in corso dalla posizione 0 per ricominciarlo: dopo i primi
     * secondi, o perché la finestra non ha il brano prima. [nearStart]: si è nei primi secondi del brano
     * (maxSeekToPreviousPosition di Media3, 3 s), quando indietro vuole il brano prima.
     */
    fun previous(requested: Int?, defaultPosition: Boolean, nearStart: Boolean, q: Queue): Step {
        if (q.current == null) return Previous
        if (requested != null && requested < q.shown) return Jump(q.start + requested)
        val wanted = when {
            // Il brano prima, che la finestra non ha (seekToPreviousMediaItem).
            requested == null -> true
            // Ripeti tutto: dal primo della finestra all'ultimo (o allo stesso, con la finestra di un brano solo).
            requested > q.shown || defaultPosition -> true
            // Da capo: Media3 non ha il brano prima nella finestra ridotta, ma la coda sì.
            else -> q.shown == 0 && nearStart && q.showing > 0
        }
        if (!wanted) return if (q.settled) Restart else Jump(q.showing)
        val previous = q.showing - 1
        // Durante un salto previous partirebbe dal brano dove Music Assistant è ancora.
        if (!q.settled) return Jump(maxOf(previous, 0))
        if (q.repeat == "off" && previous >= 0) return Jump(previous)
        return Previous
    }
}
