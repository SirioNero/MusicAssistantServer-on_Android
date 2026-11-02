package it.xonos.ma.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap

// La versione per Compose Desktop del Platform.kt dell'app (banco di prova delle schermate).

@Composable
internal fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
internal fun PlatformReportReady(ready: () -> Boolean) = Unit

@Composable
internal fun KeepScreenOn(on: Boolean) = Unit

@Composable
internal fun rememberCopy(): (String) -> Unit = remember { { _ -> } }

internal fun Modifier.automationIds(): Modifier = this

@Composable
internal fun rememberArtworkPixels(url: String?): IntArray? {
    val context = LocalPlatformContext.current
    var pixels by remember(url) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(32).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        pixels = result.image.toBitmap().asComposeImageBitmap().toPixelMap().buffer
    }
    return pixels
}
