package it.xonos.ma.phone.model

import androidx.compose.runtime.mutableStateMapOf
import it.xonos.ma.data.Connection
import it.xonos.ma.data.FlowStep
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ServiceConfig
import it.xonos.ma.data.ServiceIcon
import it.xonos.ma.data.ServiceKind
import it.xonos.ma.data.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * La gestione dei servizi di Music Assistant dal telefono: l'elenco, aggiungerne uno con il suo
 * flusso a passi, le opzioni, riconfigurarlo (per esempio rifare l'accesso), toglierlo. Solo per
 * gli amministratori ([admin]); i flussi ci sono dalla 2.10.
 */
class ServicesModel internal constructor(
    private val phone: PhoneModel,
    private val repo: MaRepository,
    private val scope: CoroutineScope,
) {
    private val services = Services(repo)

    /** I flussi aperti sullo schermo, per id: i loro passi nuovi arrivano anche come eventi. */
    private val flows = mutableMapOf<String, FlowPage>()

    /** Le icone dei servizi già arrivate, per dominio (null: il servizio non ne ha). */
    val icons = mutableStateMapOf<String, ServiceIcon?>()
    private val askedIcons = mutableSetOf<String>()

    /** Vero se chi ha fatto l'accesso è un amministratore di Music Assistant. */
    val admin: Boolean get() = (repo.connection.value as? Connection.Connected)?.server?.admin == true

    /** Vero dalla 2.10: i servizi si aggiungono e si riconfigurano con i flussi a passi. */
    val flowsAvailable: Boolean
        get() = ((repo.connection.value as? Connection.Connected)?.server?.schemaVersion ?: 0) >= Services.FLOW_SCHEMA

    init {
        repo.addEventListener { event, objectId, data ->
            if (event != Services.FLOW_EVENT || objectId == null) return@addEventListener
            val step = FlowStep.from(data as? JSONObject) ?: return@addEventListener
            scope.launch { flows[objectId]?.let { page -> show(page, step) } }
        }
    }

    fun open() = phone.push(ServicesPage())

    // ---------- Elenco ----------

    internal fun load(page: ServicesPage) {
        page.services = Load.Loading
        phone.launchLoad({ page.services = Load.Failed(it) }) {
            page.flowsMissing = !flowsAvailable
            page.services = Load.Ready(services.configured().sortedWith(compareBy({ TYPES.indexOf(it.type).let { i -> if (i < 0) TYPES.size else i } }, { it.name.lowercase() })))
        }
    }

    internal fun load(page: AddServicePage) {
        page.kinds = Load.Loading
        phone.launchLoad({ page.kinds = Load.Failed(it) }) {
            val configured = services.configured().map { it.domain }.toSet()
            // Quelli già configurati che non ammettono un secondo account non si propongono.
            val kinds = services.kinds().filter { !it.builtin && (it.multiInstance || it.domain !in configured) }
            page.kinds = Load.Ready(kinds.sortedWith(compareBy({ TYPES.indexOf(it.type).let { i -> if (i < 0) TYPES.size else i } }, { it.name.lowercase() })))
        }
    }

    /** Chiede l'icona di un servizio la prima volta che serve; arriva in [icons]. */
    fun loadIcon(domain: String) {
        if (!askedIcons.add(domain)) return
        scope.launch {
            icons[domain] = try {
                services.icon(domain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun add() = phone.push(AddServicePage())

    // ---------- Flussi ----------

    /** Aggiunge un servizio: si apre il suo flusso. */
    fun add(kind: ServiceKind) = phone.push(FlowPage(kind.name, kind.domain) { services.setup(kind.domain) })

    /** Riconfigura un servizio (rifare l'accesso, cambiare account...). */
    fun reconfigure(page: ServicePage) {
        val domain = page.service.value?.domain ?: return
        phone.push(FlowPage(page.title, domain) { services.reconfigure(page.instanceId) })
    }

    internal fun start(page: FlowPage) {
        page.step = Load.Loading
        phone.launchLoad({ page.step = Load.Failed(it) }) {
            page.before = services.configured().filter { it.domain == page.domain }.map { it.instanceId }.toSet()
            show(page, page.start())
        }
    }

    private fun show(page: FlowPage, step: FlowStep) {
        val previous = page.step.value
        previous?.flowId?.takeIf { it != step.flowId }?.let(flows::remove)
        if (step.done) flows.remove(step.flowId) else flows[step.flowId] = page
        if (step.type == "finish") step.instanceId?.let(services::changed)
        // Un modulo nuovo parte dai suoi valori; lo stesso modulo con gli errori tiene ciò che si è scritto.
        if (step.type == "form" && (previous?.type != "form" || previous.stepId != step.stepId)) {
            page.values.clear()
            page.values.putAll(initialValues(step.fields))
        }
        page.step = Load.Ready(step)
    }

    /** Manda il modulo: arriva il passo dopo, o lo stesso con gli errori. */
    fun submit(page: FlowPage) {
        val step = page.step.value ?: return
        if (step.type != "form" || page.sending) return
        page.sending = true
        val values = page.values.toMap()
        scope.launch {
            try {
                show(page, services.submit(step.flowId, values))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phone.notice = Notice.Failed(e)
            } finally {
                page.sending = false
            }
        }
    }

    /**
     * Tornati nell'app (per esempio dopo l'accesso nel browser): il passo attuale, nel caso
     * l'evento si sia perso. Se il flusso non c'è più è finito mentre si era fuori: è andato
     * bene se è comparso un servizio nuovo di quel tipo.
     */
    fun refresh(page: FlowPage) {
        val step = page.step.value ?: return
        if (step.done) return
        scope.launch {
            try {
                // Fuori dall'app la connessione si può essere chiusa: il flusso invece resta in Music Assistant.
                phone.awaitConnected()
                show(page, services.current(step.flowId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MaException) {
                if (e.problem == Problem.NOT_CONNECTED || e.problem == Problem.NETWORK) return@launch
                val now = runCatching { services.configured() }.getOrNull() ?: return@launch
                val added = now.filter { it.domain == page.domain }.map { it.instanceId }.firstOrNull { it !in page.before }
                // Il flusso non c'è più e non è comparso un servizio nuovo: si è chiuso mentre si era fuori (per esempio
                // nel browser). Music Assistant risponde "'Unknown (or finished) setup flow: …'": lo si dice a parole.
                val reason = if (e.message.orEmpty().contains("setup flow")) ENDED_AWAY else e.message
                show(page, step.copy(type = if (added != null) "finish" else "abort", instanceId = added, reason = if (added != null) null else reason))
            }
        }
    }

    /** Si lascia un flusso (indietro, "Annulla"): Music Assistant lo chiude subito invece di aspettare che scada. */
    internal fun leave(page: FlowPage) {
        val step = page.step.value ?: return
        flows.remove(step.flowId)
        if (!step.done) scope.launch { services.abort(step.flowId) }
    }

    /**
     * Fine del flusso: si torna indietro e la schermata di prima si ricarica. Con un servizio
     * nuovo si torna all'elenco, dove compare; se il flusso si è fermato si può sceglierne un altro.
     */
    fun close(page: FlowPage) {
        if (phone.page === page) phone.back()
        if (page.step.value?.type == "finish" && phone.page is AddServicePage) phone.back()
        phone.reload(phone.page)
    }

    // ---------- Un servizio ----------

    fun open(service: ServiceConfig) = phone.push(ServicePage(service.instanceId, service.name))

    internal fun load(page: ServicePage) {
        page.service = Load.Loading
        phone.launchLoad({ page.service = Load.Failed(it) }) { fill(page, services.config(page.instanceId)) }
    }

    private fun fill(page: ServicePage, service: ServiceConfig) {
        val values = initialValues(service.fields) + (ENABLED to service.enabled)
        page.values.clear()
        page.values.putAll(values)
        page.original = values
        page.service = Load.Ready(service)
    }

    /** Salva le opzioni cambiate. */
    fun save(page: ServicePage) {
        val service = page.service.value ?: return
        if (page.saving) return
        val changed = page.values.filter { (key, value) -> page.original[key] != value }
        if (changed.isEmpty()) return
        page.saving = true
        page.message = null
        scope.launch {
            try {
                fill(page, services.save(service, changed))
                phone.notice = Notice.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phone.notice = Notice.Failed(e)
            } finally {
                page.saving = false
            }
        }
    }

    /** Un pulsante delle opzioni: un messaggio, un indirizzo da aprire o un modulo nuovo. */
    fun action(page: ServicePage, action: String) {
        val service = page.service.value ?: return
        page.saving = true
        page.message = null
        scope.launch {
            try {
                val result = services.action(page.instanceId, action)
                page.message = result.message
                result.url?.let { page.openUrl = it }
                if (result.fields.isNotEmpty()) fill(page, service.copy(fields = result.fields))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phone.notice = Notice.Failed(e)
            } finally {
                page.saving = false
            }
        }
    }

    /** Toglie il servizio da Music Assistant (con la sua musica dalla libreria). */
    fun remove(page: ServicePage) {
        page.saving = true
        scope.launch {
            try {
                services.remove(page.instanceId)
                phone.notice = Notice.Removed
                if (phone.page === page) phone.back()
                phone.reload(phone.page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phone.notice = Notice.Failed(e)
            } finally {
                page.saving = false
            }
        }
    }

    companion object {
        /** Il motivo di un flusso chiuso mentre si era fuori dall'app: lo schermo lo dice a parole. */
        const val ENDED_AWAY = "xonos:ended-away"

        /** L'ordine dei gruppi: prima la musica, poi le casse, il resto in fondo. */
        val TYPES = listOf("music", "player", "plugin", "metadata", "audio_analysis")

        /** Il valore "abilitato" del servizio, che si salva insieme alle opzioni. */
        const val ENABLED = "enabled"
    }
}
