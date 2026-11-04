package it.xonos.ma.phone.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I colori presi da una copertina, come ARGB (Kotlin puro: lo provano il banco di prova e tools/screens). Tutti nella
 * tinta che domina la copertina, tranne [secondo]:
 * - [profondo]: fondo di eroe, testate, lettore (testo bianco sopra almeno 10:1);
 * - [contenitore]: mini-lettore, chip;
 * - [vivo]: play, barre, slider, brano in corso (almeno 4,5:1 sul fondo dell'app e su [profondo]);
 * - [suVivo]: icone e testo sui tasti [vivo] (almeno 4,5:1);
 * - [alone]: l'alone della testata e del lettore, già con l'alfa 0,5;
 * - [secondo]: la seconda tinta della copertina, per le sfumature.
 * [grey] è vero se la copertina è grigia (o non ha colori abbastanza): allora è l'ambra di Xonos a croma bassa.
 */
data class ArtPalette(
    val profondo: Int,
    val contenitore: Int,
    val vivo: Int,
    val suVivo: Int,
    val alone: Int,
    val secondo: Int,
    val grey: Boolean,
) {
    companion object {
        /** Il fondo dell'app (background del tema), su cui [vivo] deve leggersi. */
        const val BACKGROUND = 0xFF0D0E12.toInt()

        /** L'ambra del marchio: dove non c'è una copertina (Impostazioni, tasti, menu dell'auto). */
        const val AMBER = 0xFFFFC27A.toInt()

        /** La palette senza copertina: l'ambra di Xonos, coi colori del tema di sempre per play e la sua icona. */
        val BRAND: ArtPalette by lazy {
            val hue = Oklch.of(AMBER).h
            ArtPalette(
                profondo = Roles.profondo(hue, 0.03),
                contenitore = Roles.contenitore(hue, 0.035),
                vivo = AMBER,
                suVivo = 0xFF3A2300.toInt(),
                alone = Roles.alone(hue, 0.10),
                secondo = Roles.secondo(hue + 40, 0.10),
                grey = true,
            )
        }

        /** La palette di una copertina grigia: l'ambra a croma bassa. */
        val GREY: ArtPalette by lazy { Roles.palette(Oklch.of(AMBER).h, GREY_CHROMA, Oklch.of(AMBER).h + 40, GREY_CHROMA, grey = true) }

        /**
         * La palette dai pixel di una copertina rimpicciolita (ARGB, di solito 48×48). I pixel si portano in OKLCH e
         * si contano in 24 spicchi di tinta, ognuno pesato per popolazione × (C + 0,03)^1,5; i grigi (C < 0,03) stanno
         * a parte. Vince lo spicchio più pesante fra quelli con almeno il 3% dei pixel; la seconda tinta deve stare ad
         * almeno 40° dalla prima (se non c'è, la prima girata di 40°). Stessi pixel, stessa palette.
         */
        fun from(pixels: IntArray): ArtPalette {
            val weight = DoubleArray(BINS)
            val count = IntArray(BINS)
            val sumA = DoubleArray(BINS)
            val sumB = DoubleArray(BINS)
            val sumC = DoubleArray(BINS)
            var opaque = 0
            val lab = DoubleArray(3)
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) continue
                opaque++
                Oklab.fromArgb(pixel, lab)
                val a = lab[1]
                val b = lab[2]
                val c = sqrt(a * a + b * b)
                if (c < GREY_LIMIT) continue
                var h = Math.toDegrees(atan2(b, a))
                if (h < 0) h += 360.0
                val bin = min(BINS - 1, (h / BIN_DEGREES).toInt())
                val w = (c + 0.03).pow(1.5)
                weight[bin] += w
                count[bin]++
                // La tinta media dello spicchio: media dei vettori (a, b) normalizzati, pesati come lo spicchio.
                sumA[bin] += w * a / c
                sumB[bin] += w * b / c
                sumC[bin] += w * c
            }
            if (opaque == 0) return GREY
            // Almeno il 3% dei pixel della copertina (grigi compresi).
            val enough = max(1, ceil(opaque * MIN_SHARE).toInt())
            val first = (0 until BINS).filter { count[it] >= enough }.maxByOrNull { weight[it] } ?: return GREY
            val hue1 = hueOf(sumA[first], sumB[first])
            val chroma1 = sumC[first] / weight[first]
            val second = (0 until BINS)
                .filter { it != first && count[it] >= enough && distance(hueOf(sumA[it], sumB[it]), hue1) >= SECOND_DEGREES }
                .maxByOrNull { weight[it] }
            val hue2 = second?.let { hueOf(sumA[it], sumB[it]) } ?: (hue1 + SECOND_DEGREES)
            val chroma2 = second?.let { sumC[it] / weight[it] } ?: chroma1
            return Roles.palette(hue1, chroma1, hue2, chroma2, grey = false)
        }

        /** Il rapporto di contrasto WCAG fra due colori opachi (da 1 a 21). */
        fun contrast(first: Int, second: Int): Double {
            val l1 = luminance(first)
            val l2 = luminance(second)
            return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
        }

        /** La luminanza relativa WCAG di un colore (l'alfa non conta). */
        fun luminance(argb: Int): Double {
            val r = Oklab.linear((argb shr 16) and 0xFF)
            val g = Oklab.linear((argb shr 8) and 0xFF)
            val b = Oklab.linear(argb and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        private fun hueOf(a: Double, b: Double): Double {
            val h = Math.toDegrees(atan2(b, a))
            return if (h < 0) h + 360.0 else h
        }

        /** La distanza fra due tinte sul cerchio, da 0 a 180°. */
        private fun distance(h1: Double, h2: Double): Double {
            val d = abs(h1 - h2) % 360.0
            return if (d > 180) 360 - d else d
        }

        private const val BINS = 24
        private const val BIN_DEGREES = 360.0 / BINS
        private const val GREY_LIMIT = 0.03
        private const val MIN_SHARE = 0.03
        private const val SECOND_DEGREES = 40.0
        private const val GREY_CHROMA = 0.035
    }
}

/**
 * Le palette per indirizzo della copertina (al massimo [SIZE]): riaprendo il lettore o tornando su una schermata i
 * colori non partono di nuovo dal grigio.
 */
object ArtPalettes {
    private const val SIZE = 64

    private val cache = object : LinkedHashMap<String, ArtPalette>(SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtPalette>?) = size > SIZE
    }

    fun cached(url: String): ArtPalette? = synchronized(cache) { cache[url] }

    /** La palette di [url] dai suoi pixel, calcolata una volta sola. */
    fun of(url: String, pixels: IntArray): ArtPalette {
        cached(url)?.let { return it }
        val palette = ArtPalette.from(pixels)
        synchronized(cache) { cache[url] = palette }
        return palette
    }

    /** Quante palette sono in memoria (per le prove). */
    val size: Int get() = synchronized(cache) { cache.size }
}

