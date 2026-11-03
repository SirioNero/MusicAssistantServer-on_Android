package harness

import it.xonos.ma.phone.model.ServerKeeper
import it.xonos.ma.phone.model.ServerKeeper.Need
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

/**
 * [ServerKeeper] senza Android, con un tempo finto: il server sul telefono parte quando serve e si ferma quando tutti
 * i bisogni sono scaduti.
 *
 * - tenere un bisogno lo avvia; lasciarlo lo ferma dopo la sua attesa, non prima; tenerlo di nuovo annulla l'arresto;
 * - ogni bisogno ha la sua scadenza (schermo lasciato con 3 minuti e musica con 0: si ferma dopo i 3 minuti);
 * - lasciare un bisogno mai tenuto non fa niente; dopo l'app tolta dai recenti l'attesa si accorcia;
 * - un bisogno che arriva mentre il server si ferma lo fa ripartire, anche se lo stato dice ancora "acceso";
 * - se Xonos non usa il server sul telefono, non parte mai.
 */
fun main() = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    /** Il tempo finto: le attese finiscono solo quando la prova lo fa andare avanti. */
    class Time {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        private val waiting = mutableListOf<Pair<Long, CancellableContinuation<Unit>>>()

        suspend fun sleep(ms: Long) {
            sleeps += ms
            suspendCancellableCoroutine { c ->
                val entry = (now + ms) to c
                waiting += entry
                c.invokeOnCancellation { waiting.remove(entry) }
            }
        }

        suspend fun settle() = repeat(50) { yield() }

        /** Avanti di [ms], svegliando in ordine le attese che scadono. */
        suspend fun advance(ms: Long) {
            settle()
            val target = now + ms
            while (true) {
                val next = waiting.minOfOrNull { it.first }?.takeIf { it <= target } ?: break
                now = maxOf(now, next)
                val due = waiting.filter { it.first <= now }
                waiting.removeAll(due)
                due.forEach { it.second.resume(Unit) }
                settle()
            }
            now = target
            settle()
        }
    }

    /** Un server finto: [alive] è il processo, [state] quello che direbbe lo stato (in ritardo dopo l'arresto). */
    class Server(val time: Time, val stopMs: Long = 0L) {
        var alive = false
        var state = "STOPPED"
        var starts = 0
        var stops = 0
        val events = mutableListOf<String>()

        suspend fun start() {
            // Come PhoneServer.start(): guarda il processo, non lo stato.
            if (alive) return
            starts++
            alive = true
            state = "RUNNING"
            events += "avvio a ${time.now / 1000} s"
        }

        suspend fun stop() {
            if (!alive) return
            stops++
            events += "arresto a ${time.now / 1000} s"
            if (stopMs > 0) time.sleep(stopMs)
            alive = false
            // Lo stato diventa STOPPED più tardi, su un altro thread: qui non si aggiorna proprio.
        }
    }

    class Bench(val keeper: ServerKeeper, val server: Server, val time: Time, val job: Job)

    fun CoroutineScope.bench(used: Boolean = true, stopMs: Long = 0L): Bench {
        val time = Time()
        val server = Server(time, stopMs)
        val job = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        val keeper = ServerKeeper(
            scope,
            used = { used },
            start = { server.start() },
            stop = { server.stop() },
            clock = { time.now },
            sleep = { time.sleep(it) },
            log = { println("   · $it") },
        )
        return Bench(keeper, server, time, job)
    }

    val minute = 60_000L

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        check("Tenere lo schermo avvia il server", b.server.alive && b.server.starts == 1 && b.keeper.wanted, b.server.events.toString())
        b.keeper.release(Need.SCREEN)
        b.time.advance(3 * minute - 1_000)
        check("Lasciato lo schermo, resta acceso fino a 3 minuti", b.server.alive && b.server.stops == 0 && b.keeper.wanted)
        b.time.advance(2_000)
        check("Dopo 3 minuti si ferma", !b.server.alive && b.server.stops == 1 && !b.keeper.wanted, b.server.events.toString())
        check("L'attesa va a passi di al massimo ${ServerKeeper.STEP_MS / 1000} s", b.time.sleeps.all { it <= ServerKeeper.STEP_MS }, b.time.sleeps.toString())
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        b.keeper.release(Need.SCREEN)
        b.time.advance(minute)
        b.keeper.hold(Need.SCREEN)
        b.time.advance(10 * minute)
        check("Tenuto di nuovo durante l'attesa: niente arresto né secondo avvio", b.server.alive && b.server.stops == 0 && b.server.starts == 1, b.server.events.toString())
        b.keeper.release(Need.SCREEN, 0)
        b.time.settle()
        check("Lasciato con attesa 0: si ferma subito", !b.server.alive && b.server.stops == 1)
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.keeper.hold(Need.PLAYING)
        b.time.settle()
        b.keeper.release(Need.SCREEN, 3 * minute)
        b.keeper.release(Need.PLAYING, 0)
        b.time.advance(2 * minute)
        check("Schermo lasciato con 3 minuti e musica con 0: dopo 2 minuti è acceso", b.server.alive && b.server.stops == 0)
        b.time.advance(minute + 1_000)
        check("... e dopo 3 si ferma", !b.server.alive && b.server.stops == 1, b.server.events.toString())
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.PLAYING)
        b.keeper.hold(Need.CAR)
        b.time.settle()
        b.keeper.release(Need.PLAYING)
        b.time.advance(30 * minute)
        check("Con l'auto collegata la pausa non ferma il server", b.server.alive && b.server.stops == 0)
        b.keeper.release(Need.CAR)
        b.time.advance(2 * minute - 1_000)
        check("Staccata l'auto, resta acceso 2 minuti", b.server.alive)
        b.time.advance(2_000)
        check("... poi si ferma", !b.server.alive && b.server.stops == 1)
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.release(Need.CAR, 0)
        b.keeper.release(Need.MYCAR)
        b.time.advance(10 * minute)
        check("Lasciare un bisogno mai tenuto non fa niente", !b.keeper.wanted && b.server.starts == 0 && b.server.stops == 0 && b.time.sleeps.isEmpty())
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        b.keeper.release(Need.SCREEN, 0)
        b.time.advance(10 * minute)
        check("Due schermate: lasciata la prima, il server resta", b.server.alive && b.keeper.wanted)
        b.keeper.release(Need.SCREEN, 0)
        b.time.settle()
        check("Lasciata anche la seconda, si ferma", !b.server.alive && b.server.stops == 1)
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        b.keeper.release(Need.SCREEN)
        b.time.advance(minute)
        // Xonos tolto dai recenti: lo schermo non serve più, l'attesa si accorcia.
        b.keeper.release(Need.SCREEN, 0)
        b.time.settle()
        check("Lasciato di nuovo con 0 (app tolta dai recenti): si ferma subito", !b.server.alive && b.server.stops == 1)
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.touch(Need.ACCOUNT)
        b.time.settle()
        check("Un bisogno di un momento avvia il server", b.server.alive && b.server.starts == 1)
        b.time.advance(2 * minute - 1_000)
        check("... e lo tiene acceso per la sua attesa", b.server.alive)
        b.time.advance(2_000)
        check("... poi si ferma", !b.server.alive && b.server.stops == 1)
        b.keeper.hold(Need.CAR)
        b.time.settle()
        b.keeper.touch(Need.CAR, 1_000)
        b.time.advance(10 * minute)
        check("Un bisogno di un momento non lascia un bisogno tenuto", b.server.alive && b.server.stops == 1 && b.server.starts == 2)
        b.job.cancel()
    }

    run {
        // L'arresto dura 5 s; a metà arriva un play dall'auto. Lo stato dice ancora "acceso" (RUNNING): il server deve
        // ripartire lo stesso, appena è fermo.
        val b = bench(stopMs = 5_000)
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        b.keeper.release(Need.SCREEN, 0)
        b.time.settle()
        b.time.advance(2_000)
        val stopping = b.server.stops == 1 && b.server.alive
        b.keeper.touch(Need.PLAYING)
        b.time.advance(1_000)
        check("Un bisogno durante l'arresto aspetta che finisca", stopping && b.server.starts == 1, b.server.events.toString())
        b.time.advance(3_000)
        check(
            "... poi il server riparte (stato ancora ${b.server.state})",
            b.server.alive && b.server.starts == 2 && b.server.stops == 1 && b.keeper.wanted,
            b.server.events.toString(),
        )
        b.time.advance(8 * minute + 1_000)
        check("... e si ferma dopo l'attesa della musica", !b.server.alive && b.server.stops == 2, b.server.events.toString())
        b.job.cancel()
    }

    run {
        val b = bench()
        b.keeper.hold(Need.SCREEN)
        b.time.settle()
        // Il processo è morto da solo (Android): revive lo fa ripartire, perché serve.
        b.server.alive = false
        b.keeper.revive()
        b.time.settle()
        check("Fermato da Android mentre serve: riparte", b.server.alive && b.server.starts == 2)
        b.keeper.release(Need.SCREEN, 0)
        b.time.settle()
        b.keeper.revive()
        b.time.settle()
        check("Fermato da Xonos perché non serve: niente lo fa ripartire", !b.server.alive && b.server.starts == 2 && b.server.stops == 1)
        b.job.cancel()
    }

    run {
        val b = bench(used = false)
        b.keeper.hold(Need.SCREEN)
        b.keeper.touch(Need.ACCOUNT)
        b.keeper.hold(Need.MYCAR)
        b.time.settle()
        b.keeper.revive()
        b.time.advance(minute)
        check("Se Xonos non usa il server sul telefono, non parte mai", b.server.starts == 0 && !b.server.alive)
        b.job.cancel()
    }

    run {
        // Il telefono ha dormito: l'attesa finta non è andata avanti, l'orologio sì. Al prossimo passo si ferma.
        val b = bench()
        b.keeper.hold(Need.PLAYING)
        b.time.settle()
        b.keeper.release(Need.PLAYING)
        b.time.advance(ServerKeeper.STEP_MS / 2)
        b.time.now += 20 * minute
        b.time.advance(ServerKeeper.STEP_MS)
        check("Dopo il sonno del telefono si ferma al primo passo", !b.server.alive && b.server.stops == 1, b.server.events.toString())
        b.job.cancel()
    }

    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(if (failures == 0) 0 else 1)
}
