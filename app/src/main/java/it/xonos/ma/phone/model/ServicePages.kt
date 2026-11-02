package it.xonos.ma.phone.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.xonos.ma.data.ConfigField
import it.xonos.ma.data.FlowStep
import it.xonos.ma.data.ServiceConfig
import it.xonos.ma.data.ServiceKind

/** I servizi configurati in Music Assistant (solo per gli amministratori). */
class ServicesPage : Page() {
    var services by mutableStateOf<Load<List<ServiceConfig>>>(Load.Loading)

    /** Il server è più vecchio della 2.10: si vedono e si tolgono, ma non si aggiungono né si configurano da qui. */
    var flowsMissing by mutableStateOf(false)
}

/** I tipi di servizio che si possono aggiungere, con un filtro per nome. */
class AddServicePage : Page() {
    var kinds by mutableStateOf<Load<List<ServiceKind>>>(Load.Loading)
    var filter by mutableStateOf("")
}

/**
 * Un flusso di configurazione di Music Assistant (aggiungere o riconfigurare un servizio), un
 * passo alla volta: [start] lo comincia. I valori del modulo stanno in [values].
 */
class FlowPage(val title: String, val domain: String, internal val start: suspend () -> FlowStep) : Page() {
    var step by mutableStateOf<Load<FlowStep>>(Load.Loading)
    val values = mutableStateMapOf<String, Any?>()
    var sending by mutableStateOf(false)
    var showAdvanced by mutableStateOf(false)

    /** Il servizio esistente prima del flusso, per capire se è finito bene quando l'evento della fine si è perso. */
    internal var before: Set<String> = emptySet()

    /** Vero quando il flusso è andato oltre il primo passo: lasciarlo butterebbe ciò che si è già fatto. */
    internal var advanced = false

    /** Vero mentre si chiede conferma prima di lasciare il flusso a metà (indietro, "Annulla"). */
    var confirmLeave by mutableStateOf(false)
}

/** Un servizio configurato: le sue opzioni, abilitarlo o no, riconfigurarlo, toglierlo. */
class ServicePage(val instanceId: String, val title: String) : Page() {
    var service by mutableStateOf<Load<ServiceConfig>>(Load.Loading)
    val values = mutableStateMapOf<String, Any?>()
    var saving by mutableStateOf(false)
    var showAdvanced by mutableStateOf(false)

    /** Il risultato di un pulsante, da mostrare sotto. */
    var message by mutableStateOf<String?>(null)

    /** Un indirizzo che un pulsante chiede di aprire nel browser: lo schermo lo apre e lo azzera. */
    var openUrl by mutableStateOf<String?>(null)

    /** Vero mentre si chiede conferma prima di togliere il servizio. */
    var confirmRemove by mutableStateOf(false)

    /** I valori com'erano all'apertura: si salva solo ciò che cambia. */
    internal var original: Map<String, Any?> = emptyMap()

    /** Vero se qualche valore è diverso da com'era all'apertura. */
    val changed: Boolean get() = values.any { (key, value) -> original[key] != value }
}

/** I valori iniziali di un modulo: quello attuale di ogni campo, o il predefinito. */
internal fun initialValues(fields: List<ConfigField>): Map<String, Any?> =
    fields.filter { !it.display }.associate { it.key to it.current }
