package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import it.xonos.ma.R
import it.xonos.ma.data.ConfigField
import it.xonos.ma.data.Connection
import it.xonos.ma.data.FlowStep
import it.xonos.ma.data.ServiceConfig
import it.xonos.ma.data.ServiceKind
import it.xonos.ma.data.Services
import it.xonos.ma.phone.model.AddServicePage
import it.xonos.ma.phone.model.FlowPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.ServicePage
import it.xonos.ma.phone.model.ServicesModel
import it.xonos.ma.phone.model.ServicesPage
import kotlin.math.roundToInt

// La gestione dei servizi di Music Assistant (solo per gli amministratori): l'elenco, aggiungerne
// uno, i passi del suo flusso, le opzioni. I moduli si disegnano dai campi che manda Music
// Assistant, senza sapere niente del servizio.

/** Vero se chi ha fatto l'accesso a Music Assistant è un amministratore (si aggiorna con la connessione). */
@Composable
internal fun rememberAdmin(model: PhoneModel): Boolean {
    val connection by model.connection.collectAsState()
    return (connection as? Connection.Connected)?.server?.admin == true
}

/** I servizi configurati, divisi per tipo; in cima "Aggiungi un servizio". */
@Composable
internal fun ServicesScreen(page: ServicesPage, model: PhoneModel, host: Host) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TitleBar(stringResource(R.string.services_title)) { model.back() }
        Loaded(page.services, host::describe, { model.reload(page) }) { list ->
            LazyColumn(Modifier.fillMaxSize().testTag("services"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item(key = "add", contentType = "add") {
                    if (page.flowsMissing) {
                        Note(XIcons.Warning, stringResource(R.string.services_old_server))
                    } else {
                        Button(onClick = { model.services.add() }, modifier = Modifier.testTag("add_service").fillMaxWidth().height(52.dp)) {
                            Icon(XIcons.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.services_add))
                        }
                    }
                }
                if (list.isEmpty()) item(key = "empty") { Empty(stringResource(R.string.services_none)) }
                for ((type, services) in list.groupBy { group(it.type) }) {
                    item(key = "type-$type", contentType = "header") { GroupTitle(type) }
                    items(services, key = { it.instanceId }, contentType = { "service" }) { service ->
                        ServiceRow(model, service.domain, service.name, serviceStatus(service), service.lastError != null && service.enabled) { model.services.open(service) }
                    }
                }
            }
        }
    }
}

/** I tipi di servizio che si possono aggiungere, con un filtro per nome. */
@Composable
internal fun AddServiceScreen(page: AddServicePage, model: PhoneModel, host: Host) {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TitleBar(stringResource(R.string.services_add)) { model.back() }
        OutlinedTextField(
            value = page.filter,
            onValueChange = { page.filter = it },
            placeholder = { Text(stringResource(R.string.services_filter)) },
            leadingIcon = { Icon(XIcons.Search, null) },
            singleLine = true,
            modifier = Modifier.testTag("service_filter").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Loaded(page.kinds, host::describe, { model.reload(page) }) { kinds ->
            val filter = page.filter.trim()
            val shown = if (filter.isEmpty()) kinds else kinds.filter { it.name.contains(filter, ignoreCase = true) || it.domain.contains(filter, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize().testTag("service_kinds"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                if (shown.isEmpty()) item(key = "empty") { Empty() }
                for ((type, group) in shown.groupBy { group(it.type) }) {
                    item(key = "type-$type", contentType = "header") { GroupTitle(type) }
                    items(group, key = { it.domain }, contentType = { "kind" }) { kind ->
                        ServiceRow(model, kind.domain, kind.name, kindLine(kind), false, maxLines = 2) { model.services.add(kind) }
                    }
                }
            }
        }
    }
}

/** Un flusso: un modulo, un accesso nel browser, un'attesa, la fine. */
@Composable
internal fun FlowScreen(page: FlowPage, model: PhoneModel, host: Host) {
    // Sul telefono Spotify ha solo librespot: va detto prima che la persona faccia tutti i passi.
    val spotifyOnPhone = remember(page) { page.domain == "spotify" && model.onPhoneServer }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TitleBar(page.title, XIcons.Close) { model.back() }
        Loaded(page.step, host::describe, { model.reload(page) }) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (spotifyOnPhone) Note(XIcons.Warning, stringResource(R.string.spotify_old_accounts))
                step.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                step.description?.let { Markdown(it) }
                when (step.type) {
                    "form" -> FlowForm(page, step, model)
                    "external" -> FlowExternal(step)
                    "progress" -> FlowProgress(step)
                    "finish" -> FlowEnd(XIcons.Check, stringResource(R.string.flow_finished), stringResource(R.string.flow_done)) { model.services.close(page) }
                    "abort" -> FlowEnd(
                        XIcons.Warning,
                        step.reason?.let { stringResource(R.string.flow_aborted, it) } ?: stringResource(R.string.flow_aborted_plain),
                        stringResource(R.string.flow_close),
                    ) { model.services.close(page) }
                    else -> FlowProgress(step)
                }
            }
        }
    }
}

