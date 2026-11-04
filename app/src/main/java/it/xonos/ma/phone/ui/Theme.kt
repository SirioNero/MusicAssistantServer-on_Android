package it.xonos.ma.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * I colori di Xonos: una base neutra quasi nera (le copertine non si scontrano col fondo) e l'ambra del marchio dove
 * non c'è una copertina (Impostazioni, tasti, icona, menu in auto). Dove c'è una copertina il colore lo dà lei
 * ([ArtTheme]). Sempre scuri: l'app si usa anche di sera in macchina.
 */
internal val XonosColors = darkColorScheme(
    primary = Color(ArtPalette.AMBER),
    onPrimary = Color(0xFF3A2300),
    primaryContainer = Color(0xFF5A3C0C),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFFC6C7D2),
    onSecondary = Color(0xFF2D2E36),
    // Non uguale a surfaceContainerHighest: contentColorFor guarda secondaryContainer prima e darebbe il suo colore
    // del testo a ogni superficie surfaceContainerHighest.
    secondaryContainer = Color(0xFF2E2F37),
    onSecondaryContainer = Color(0xFFE3E3EC),
    tertiary = Color(0xFF8FD5C6),
    onTertiary = Color(0xFF00382F),
    background = Color(ArtPalette.BACKGROUND),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFEDEDF2),
    // Un soffio diverso da surfaceContainerHigh: contentColorFor guarda surfaceVariant prima, e con lo stesso colore
    // il testo di ogni scheda surfaceContainerHigh (Impostazioni, fogli, moduli) diventava grigio (onSurfaceVariant).
    surfaceVariant = Color(0xFF24252C),
    onSurfaceVariant = Color(0xFFA9AAB6),
    surfaceTint = Color(ArtPalette.AMBER),
    surfaceContainerLowest = Color(0xFF0A0B0E),
    surfaceContainerLow = Color(0xFF15161B),
    surfaceContainer = Color(0xFF1A1B21),
    surfaceContainerHigh = Color(0xFF23242B),
    surfaceContainerHighest = Color(0xFF2D2E36),
    surfaceBright = Color(0xFF34353E),
    surfaceDim = Color(ArtPalette.BACKGROUND),
    inverseSurface = Color(0xFFEDEDF2),
    inverseOnSurface = Color(0xFF23242B),
    inversePrimary = Color(0xFF7A5200),
    outline = Color(0xFF3A3B45),
    outlineVariant = Color(0xFF2A2B33),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/**
 * La scala dei testi: titoli in Manrope (solo 600 e 800, vedi [Manrope] in Platform.kt), corpo ed etichette col
 * carattere di sistema, più leggibile in piccolo (e il cinese ripiega da sé).
 */
private val XonosType = Typography().let { base ->
    fun TextStyle.manrope(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = copy(
        fontFamily = Manrope,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = spacing.em,
    )
    base.copy(
        displayLarge = base.displayLarge.manrope(57, 64, FontWeight.ExtraBold, -0.02),
        displayMedium = base.displayMedium.manrope(45, 52, FontWeight.ExtraBold, -0.02),
        displaySmall = base.displaySmall.manrope(36, 44, FontWeight.ExtraBold, -0.01),
        // Il saluto della Home.
        headlineLarge = base.headlineLarge.manrope(32, 38, FontWeight.ExtraBold, -0.01),
        // I titoli di pagina.
        headlineMedium = base.headlineMedium.manrope(28, 34, FontWeight.ExtraBold, -0.01),
        // Testate e titolo del lettore.
        headlineSmall = base.headlineSmall.manrope(24, 30, FontWeight.ExtraBold),
        // Le sezioni.
        titleLarge = base.titleLarge.manrope(20, 26, FontWeight.ExtraBold),
        // Le card.
        titleMedium = base.titleMedium.manrope(16, 22, FontWeight.SemiBold),
        // Le tessere.
        titleSmall = base.titleSmall.manrope(14, 20, FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

/** Gli stili che Material 3 non ha. */
internal object XonosStyles {
    /** L'occhiello sopra un titolo ("RIPRENDI · 2 ORE FA"): 11/16, 600, maiuscolo (lo fa [Eyebrow]), +6% di spaziatura. */
    val eyebrow = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.06.em)

    /** Cifre tutte larghe uguali: tempi e durate che cambiano non fanno ballare il testo. */
    const val TABULAR = "tnum"
}

/** Le cifre tabellari su uno stile (tempi, durate). */
internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = XonosStyles.TABULAR)

/**
 * Le forme: extraSmall 6, small 10, medium 14, large 20, extraLarge 28. Le copertine hanno le loro (vedi coverShape in
 * Components.kt).
 */
private val XonosShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun XonosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XonosColors, typography = XonosType, shapes = XonosShapes) {
        CompositionLocalProvider(LocalArt provides BRAND_COLORS, content = content)
    }
}

