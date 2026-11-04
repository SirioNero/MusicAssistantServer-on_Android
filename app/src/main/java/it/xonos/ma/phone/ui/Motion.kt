package it.xonos.ma.phone.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Il movimento dell'interfaccia: tempi e curve in un posto solo. Con le animazioni spente nel sistema
 * ([reducedMotion]) niente scorrimenti né cascate, scheletri fermi, pagine in dissolvenza da 100 ms. Niente animazioni
 * infinite fuori dallo schermo: gli scheletri pulsano solo finché si vedono.
 */
internal object Motion {
    /** La curva enfatizzata di Material 3: parte decisa, arriva piano. */
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Per ciò che entra. */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Per ciò che esce. */
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Una pagina nuova (avanti). */
    const val PAGE_FORWARD_MS = 260

    /** Indietro. */
    const val PAGE_BACK_MS = 180

    /** Da una scheda all'altra: solo dissolvenza. */
    const val TAB_MS = 150

    /** Con le animazioni spente nel sistema: dissolvenza breve. */
    const val REDUCED_MS = 100

    /** Una pagina scorre di 1/8 della larghezza. */
    const val PAGE_SHIFT = 8

    /** Una card premuta si rimpicciolisce fino a qui. */
    const val PRESS_SCALE = 0.96f

    /** La molla della pressione. */
    const val PRESS_DAMPING = 0.8f
    const val PRESS_STIFFNESS = 380f

    /** Gli scheletri: alfa da 0,35 a 0,6 e ritorno in 1,2 s. */
    const val SKELETON_LOW = 0.35f
    const val SKELETON_HIGH = 0.6f
    const val SKELETON_MS = 600

    /** La cascata delle sezioni al primo caricamento: 30 ms l'una, al massimo 6 passi. */
    const val CASCADE_STEP_MS = 30L
    const val CASCADE_STEPS = 6
    const val CASCADE_MS = 220
}

/** Come si passa da una pagina all'altra: avanti nella pila, indietro, un'altra scheda o la stessa pagina rimpiazzata. */
internal enum class PageMove { FORWARD, BACK, TAB, REPLACE }

/**
 * La transizione fra due pagine per AnimatedContent. Avanti: la nuova arriva da destra di 1/8 della larghezza con una
 * dissolvenza, 260 ms con la curva enfatizzata (la vecchia sfuma nel primo terzo, la nuova dopo). Indietro: l'inverso,
 * 180 ms. Schede e pagine rimpiazzate: solo dissolvenza, 150 ms. Animazioni spente: dissolvenza da 100 ms.
 */
internal fun <S> AnimatedContentTransitionScope<S>.pageTransition(move: PageMove, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(Motion.REDUCED_MS)) togetherWith fadeOut(tween(Motion.REDUCED_MS))
    return when (move) {
        PageMove.FORWARD, PageMove.BACK -> {
            val forward = move == PageMove.FORWARD
            val ms = if (forward) Motion.PAGE_FORWARD_MS else Motion.PAGE_BACK_MS
            val out = ms * 35 / 100
            val sign = if (forward) 1 else -1
            (
                slideInHorizontally(tween(ms, easing = Motion.emphasized)) { width -> sign * width / Motion.PAGE_SHIFT } +
                    fadeIn(tween(ms - out, delayMillis = out, easing = Motion.emphasizedDecelerate))
                ) togetherWith (
                slideOutHorizontally(tween(ms, easing = Motion.emphasized)) { width -> -sign * width / Motion.PAGE_SHIFT } +
                    fadeOut(tween(out, easing = Motion.emphasizedAccelerate))
                )
        }
        PageMove.TAB, PageMove.REPLACE -> fadeIn(tween(Motion.TAB_MS)) togetherWith fadeOut(tween(Motion.TAB_MS))
    }
}

/**
 * Una card premuta si rimpicciolisce a 0,96 con una molla (smorzamento 0,8, rigidità 380) e torna quando la si
 * lascia. [interaction] è quello del clickable della card. Con le animazioni spente non si muove.
 */
internal fun Modifier.pressScale(interaction: InteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val scale by animateFloatAsState(
        if (pressed && !still) Motion.PRESS_SCALE else 1f,
        spring(dampingRatio = Motion.PRESS_DAMPING, stiffness = Motion.PRESS_STIFFNESS),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * L'entrata a cascata di una sezione: sale di 16 dp e compare, [index] × 30 ms dopo la prima (dalla sesta in poi
 * insieme). Solo se [play]: chi chiama lo accende al primo caricamento del processo (vedi [CascadeOnce]), sennò le
 * sezioni comparirebbero di nuovo a ogni scorrimento. Con le animazioni spente niente cascata.
 *
 * Finita, non si rifà: una sezione che esce dallo schermo e torna (la LazyColumn la toglie e la ricompone) ritrova il
 * suo "fatto" con rememberSaveable, che la LazyColumn tiene per ogni voce anche fuori dallo schermo.
 */
internal fun Modifier.cascadeIn(index: Int, play: Boolean): Modifier = composed {
    if (!play || reducedMotion()) return@composed Modifier
    var done by rememberSaveable { mutableStateOf(false) }
    if (done) return@composed Modifier
    val progress = remember { Animatable(0f) }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(Motion.CASCADE_STEP_MS * min(index, Motion.CASCADE_STEPS - 1))
        progress.animateTo(1f, tween(Motion.CASCADE_MS, easing = Motion.emphasizedDecelerate))
        done = true
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

/**
 * Una cascata per processo: [take] dà vero la prima volta per ogni [key] e poi sempre falso. La Home la chiede una
 * volta (`remember { CascadeOnce.take("home") }`): le sezioni entrano a cascata solo all'apertura dell'app.
 */
internal object CascadeOnce {
    private val taken = HashSet<String>()

    fun take(key: String): Boolean = synchronized(taken) { taken.add(key) }
}

/**
 * Un posto vuoto che pulsa mentre si carica, al posto della rotella: alfa da 0,35 a 0,6 in 1,2 s, fermo a metà con le
 * animazioni spente. [shape] di solito quella della copertina che arriverà.
 */
@Composable
internal fun Skeleton(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reducedMotion()) {
        Box(modifier.graphicsLayer { alpha = (Motion.SKELETON_LOW + Motion.SKELETON_HIGH) / 2 }.clip(shape).background(color))
        return
    }
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        Motion.SKELETON_LOW,
        Motion.SKELETON_HIGH,
        infiniteRepeatable(tween(Motion.SKELETON_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeleton",
    )
    // L'alfa si legge mentre si disegna: lo scheletro pulsa senza ricomporsi.
    Box(modifier.graphicsLayer { alpha = pulse.value }.clip(shape).background(color))
}
