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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.io.File

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

/** Manrope dagli stessi file dell'app (res/font), come sul telefono. */
internal val Manrope: FontFamily by lazy {
    val fonts = File(System.getProperty("xonos.res") ?: "../../app/src/main/res", "font")
    FontFamily(
        Font(File(fonts, "manrope_semibold.ttf"), FontWeight.SemiBold),
        Font(File(fonts, "manrope_extrabold.ttf"), FontWeight.ExtraBold),
    )
}

/** Le animazioni spente come nel sistema: -Dxonos.reducedMotion=true. */
@Composable
internal fun reducedMotion(): Boolean = remember { System.getProperty("xonos.reducedMotion") == "true" }

@Composable
internal fun rememberArtworkPixels(url: String?): IntArray? {
    val context = LocalPlatformContext.current
    var pixels by remember(url) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(48).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        pixels = result.image.toBitmap().asComposeImageBitmap().toPixelMap().buffer
    }
    return pixels
}