@Composable
private fun FlowForm(page: FlowPage, step: FlowStep, model: PhoneModel) {
    step.errors["base"]?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    ConfigForm(step.fields, page.values, step.errors, page.showAdvanced, enabled = !page.sending, onAction = null)
    if (step.fields.any { isAdvanced(it) && !it.hidden }) AdvancedToggle(page.showAdvanced) { page.showAdvanced = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { model.back() }) { Text(stringResource(R.string.flow_cancel)) }
        Button(onClick = { model.services.submit(page) }, enabled = !page.sending, modifier = Modifier.testTag("flow_next")) {
            if (page.sending) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
            }
            Text(stringResource(if (step.lastStep == true) R.string.flow_finish else R.string.flow_next))
        }
    }
}

/** L'accesso nel browser: si apre la pagina del servizio; finito lì, Music Assistant manda il passo dopo. */
@Composable
private fun FlowExternal(step: FlowStep) {
    val uri = LocalUriHandler.current
    val url = step.url ?: return FlowProgress(step)
    Button(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.testTag("flow_open").fillMaxWidth().height(52.dp)) {
        Icon(XIcons.OpenInNew, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.flow_open_browser))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.flow_external_wait), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FlowProgress(step: FlowStep) {
    Text(step.progressText ?: stringResource(R.string.flow_working), style = MaterialTheme.typography.bodyLarge)
    val progress = step.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    // Per esempio il QR code di un abbinamento.
    step.image?.let { image ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(240.dp))
        }
    }
}

@Composable
private fun FlowEnd(icon: ImageVector, text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
    Button(onClick = onClick, modifier = Modifier.testTag("flow_close").fillMaxWidth().height(52.dp)) { Text(button) }
}

/** Un servizio configurato: abilitato o no, le opzioni, i pulsanti, riconfigurarlo, toglierlo. */
@Composable
internal fun ServiceScreen(page: ServicePage, model: PhoneModel, host: Host) {
    val uri = LocalUriHandler.current
    page.openUrl?.let { url ->
        LaunchedEffect(url) {
            runCatching { uri.openUri(url) }
            page.openUrl = null
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TitleBar(page.title) { model.back() }
        Loaded(page.service, host::describe, { model.reload(page) }) { service ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceIcon(model, service.domain, 48)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(service.name, style = MaterialTheme.typography.titleLarge)
                        Text(serviceStatus(service), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                service.lastError?.takeIf { service.enabled }?.let { Note(XIcons.Warning, it, error = true) }
                SwitchLine(stringResource(R.string.service_enabled), null, page.values[ServicesModel.ENABLED] as? Boolean ?: true, enabled = !page.saving) {
                    page.values[ServicesModel.ENABLED] = it
                }
                ConfigForm(service.fields, page.values, emptyMap(), page.showAdvanced, enabled = !page.saving) { model.services.action(page, it) }
                if (service.fields.any { isAdvanced(it) && !it.hidden }) AdvancedToggle(page.showAdvanced) { page.showAdvanced = it }
                page.message?.let { Note(XIcons.Check, it) }
                Button(onClick = { model.services.save(page) }, enabled = page.changed && !page.saving, modifier = Modifier.testTag("service_save").fillMaxWidth().height(52.dp)) {
                    if (page.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.service_save))
                }
                if (model.services.flowsAvailable) {
                    OutlinedButton(onClick = { model.services.reconfigure(page) }, enabled = !page.saving, modifier = Modifier.testTag("service_reconfigure").fillMaxWidth()) {
                        Icon(XIcons.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.service_reconfigure))
                    }
                }
                TextButton(
                    onClick = { page.confirmRemove = true },
                    enabled = !page.saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("service_remove").fillMaxWidth(),
                ) {
                    Icon(XIcons.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.service_remove))
                }
            }
        }
    }
    if (page.confirmRemove) {
        AlertDialog(
            onDismissRequest = { page.confirmRemove = false },
            title = { Text(stringResource(R.string.service_remove)) },
            text = { Text(stringResource(R.string.service_remove_confirm, page.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        page.confirmRemove = false
                        model.services.remove(page)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { page.confirmRemove = false }) { Text(stringResource(R.string.flow_cancel)) } },
        )
    }
}