/** I toni dei ruoli: luminosità fissa, croma presa dalla copertina ma tenuta nei limiti, contrasti controllati. */
internal object Roles {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun palette(hue: Double, chroma: Double, hue2: Double, chroma2: Double, grey: Boolean): ArtPalette {
        val profondo = profondo(hue, chroma)
        val vivo = vivo(hue, chroma, profondo)
        return ArtPalette(
            profondo = profondo,
            contenitore = contenitore(hue, chroma),
            vivo = vivo,
            suVivo = suVivo(hue, chroma, vivo),
            alone = alone(hue, if (grey) chroma else chroma.coerceIn(0.14, 0.20)),
            secondo = secondo(hue2, chroma2),
            grey = grey,
        )
    }

    /** L 0,25, C fino a 0,09; se il bianco sopra non arriva a 10:1 si scurisce. */
    fun profondo(hue: Double, chroma: Double): Int {
        var l = 0.25
        var color = Oklch(l, min(chroma, 0.09), hue).toArgb()
        while (ArtPalette.contrast(WHITE, color) < 10.0 && l > 0.05) {
            l -= 0.01
            color = Oklch(l, min(chroma, 0.09), hue).toArgb()
        }
        return color
    }

    /** L 0,34, C fino a 0,10; il bianco sopra almeno 7:1 (titolo e artista del mini-lettore). */
    fun contenitore(hue: Double, chroma: Double): Int {
        var l = 0.34
        var color = Oklch(l, min(chroma, 0.10), hue).toArgb()
        while (ArtPalette.contrast(WHITE, color) < 7.0 && l > 0.05) {
            l -= 0.01
            color = Oklch(l, min(chroma, 0.10), hue).toArgb()
        }
        return color
    }

