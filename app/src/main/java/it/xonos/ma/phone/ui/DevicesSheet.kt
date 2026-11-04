package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.Device
import it.xonos.ma.phone.model.DevicePicker
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.RemoteLink

/**
 * "Suona su": il telefono e i lettori di Music Assistant (casse, TV, gruppi). Scegliendone uno la
 * coda passa lì e riparte dallo stesso punto; il telefono smette di suonare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesSheet(picker: DevicePicker, model: PhoneModel, host: Host) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = model::closeDevices, sheetState = state) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.play_on), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            val current = model.remote?.device?.id
            DeviceRow(
                XIcons.Phone,
                stringResource(R.string.this_phone),
                model.playerName,
                selected = current == null,
                moving = picker.moving == PhoneModel.PHONE,
                enabled = picker.moving == null,
                tag = "device_phone",
            ) { model.playOn(null) }
            when (val devices = picker.devices) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Problem(host.describe(devices.error), { model.loadDevices(picker) })
                is Load.Ready -> {
                    if (devices.value.isEmpty()) {
                        Text(
                            stringResource(R.string.devices_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    for (device in devices.value) {
                        DeviceRow(
                            if (device.type == "group") XIcons.Cast else XIcons.Speaker,
                            device.name,
                            if (device.playing) stringResource(R.string.device_playing) else null,
                            selected = current == device.id,
                            moving = picker.moving == device.id,
                            enabled = picker.moving == null,
                            tag = "device",
                        ) { model.playOn(device) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    name: String,
    line: String?,
    selected: Boolean,
    moving: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag(tag).fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                line?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                moving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                selected -> Icon(XIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Dove suona adesso, da toccare per cambiare: il telefono o il lettore scelto. */
@Composable
internal fun PlayOnButton(model: PhoneModel, modifier: Modifier = Modifier) {
    val remote = model.remote
    Surface(
        onClick = model::openDevices,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = if (remote != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("play_on"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (remote != null) XIcons.Speaker else XIcons.Phone, null, Modifier.size(18.dp))
            Text(
                remote?.device?.name ?: stringResource(R.string.this_phone),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Il volume del lettore scelto (quello del telefono si regola con i suoi tasti). */
@Composable
internal fun RemoteVolume(remote: RemoteLink) {
    val volume by remote.volume.collectAsState()
    val level = volume ?: return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(XIcons.Speaker, stringResource(R.string.volume), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.width(12.dp))
        Slider(
            value = dragging ?: (level / 100f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { remote.setVolume((it * 100).toInt()) }
                dragging = null
            },
            // Sul fondo del lettore (il «profondo» della copertina, che fa anche da secondaryContainer) la parte vuota
            // di base sparirebbe: come la barra del brano.
            colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            modifier = Modifier.weight(1f).testTag("remote_volume"),
        )
    }
}

/** Con un lettore scelto e niente in coda: dove suonerà, e il tocco per cambiarlo. */
@Composable
internal fun RemoteBar(device: Device, model: PhoneModel) {
    Surface(
        onClick = model::openDevices,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("remote_bar").fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.Speaker, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.remote_empty, device.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(XIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
