package it.xonos.ma.phone.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import it.xonos.ma.R

// Le poche cose dello schermo che dipendono da Android. Il banco di prova (tools/screens) ha la
// sua versione di questo file per Compose Desktop.

/** Il tasto (o il gesto) indietro di Android. */
@Composable
internal fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/**
 * Dice ad Android che la prima schermata è completa (con i dati, non solo disegnata): è il
 * "tempo fino alla schermata piena" che misurano le prove di avvio.
 */
@Composable
internal fun PlatformReportReady(ready: () -> Boolean) = ReportDrawnWhen(ready)

/**
 * Lo schermo resta acceso finché [on]: a schermo spento Android congela l'app (e il server sul telefono, suo figlio), e
 * download, estrazione e primo avvio si fermavano senza dirlo.
 */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Copia un testo negli appunti di Android. */
@Composable
internal fun rememberCopy(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Xonos", text)) }
    }
}

/** I testTag diventano gli id che vedono le prove automatiche (UiAutomator, nel modulo baselineprofile). */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.automationIds(): Modifier = semantics { testTagsAsResourceId = true }

/**
 * Manrope (SIL OFL 1.1, licenza in assets/licenses/OFL-Manrope.txt), solo 600 e 800: i file di res/font li fa
 * tools/font/make_font.py. Nel banco di prova (tools/screens) gli stessi file.
 */
internal val Manrope = FontFamily(
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/**
 * Vero se nel sistema le animazioni sono spente (opzioni sviluppatore, o "Rimuovi animazioni" in Accessibilità):
 * niente cascata, scheletri fermi, pagine in dissolvenza breve.
 */
@Composable
internal fun reducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

/** I pixel di una copertina rimpicciolita (48×48), per prenderne i colori; null finché non ci sono. */
@Composable
internal fun rememberArtworkPixels(url: String?): IntArray? {
    val context = LocalPlatformContext.current
    var pixels by remember(url) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        // Le bitmap "hardware" non si possono leggere: per questa serve una copia normale.
        val request = ImageRequest.Builder(context).data(url).size(SAMPLE_SIZE).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = result.image.toBitmap()
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels = out
    }
    return pixels
}

private const val SAMPLE_SIZE = 48
