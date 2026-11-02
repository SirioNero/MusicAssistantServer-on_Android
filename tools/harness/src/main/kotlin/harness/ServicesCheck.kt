package harness

import it.xonos.ma.Language
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.phone.model.AddServicePage
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.FlowPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.Notice
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.ServicePage
import it.xonos.ma.phone.model.ServicesModel
import it.xonos.ma.phone.model.ServicesPage
import it.xonos.ma.phone.model.value
import it.xonos.ma.data.FlowStep
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Services
import it.xonos.ma.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * La gestione dei servizi di Music Assistant ([Services]) contro il Music Assistant di prova, con il
 * servizio finto "xonos_test" (tools/ma-test/xonos_test): modulo con un errore, accesso "nel
 * browser" (il finto server di podcasts.py rimanda al callback del flusso), fine, opzioni,
 * pulsante, riconfigurazione annullata e rimozione. Il "browser" qui è una richiesta HTTP.
 */
fun main() {
    // Un errore non previsto chiude tutto: i thread di OkHttp terrebbero viva la JVM.
    try {
        checks()
    } catch (e: Throwable) {
        e.printStackTrace()
        kotlin.system.exitProcess(2)
    }
}

private fun checks(): Unit = runBlocking {
    val settings = Settings()
    val repo = MaRepository(settings)
    val services = Services(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    var failures = 0

    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    val configured = services.configured()
    check("Servizi configurati", configured.any { it.domain == "filesystem_local" }, configured.joinToString { "${it.name} (${it.type}, ${it.status})" })
    val kinds = services.kinds()
    check("Tipi di servizio", kinds.any { it.domain == "spotify" } && kinds.any { it.domain == "xonos_test" }, "${kinds.size} tipi")
    val icon = services.icon("spotify")
    check("Icona di Spotify", icon != null && icon.bytes.isNotEmpty(), "${icon?.mime} ${icon?.bytes?.size} byte")

    // I passi nuovi arrivano anche come eventi: si aspetta quello finale, come farà lo schermo.
    val ended = mutableMapOf<String, CompletableDeferred<FlowStep>>()
    repo.addEventListener { event, objectId, data ->
        if (event != Services.FLOW_EVENT || objectId == null) return@addEventListener
        val step = FlowStep.from(data as? JSONObject) ?: return@addEventListener
        if (step.done) synchronized(ended) { ended.getOrPut(objectId) { CompletableDeferred() } }.complete(step)
    }
    fun endOf(flowId: String) = synchronized(ended) { ended.getOrPut(flowId) { CompletableDeferred() } }

    var step = services.setup("xonos_test")
    check("Flusso: primo passo è un modulo", step.type == "form" && step.fields.any { it.key == "secret" && it.type == "secure_string" }, step.fields.joinToString { "${it.key}:${it.type}" })
    val quality = step.fields.first { it.key == "quality" }
    check("Flusso: campo a scelta", quality.options.map { it.value } == listOf("low", "high", "lossless") && quality.current == "high", "${quality.options}")
    check("Flusso: intervallo", step.fields.first { it.key == "volume" }.range == 0..100)
    val values = step.fields.filter { !it.display }.associate { it.key to it.current }.toMutableMap()
    values["account"] = "errore"
    values["secret"] = "segreto"
    step = services.submit(step.flowId, values)
    check("Flusso: errore su un campo", step.type == "form" && step.errors["account"] != null, "${step.errors}")
    values["account"] = "prova"
    step = services.submit(step.flowId, values)
    check("Flusso: accesso nel browser", step.type == "external" && step.url?.contains("/oauth") == true, "${step.url}")

    // Il "browser": segue i rimandi fino al callback del flusso in Music Assistant.
    val browser = OkHttpClient.Builder().followRedirects(true).build()
    browser.newCall(Request.Builder().url(step.url!!).build()).execute().use { println("   browser: ${it.code} ${it.request.url}") }
    val next = withTimeoutOrNull(20_000) { endOf(step.flowId).await() }
    check("Flusso: finito dopo il ritorno dal browser", next?.type == "finish" && next.instanceId != null, "${next?.type} ${next?.instanceId} ${next?.reason}")

    val instance = next?.instanceId ?: services.configured().first { it.domain == "xonos_test" }.instanceId
    // Le opzioni si leggono a servizio caricato.
    withTimeoutOrNull(15_000) { while (services.configured().none { it.instanceId == instance && it.status == "loaded" }) delay(300) }
    val service = services.config(instance)
    check("Opzioni: campi e valori", service.fields.any { it.key == "nickname" && it.current == "Xonos" } && service.fields.any { it.key == "genres" && it.multiValue }, service.fields.joinToString { "${it.key}=${it.current}" })
    val saved = services.save(service, mapOf("nickname" to "Auto", "genres" to listOf("rock", "jazz"), "bitrate" to 128))
    val again = services.config(instance)
    check(
        "Opzioni: salvate",
        again.fields.first { it.key == "nickname" }.current == "Auto" && again.fields.first { it.key == "genres" }.current == listOf("rock", "jazz") &&
            (again.fields.first { it.key == "bitrate" }.current as Number).toInt() == 128,
        saved.fields.joinToString { "${it.key}=${it.current}" },
    )
    val action = services.action(instance, "test")
    check("Pulsante: messaggio", action.message?.contains("prova") == true, "${action.message}")
    val reconfigure = services.reconfigure(instance)
    check("Riconfigura: modulo con i valori di prima", reconfigure.type == "form", reconfigure.fields.joinToString { "${it.key}=${it.current}" })
    services.abort(reconfigure.flowId)
    services.remove(instance)
    check("Rimosso", services.configured().none { it.instanceId == instance })

    val abort = services.setup("xonos_test")
    val abortValues = abort.fields.filter { !it.display }.associate { it.key to it.current }.toMutableMap()
    abortValues["account"] = "abort"
    abortValues["secret"] = "x"
    val aborted = services.submit(abort.flowId, abortValues)
    check("Flusso fermato dal servizio", aborted.type == "abort" && aborted.reason != null, "${aborted.reason}")

    // ---------- Il modello del telefono, come lo usano le schermate ----------
    println("\nModello del telefono")
    suspend fun until(ms: Long = 20_000, ok: () -> Boolean): Boolean = withTimeoutOrNull(ms) {
        while (!ok()) delay(100)
        true
    } ?: false
    val model = PhoneModel(
        repo,
        Library(repo),
        settings,
        ScreenCache(null),
        FixedFinder(),
        "Xonos (prova)",
        { "xonos-servicescheck" },
        MutableStateFlow(false),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    check("Modello: amministratore, flussi disponibili", model.services.admin && model.services.flowsAvailable)
    model.services.open()
    val list = model.page as ServicesPage
    model.start(list)
    until { list.services !is Load.Loading }
    check("Modello: elenco dei servizi", list.services.value?.any { it.domain == "filesystem_local" } == true, "${list.services}")
    model.services.add()
    val add = model.page as AddServicePage
    model.start(add)
    until { add.kinds !is Load.Loading }
    val kind = add.kinds.value?.firstOrNull { it.domain == "xonos_test" }
    check("Modello: tipi da aggiungere, senza quelli interni", kind != null && add.kinds.value!!.none { it.builtin }, "${add.kinds.value?.size} tipi")

    model.services.add(kind!!)
    val flow = model.page as FlowPage
    model.start(flow)
    until { flow.step.value?.type == "form" }
    check("Modello: il modulo parte dai suoi valori", flow.values["quality"] == "high" && flow.values.containsKey("account"), "${flow.values}")
    flow.values["account"] = "errore"
    flow.values["secret"] = "segreto"
    model.services.submit(flow)
    until { flow.step.value?.errors?.containsKey("account") == true }
    check("Modello: errore sul campo, i valori scritti restano", flow.values["secret"] == "segreto" && flow.values["account"] == "errore", "${flow.step.value?.errors}")
    flow.values["account"] = "prova"
    model.services.submit(flow)
    until { flow.step.value?.type == "external" }
    val external = flow.step.value!!
    check("Modello: accesso nel browser", external.type == "external" && external.url != null)
    browser.newCall(Request.Builder().url(external.url!!).build()).execute().close()
    until { flow.step.value?.type == "finish" }
    check("Modello: la fine arriva con l'evento", flow.step.value?.type == "finish" && flow.step.value?.instanceId != null, "${flow.step.value}")
    val added = flow.step.value?.instanceId
    model.services.close(flow)
    check("Modello: chiuso il flusso si torna all'elenco", model.page === list)
    until { list.services.value?.any { it.instanceId == added } == true }
    check("Modello: l'elenco si ricarica con il servizio nuovo", list.services.value?.any { it.instanceId == added } == true)

    withTimeoutOrNull(15_000) { while (services.configured().none { it.instanceId == added && it.status == "loaded" }) delay(300) }
    model.services.open(list.services.value!!.first { it.instanceId == added })
    val page = model.page as ServicePage
    model.start(page)
    until { page.service !is Load.Loading }
    check("Modello: opzioni del servizio", page.values["nickname"] == "Xonos" && page.values[ServicesModel.ENABLED] == true && !page.changed, "${page.values}")
    page.values["nickname"] = "Macchina"
    check("Modello: una modifica si vede", page.changed)
    model.notice = null
    model.services.save(page)
    until { model.notice != null && !page.saving }
    check("Modello: salvato", model.notice == Notice.Saved && !page.changed && services.config(added!!).fields.first { it.key == "nickname" }.current == "Macchina", "${model.notice}")
    model.services.action(page, "test")
    until { page.message != null && !page.saving }
    check("Modello: pulsante", page.message?.contains("prova") == true, "${page.message}")

    model.services.reconfigure(page)
    val redo = model.page as FlowPage
    model.start(redo)
    until { redo.step.value?.type == "form" }
    val againId = redo.step.value?.flowId
    model.back()
    delay(1_000)
    val gone = runCatching { services.current(againId!!) }.isFailure
    check("Modello: indietro chiude il flusso anche in Music Assistant", model.page === page && gone)

    // Un flusso andato oltre il primo passo: indietro prima chiede; "Continua" resta, "Interrompi" lo chiude.
    model.services.add(kind)
    val half = model.page as FlowPage
    model.start(half)
    until { half.step.value?.type == "form" }
    half.values["account"] = "prova"
    half.values["secret"] = "x"
    model.services.submit(half)
    until { half.step.value?.type == "external" }
    model.back()
    check("Modello: indietro a metà flusso chiede prima", model.page === half && half.confirmLeave, "${model.page}")
    half.confirmLeave = false
    delay(500)
    val halfId = half.step.value!!.flowId
    check("Modello: «Continua» resta nel flusso, aperto anche in Music Assistant", model.page === half && runCatching { services.current(halfId) }.isSuccess)
    model.back()
    model.leaveFlow(half)
    delay(1_000)
    check("Modello: «Interrompi» chiude il flusso anche in Music Assistant", model.page === page && runCatching { services.current(halfId) }.isFailure, "${model.page}")

    // Tornati dal browser con l'evento perso (la connessione era chiusa): il flusso è già finito.
    model.services.add(kind)
    val lost = model.page as FlowPage
    model.start(lost)
    until { lost.step.value?.type == "form" }
    lost.values["account"] = "prova"
    lost.values["secret"] = "x"
    model.services.submit(lost)
    until { lost.step.value?.type == "external" }
    check("Modello: un altro flusso fino al browser", lost.step.value?.type == "external", "${lost.step}")
    // Una copia del passo, che gli eventi non aggiornano: è come se si fossero persi.
    val missed = FlowPage(lost.title, lost.domain) { error("non serve") }.apply {
        this.step = Load.Ready(lost.step.value!!)
        before = lost.before
    }
    model.push(missed)
    browser.newCall(Request.Builder().url(missed.step.value!!.url!!).build()).execute().close()
    withTimeoutOrNull(20_000) { while (services.configured().none { it.domain == "xonos_test" && it.instanceId !in missed.before }) delay(300) }
    delay(500)
    model.wake()
    until { missed.step.value?.done == true }
    val second = missed.step.value?.instanceId
    check("Modello: al ritorno nell'app la fine si ricava dai servizi", missed.step.value?.type == "finish" && second != null, "${missed.step.value}")
    model.back()
    model.back()

    model.notice = null
    model.services.remove(page)
    until { model.notice != null && !page.saving }
    check("Modello: tolto, si torna all'elenco", model.notice == Notice.Removed && model.page === list && services.configured().none { it.instanceId == added }, "${model.notice}")
    // Appena aggiunto si sta ancora caricando: remove() aspetta da sé (il bug di Music Assistant in CLAUDE.md).
    if (second != null) services.remove(second)
    check("Tolto anche mentre si caricava, l'elenco funziona", runCatching { services.configured() }.getOrNull()?.none { it.instanceId == second } == true)

    // La lingua: Music Assistant traduce da sé le etichette dei moduli (qui il livello dei log).
    val local = services.configured().first { it.domain == "filesystem_local" }.instanceId
    val english = services.config(local).fields.firstOrNull { it.key == "log_level" }?.label
    settings.language = Language.ITALIAN
    repo.languageChanged()
    delay(1_000)
    val italian = services.config(local).fields.firstOrNull { it.key == "log_level" }?.label
    check("Lingua dei moduli", english != null && italian != null && english != italian, "$english → $italian")

    repo.release()
    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