// ---------- Moduli ----------

/**
 * I campi di un modulo di Music Assistant, nell'ordine in cui arrivano, con i titoli dei gruppi.
 * Quelli che dipendono da un altro campo compaiono quando la condizione vale; gli avanzati solo
 * con [showAdvanced]. [onAction] è per i pulsanti delle opzioni (nei flussi non ce ne sono).
 */
@Composable
private fun ConfigForm(
    fields: List<ConfigField>,
    values: SnapshotStateMap<String, Any?>,
    errors: Map<String, String>,
    showAdvanced: Boolean,
    enabled: Boolean,
    onAction: ((String) -> Unit)?,
) {
    var category: String? = null
    for (field in fields) {
        if (field.hidden || (isAdvanced(field) && !showAdvanced)) continue
        if (field.type == "action" && onAction == null) continue
        if (!ConfigField.met(field, values)) continue
        if (field.category != category) {
            category = field.category
            field.categoryLabel?.takeIf { field.category != "generic" }?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        FieldView(field, values, errors[field.key], enabled && !field.readOnly, onAction)
    }
}

private fun isAdvanced(field: ConfigField) = field.advanced || field.category == "advanced"

@Composable
private fun FieldView(field: ConfigField, values: SnapshotStateMap<String, Any?>, error: String?, enabled: Boolean, onAction: ((String) -> Unit)?) {
    val value = values[field.key]
    val tag = "field_${field.key}"
    when {
        field.type == "divider" -> HorizontalDivider()
        field.type == "label" -> Markdown(field.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        field.type == "alert" -> Note(XIcons.Warning, field.label)
        field.type == "action" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onAction?.invoke(field.action ?: field.key) }, enabled = enabled, modifier = Modifier.testTag(tag)) {
                Text(field.actionLabel ?: field.label)
            }
            field.description?.let { Markdown(it, style = MaterialTheme.typography.bodySmall) }
        }
        field.type == "url" -> {
            val uri = LocalUriHandler.current
            val url = (field.current as? String) ?: field.helpLink
            TextButton(onClick = { url?.let { runCatching { uri.openUri(it) } } }, enabled = url != null) {
                Icon(XIcons.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text(field.label)
            }
        }
        field.type == "image" || field.type == "icon" -> (field.current as? String)?.let {
            AsyncImage(model = it, contentDescription = field.label, contentScale = ContentScale.Fit, modifier = Modifier.size(160.dp))
        }
        field.type == "boolean" -> SwitchLine(field.label, field.description, value as? Boolean ?: false, enabled, tag) { values[field.key] = it }
        field.options.isNotEmpty() && field.multiValue -> Choices(field, value, enabled, tag) { values[field.key] = it }
        field.options.isNotEmpty() && field.expanded -> Radios(field, value, enabled, tag) { values[field.key] = it }
        field.options.isNotEmpty() -> Dropdown(field, value, error, enabled, tag) { values[field.key] = it }
        (field.type == "integer" || field.type == "float") && field.range != null -> RangeSlider(field, value, enabled, tag) { values[field.key] = it }
        field.type == "secure_string" -> SecretField(field, value, error, enabled, tag) { values[field.key] = it }
        else -> TextValue(field, value, error, enabled, tag) { values[field.key] = it }
    }
}

