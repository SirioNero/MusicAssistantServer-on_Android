package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.value

/** La prima scheda: gli ascolti recenti e le righe "Per te" di Music Assistant; trascinando in giù si aggiorna. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(page: HomePage, model: PhoneModel, host: Host) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pull = rememberPullToRefreshState()
    // La Home è pronta quando si sa cosa mostrare: gli ascolti recenti e le righe di "Per te".
    PlatformReportReady { page.recent !is Load.Loading && page.rows !is Load.Loading }
    PullToRefreshBox(
        isRefreshing = page.refreshing,
        onRefresh = { model.refresh(page) },
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(pull, page.refreshing, Modifier.align(Alignment.TopCenter).padding(top = top))
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(top = top, bottom = 24.dp)) {
            item(key = "header", contentType = "header") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = model::openDevices, modifier = Modifier.testTag("home_play_on")) { Icon(XIcons.Cast, stringResource(R.string.play_on)) }
                    IconButton(onClick = model::openSettings) { Icon(XIcons.Settings, stringResource(R.string.settings)) }
                }
                ConnectionBanner(model, host)
            }
            item(key = "recent", contentType = "row") {
                when (val recent = page.recent) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is Load.Failed -> Problem(host.describe(recent.error), { model.reload(page) })
                    is Load.Ready -> if (recent.value.isNotEmpty()) {
                        CardRow(stringResource(R.string.cat_recent), recent.value, model) { _, item -> model.open(item) }
                    }
                }
            }
            // Le righe vuote (o non riuscite) non si mostrano: sono solo consigli.
            val rows = page.rows.value.orEmpty()
            items(rows, key = { "${it.row.provider}|${it.row.itemId}" }, contentType = { "row" }) { row ->
                val items = row.items.value
                if (!items.isNullOrEmpty()) {
                    CardRow(row.row.name, items, model, row.row.subtitle, { model.openRow(row) }) { _, item -> model.open(item) }
                }
            }
            if (empty(page)) item(key = "empty", contentType = "empty") { EmptyHome(model) }
        }
    }
}

/**
 * Una riga sotto il titolo quando Music Assistant non risponde: il motivo, o che ci si sta collegando. Se è quello
 * sul telefono e Xonos è stato aggiornato senza il suo pacchetto nuovo, lo dice, con il tasto per aggiornarlo.
 */
@Composable
internal fun ConnectionBanner(model: PhoneModel, host: Host) {
    val connection by model.connection.collectAsState()
    // Il server sul telefono parte solo con il pacchetto della versione di Xonos: finché non lo si scarica, niente.
    val serverOld = connection !is Connection.Connected && model.onPhoneServer && model.phoneServerOutdated
    // Mentre il server sul telefono parte la connessione non riesce: non è un errore di rete, è l'avvio.
    val starting = connection !is Connection.Connected && !serverOld && model.phoneServerStarting
    val text = when {
        serverOld -> stringResource(R.string.phone_server_outdated)
        connection is Connection.Connected -> return
        starting -> stringResource(R.string.phone_server_starting)
        connection is Connection.Connecting -> stringResource(R.string.connecting)
        else -> host.describe(connection) ?: return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if ((connection is Connection.Connecting || starting) && !serverOld) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(XIcons.Offline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (serverOld) {
                TextButton(onClick = { model.openSettings() }, modifier = Modifier.align(Alignment.End).testTag("update_phone_server")) {
                    Text(stringResource(R.string.phone_server_update))
                }
            }
        }
    }
}

/** Niente da mostrare: né ascolti né consigli (un Music Assistant appena installato). */
private fun empty(page: HomePage): Boolean {
    val recent = page.recent.value ?: return false
    val rows = page.rows.value ?: return false
    return recent.isEmpty() && rows.all { it.items is Load.Failed || it.items.value?.isEmpty() == true }
}

/** La Home vuota: chi amministra Music Assistant può aggiungere subito un servizio. */
@Composable
private fun EmptyHome(model: PhoneModel) {
    val admin = rememberAdmin(model)
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(XIcons.Services, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (admin) R.string.home_empty_admin else R.string.home_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (admin) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { model.services.open() }, modifier = Modifier.testTag("home_services")) { Text(stringResource(R.string.services_add)) }
        }
    }
}