    /** L 0,78, C da 0,10 a 0,17; si schiarisce finché non si legge sul fondo dell'app e su [profondo] (4,5:1). */
    fun vivo(hue: Double, chroma: Double, profondo: Int): Int {
        val c = chroma.coerceIn(0.10, 0.17)
        var l = 0.78
        var color = Oklch(l, c, hue).toArgb()
        while ((ArtPalette.contrast(color, ArtPalette.BACKGROUND) < 4.5 || ArtPalette.contrast(color, profondo) < 4.5) && l < 0.98) {
            l += 0.01
            color = Oklch(l, c, hue).toArgb()
        }
        return color
    }

    /**
     * Fra L 0,20 nella stessa tinta e il nero, quello col contrasto più alto su [vivo] (come dice il piano; il nero vince
     * quasi sempre). Tutti e due superano 4,5:1, perché [vivo] si legge già sul fondo quasi nero.
     */
    fun suVivo(hue: Double, chroma: Double, vivo: Int): Int {
        val dark = Oklch(0.20, min(chroma, 0.06), hue).toArgb()
        return if (ArtPalette.contrast(dark, vivo) > ArtPalette.contrast(BLACK, vivo)) dark else BLACK
    }

    /** L 0,55 con l'alfa 0,5: decorativo, nessun testo ci sta sopra da solo. */
    fun alone(hue: Double, chroma: Double): Int = (Oklch(0.55, chroma, hue).toArgb() and 0x00FFFFFF) or (0x80 shl 24)

    /** La seconda tinta a L 0,68, C da 0,10 a 0,17: con [vivo] fa la sfumatura delle copertine dei mix. */
    fun secondo(hue: Double, chroma: Double): Int = Oklch(0.68, chroma.coerceIn(0.10, 0.17), hue).toArgb()
}

/** Un colore OKLCH: luminosità 0-1, croma, tinta in gradi. */
internal data class Oklch(val l: Double, val c: Double, val h: Double) {
    /**
     * Il colore sRGB più vicino: se il tono esce dalla gamma si riduce la croma (ricerca binaria), tinta e luminosità
     * restano.
     */
    fun toArgb(): Int {
        val rad = Math.toRadians(((h % 360.0) + 360.0) % 360.0)
        val ca = cos(rad)
        val sa = sin(rad)
        val rgb = DoubleArray(3)
        if (Oklab.toLinear(l, c * ca, c * sa, rgb)) return Oklab.argb(rgb)
        var low = 0.0
        var high = c
        repeat(16) {
            val mid = (low + high) / 2
            if (Oklab.toLinear(l, mid * ca, mid * sa, rgb)) low = mid else high = mid
        }
        Oklab.toLinear(l, low * ca, low * sa, rgb)
        return Oklab.argb(rgb)
    }

    companion object {
        fun of(argb: Int): Oklch {
            val lab = DoubleArray(3)
            Oklab.fromArgb(argb, lab)
            var h = Math.toDegrees(atan2(lab[2], lab[1]))
            if (h < 0) h += 360.0
            return Oklch(lab[0], sqrt(lab[1] * lab[1] + lab[2] * lab[2]), h)
        }
    }
}

/** Le conversioni fra sRGB e OKLab (Björn Ottosson, 2020). */
internal object Oklab {
    /** sRGB a 8 bit → lineare, già calcolato. */
    private val LINEAR = DoubleArray(256) { i ->
        val c = i / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun linear(channel: Int): Double = LINEAR[channel]

    /** [out] = L, a, b del colore. */
    fun fromArgb(argb: Int, out: DoubleArray) {
        val r = LINEAR[(argb shr 16) and 0xFF]
        val g = LINEAR[(argb shr 8) and 0xFF]
        val b = LINEAR[argb and 0xFF]
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    }

    /** RGB lineare in [out]; falso se esce dalla gamma sRGB. */
    fun toLinear(l: Double, a: Double, b: Double, out: DoubleArray): Boolean {
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        out[0] = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        out[1] = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        out[2] = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        val eps = 1e-6
        return out.all { it >= -eps && it <= 1 + eps }
    }

    /** Il colore opaco a 8 bit da un RGB lineare (già nella gamma). */
    fun argb(rgb: DoubleArray): Int {
        var color = 0xFF shl 24
        for (i in 0..2) {
            val c = rgb[i].coerceIn(0.0, 1.0)
            val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
            color = color or ((s * 255 + 0.5).toInt().coerceIn(0, 255) shl (16 - 8 * i))
        }
        return color
    }
}