@Composable
private fun SwitchLine(label: String, description: String?, checked: Boolean, enabled: Boolean, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(checked, enabled = enabled, role = Role.Switch) { onChange(!checked) }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Markdown(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Più scelte insieme (per esempio i generi). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Choices(field: ConfigField, value: Any?, enabled: Boolean, tag: String, onChange: (List<Any?>) -> Unit) {
    val chosen = (value as? List<*>).orEmpty()
    Column(Modifier.testTag(tag)) {
        Text(field.label, style = MaterialTheme.typography.bodyLarge)
        field.description?.let { Markdown(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in field.options) {
                val selected = chosen.any { same(it, option.value) }
                FilterChip(
                    selected = selected,
                    onClick = { onChange(if (selected) chosen.filterNot { same(it, option.value) } else chosen + option.value) },
                    label = { Text(option.title) },
                    enabled = enabled && !option.disabled,
                )
            }
        }
    }
}

/** Una scelta tra poche opzioni, tutte in vista. */
@Composable
private fun Radios(field: ConfigField, value: Any?, enabled: Boolean, tag: String, onChange: (Any?) -> Unit) {
    Column(Modifier.testTag(tag)) {
        Text(field.label, style = MaterialTheme.typography.bodyLarge)
        field.description?.let { Markdown(it, style = MaterialTheme.typography.bodySmall) }
        for (option in field.options) {
            val selected = same(value, option.value)
            Row(
                Modifier.fillMaxWidth().selectable(selected, enabled = enabled && !option.disabled, role = Role.RadioButton) { onChange(option.value) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled && !option.disabled)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(option.title, style = MaterialTheme.typography.bodyMedium)
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** Una scelta da un menu. */
@Composable
private fun Dropdown(field: ConfigField, value: Any?, error: String?, enabled: Boolean, tag: String, onChange: (Any?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = field.options.firstOrNull { same(it.value, value) }?.title ?: value?.toString().orEmpty()
    Box {
        Surface(
            onClick = { open = true },
            enabled = enabled,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag(tag).fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(XIcons.Down, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in field.options) {
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        open = false
                        onChange(option.value)
                    },
                    enabled = !option.disabled,
                    trailingIcon = { if (same(option.value, value)) Icon(XIcons.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
    FieldHelp(field.description, error)
}

/** Un numero in un intervallo. */
@Composable
private fun RangeSlider(field: ConfigField, value: Any?, enabled: Boolean, tag: String, onChange: (Number) -> Unit) {
    val range = field.range ?: return
    val number = (value as? Number)?.toFloat() ?: range.first.toFloat()
    Column(Modifier.testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (field.type == "float") "%.1f".format(number) else number.roundToInt().toString(), style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = number.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(if (field.type == "float") it.toDouble() else it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
        field.description?.let { Markdown(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Una password o un altro segreto. Music Assistant non rimanda quelli salvati: al loro posto c'è
 * [Services.SECRET_KEPT], che si lascia com'è finché non se ne scrive uno nuovo.
 */
@Composable
private fun SecretField(field: ConfigField, value: Any?, error: String?, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val kept = value == Services.SECRET_KEPT
    OutlinedTextField(
        value = if (kept) "" else value as? String ?: "",
        onValueChange = { onChange(if (it.isEmpty() && field.current == Services.SECRET_KEPT) Services.SECRET_KEPT else it) },
        label = { Text(field.label) },
        // Quella salvata non si vede: lo si dice sotto, finché non se ne scrive una nuova.
        supportingText = help(if (kept) stringResource(R.string.secret_saved) else field.description, error),
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) XIcons.Hidden else XIcons.Visible, stringResource(if (visible) R.string.hide_password else R.string.show_password))
            }
        },
        modifier = Modifier.testTag(tag).fillMaxWidth(),
    )
}

/** Un testo, un numero senza intervallo o più testi separati da virgole. */
@Composable
private fun TextValue(field: ConfigField, value: Any?, error: String?, enabled: Boolean, tag: String, onChange: (Any?) -> Unit) {
    val number = field.type == "integer" || field.type == "float"
    var text by remember(field.key) { mutableStateOf(if (value is List<*>) value.joinToString(", ") else value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(
                when {
                    field.multiValue -> it.split(',').map(String::trim).filter(String::isNotEmpty)
                    field.type == "integer" -> it.trim().toLongOrNull() ?: it.ifBlank { null }
                    field.type == "float" -> it.trim().replace(',', '.').toDoubleOrNull() ?: it.ifBlank { null }
                    else -> it
                },
            )
        },
        label = { Text(field.label) },
        placeholder = { field.format?.let { Text(it) } },
        supportingText = help(field.description, error),
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.testTag(tag).fillMaxWidth(),
    )
}

@Composable
private fun help(description: String?, error: String?): (@Composable () -> Unit)? {
    val text = error?.let { errorText(it) } ?: description ?: return null
    return { Text(text) }
}

@Composable
private fun FieldHelp(description: String?, error: String?) {
    when {
        error != null -> Text(errorText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
        description != null -> Markdown(description, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** Gli errori arrivano già tradotti da Music Assistant; se è rimasta la sigla, la si traduce qui. */
@Composable
private fun errorText(error: String): String = when (error) {
    "required" -> stringResource(R.string.field_required)
    "invalid_value" -> stringResource(R.string.field_invalid)
    // Il servizio dei file sul disco, con una cartella che non c'è (o che Xonos non può leggere).
    "music_directory_not_found" -> stringResource(R.string.folder_not_found)
    else -> error
}

@Composable
private fun AdvancedToggle(shown: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(onClick = { onChange(!shown) }, modifier = Modifier.testTag("advanced")) {
        Text(stringResource(if (shown) R.string.hide_advanced else R.string.show_advanced))
    }
}

private fun same(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

// ---------- Pezzi comuni ----------

/** La barra in cima: indietro (o chiudi) e il titolo. */
@Composable
private fun TitleBar(title: String, icon: ImageVector = XIcons.Back, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(icon, stringResource(R.string.back)) }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupTitle(type: String) {
    Text(
        stringResource(
            when (type) {
                "music" -> R.string.service_type_music
                "player" -> R.string.service_type_player
                "plugin" -> R.string.service_type_plugin
                "metadata" -> R.string.service_type_metadata
                else -> R.string.service_type_other
            },
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** Il gruppo di un tipo di servizio: quelli che non hanno un titolo loro finiscono in "Altro". */
private fun group(type: String) = if (type in ServicesModel.TYPES && type != "audio_analysis") type else "other"

@Composable
private fun ServiceRow(model: PhoneModel, domain: String, name: String, line: String?, problem: Boolean, maxLines: Int = 1, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("service").fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceIcon(model, domain, 36)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(XIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** L'icona di un servizio, come la dà Music Assistant (di solito un SVG); finché non c'è, una generica. */
@Composable
private fun ServiceIcon(model: PhoneModel, domain: String, size: Int) {
    LaunchedEffect(domain) { model.services.loadIcon(domain) }
    val icon = model.services.icons[domain]
    val fallback = rememberVectorPainter(XIcons.Services)
    if (icon != null) {
        AsyncImage(model = icon.bytes, contentDescription = null, error = fallback, contentScale = ContentScale.Fit, modifier = Modifier.size(size.dp))
    } else {
        Icon(XIcons.Services, null, Modifier.size(size.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Lo stato di un servizio in una riga: l'errore, se è spento, se si sta avviando. */
@Composable
private fun serviceStatus(service: ServiceConfig): String = when {
    !service.enabled -> stringResource(R.string.service_disabled)
    service.lastError != null -> stringResource(R.string.service_error, service.lastError)
    service.status != null && service.status != "loaded" -> stringResource(R.string.service_starting)
    else -> stringResource(R.string.service_active)
}

/** Sotto il nome di un tipo di servizio: la descrizione, e se è ancora in prova. */
@Composable
private fun kindLine(kind: ServiceKind): String? {
    val stage = when (kind.stage) {
        "beta" -> stringResource(R.string.stage_beta)
        "alpha", "experimental" -> stringResource(R.string.stage_alpha)
        else -> null
    }
    return listOfNotNull(stage, kind.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Note(icon: ImageVector, text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Markdown(text, Modifier.weight(1f), color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}