/** I colori di una copertina come li usa lo schermo, già animati (vedi [ArtPalette] per i ruoli). */
@Immutable
data class ArtColors(
    val profondo: Color,
    val contenitore: Color,
    val vivo: Color,
    val suVivo: Color,
    /** Già con l'alfa 0,5: va disegnato sopra [profondo] o sul fondo. */
    val alone: Color,
    val secondo: Color,
) {
    companion object {
        fun of(palette: ArtPalette) = ArtColors(
            Color(palette.profondo),
            Color(palette.contenitore),
            Color(palette.vivo),
            Color(palette.suVivo),
            Color(palette.alone),
            Color(palette.secondo),
        )
    }
}

private val BRAND_COLORS = ArtColors.of(ArtPalette.BRAND)

/** I colori della copertina dentro un [ArtTheme]; fuori, quelli del marchio (l'ambra). */
val LocalArt = compositionLocalOf { BRAND_COLORS }

/** Quanto ci mettono i colori a passare da una copertina all'altra. */
private const val ART_FADE_MS = 600

/**
 * La palette della copertina [url]: dalla cache se c'è già (così riaprendo i colori non saltano), sennò dai suoi pixel
 * appena arrivano. Null finché non si sa, o senza copertina. Solo per eroe, mix, testate, mini-lettore e lettore: le
 * card normali non la calcolano.
 */
@Composable
fun rememberArtPalette(url: String?): ArtPalette? {
    if (url == null) return null
    val cached = remember(url) { ArtPalettes.cached(url) }
    if (cached != null) return cached
    val pixels = rememberArtworkPixels(url) ?: return null
    return remember(url, pixels) { ArtPalettes.of(url, pixels) }
}

/**
 * Dentro questo blocco primary, onPrimary, primaryContainer e secondaryContainer vengono dalla copertina (vivo,
 * suVivo, contenitore, profondo) e [LocalArt] dà tutti i suoi colori; senza palette quelli del marchio. Il passaggio
 * da una copertina all'altra dura 600 ms.
 */
@Composable
fun ArtTheme(palette: ArtPalette?, content: @Composable () -> Unit) {
    val target = palette ?: ArtPalette.BRAND
    val spec = tween<Color>(ART_FADE_MS)
    val profondo = animateColorAsState(Color(target.profondo), spec, label = "profondo").value
    val contenitore = animateColorAsState(Color(target.contenitore), spec, label = "contenitore").value
    val vivo = animateColorAsState(Color(target.vivo), spec, label = "vivo").value
    val suVivo = animateColorAsState(Color(target.suVivo), spec, label = "suVivo").value
    val alone = animateColorAsState(Color(target.alone), spec, label = "alone").value
    val secondo = animateColorAsState(Color(target.secondo), spec, label = "secondo").value
    val colors = ArtColors(profondo, contenitore, vivo, suVivo, alone, secondo)
    val scheme = MaterialTheme.colorScheme.copy(
        primary = vivo,
        onPrimary = suVivo,
        primaryContainer = contenitore,
        secondaryContainer = profondo,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalArt provides colors, content = content)
    }
}
