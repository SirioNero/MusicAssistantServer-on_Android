package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.ui.ArtPalette
import it.xonos.ma.phone.ui.ArtPalettes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * I colori presi dalle copertine ([ArtPalette], phone/ui/ArtPalette.kt), senza Android.
 *
 * - 2000 copertine di un colore solo, scelto a caso (seme fisso), e 2000 copertine di due colori: i contrasti tengono
 *   sempre (bianco su profondo ≥ 10:1, vivo sul fondo e su profondo ≥ 4,5:1, suVivo su vivo ≥ 4,5:1, bianco sul
 *   contenitore ≥ 7:1), l'alone ha l'alfa 0,5.
 * - Copertine finte: rosso vivo, pastello, grigia, bianca, nera, trasparente, una con poco colore (2% e 5%), due tinte.
 *   Le grigie ripiegano sull'ambra a croma bassa; un colore vivo dà la sua tinta.
 * - Stessi pixel, stessa palette; meno di 1 ms per copertina (48×48) dopo il riscaldamento.
 * - La cache tiene al massimo 64 indirizzi.
 * - Con MA_TOKEN anche le copertine vere del Music Assistant di prova (a 80 px, ridotte a 48 come sul telefono).
 */
fun main(): Unit = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    val white = 0xFFFFFFFF.toInt()
    /** I contrasti di una palette; vuoto se vanno tutti. */
    fun problems(p: ArtPalette): List<String> = buildList {
        val profondo = ArtPalette.contrast(white, p.profondo)
        val vivoBg = ArtPalette.contrast(p.vivo, ArtPalette.BACKGROUND)
        val vivoDeep = ArtPalette.contrast(p.vivo, p.profondo)
        val onVivo = ArtPalette.contrast(p.suVivo, p.vivo)
        val box = ArtPalette.contrast(white, p.contenitore)
        if (profondo < 10) add("bianco su profondo %.2f".format(profondo))
        if (vivoBg < 4.5) add("vivo sul fondo %.2f".format(vivoBg))
        if (vivoDeep < 4.5) add("vivo su profondo %.2f".format(vivoDeep))
        if (onVivo < 4.5) add("suVivo su vivo %.2f".format(onVivo))
        if (box < 7) add("bianco sul contenitore %.2f".format(box))
        if ((p.alone ushr 24) != 0x80) add("alfa dell'alone ${p.alone ushr 24}")
        for ((name, color) in listOf("profondo" to p.profondo, "contenitore" to p.contenitore, "vivo" to p.vivo, "suVivo" to p.suVivo, "secondo" to p.secondo)) {
            if ((color ushr 24) != 0xFF) add("$name non opaco")
        }
    }

    // ---------- Colori a caso ----------
    val random = Random(58)
    var worst = Worst()
    var bad = 0
    var firstBad = ""
    repeat(2000) {
        val color = 0xFF000000.toInt() or random.nextInt(0x1000000)
        val palette = ArtPalette.from(IntArray(48 * 48) { color })
        worst = worst.add(palette)
        val p = problems(palette)
        if (p.isNotEmpty()) {
            bad++
            if (firstBad.isEmpty()) firstBad = "#%06X: %s".format(color and 0xFFFFFF, p.joinToString())
        }
    }
    check("2000 copertine di un colore a caso: contrasti sempre giusti", bad == 0, if (bad == 0) worst.toString() else "$bad sbagliate, per esempio $firstBad")

    bad = 0
    firstBad = ""
    worst = Worst()
    repeat(2000) {
        val a = 0xFF000000.toInt() or random.nextInt(0x1000000)
        val b = 0xFF000000.toInt() or random.nextInt(0x1000000)
        val share = random.nextInt(5, 95)
        val pixels = IntArray(48 * 48) { i -> if (i * 100 / (48 * 48) < share) a else b }
        val palette = ArtPalette.from(pixels)
        worst = worst.add(palette)
        val p = problems(palette)
        if (p.isNotEmpty()) {
            bad++
            if (firstBad.isEmpty()) firstBad = "#%06X/#%06X: %s".format(a and 0xFFFFFF, b and 0xFFFFFF, p.joinToString())
        }
    }
    check("2000 copertine di due colori a caso: contrasti sempre giusti", bad == 0, if (bad == 0) worst.toString() else "$bad sbagliate, per esempio $firstBad")

    // ---------- Copertine finte ----------
    val amber = hueOf(ArtPalette.AMBER)
    fun cover(name: String, pixels: IntArray, grey: Boolean, hue: Double? = null) {
        val p = ArtPalette.from(pixels)
        val problems = problems(p)
        val vivoHue = hueOf(p.vivo)
        val detail = "vivo #%06X, profondo #%06X, contenitore #%06X, suVivo #%06X, secondo #%06X, tinta %.0f°".format(
            p.vivo and 0xFFFFFF, p.profondo and 0xFFFFFF, p.contenitore and 0xFFFFFF, p.suVivo and 0xFFFFFF, p.secondo and 0xFFFFFF, vivoHue,
        )
        check("$name: contrasti", problems.isEmpty(), problems.joinToString().ifEmpty { detail })
        check("$name: ${if (grey) "ripiega sull'ambra a croma bassa" else "prende la sua tinta"}", p.grey == grey && (hue == null || angle(vivoHue, hue) < 20), detail)
        if (grey) check("$name: croma bassa", chroma(p.profondo) < 0.05 && chroma(p.contenitore) < 0.05, "C profondo %.3f, contenitore %.3f".format(chroma(p.profondo), chroma(p.contenitore)))
    }
    val size = 48 * 48
    cover("Rosso vivo", IntArray(size) { 0xFFE00000.toInt() }, grey = false, hue = hueOf(0xFFE00000.toInt()))
    cover("Pastello (rosa)", IntArray(size) { 0xFFF8C8DC.toInt() }, grey = false, hue = hueOf(0xFFF8C8DC.toInt()))
    cover("Grigia", IntArray(size) { i -> val v = 60 + (i % 48) * 3; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, grey = true, hue = amber)
    cover("Bianca", IntArray(size) { 0xFFFFFFFF.toInt() }, grey = true, hue = amber)
    cover("Nera", IntArray(size) { 0xFF000000.toInt() }, grey = true, hue = amber)
    cover("Trasparente", IntArray(size) { 0x00FF0000 }, grey = true, hue = amber)
    cover("Grigia con il 2% di blu", IntArray(size) { i -> if (i < size * 2 / 100) 0xFF1060E0.toInt() else 0xFF808080.toInt() }, grey = true, hue = amber)
    cover("Grigia con il 5% di blu", IntArray(size) { i -> if (i < size * 5 / 100) 0xFF1060E0.toInt() else 0xFF808080.toInt() }, grey = false, hue = hueOf(0xFF1060E0.toInt()))
    // Due tinte: con molti più pixel vince il blu, e la seconda tinta è l'arancio (lontano più di 40°). Con poca
    // differenza di pixel vince la tinta più viva: ogni pixel pesa (C + 0,03)^1,5.
    run {
        val blue = 0xFF1D4E89.toInt()
        val orange = 0xFFF97316.toInt()
        val p = ArtPalette.from(IntArray(size) { i -> if (i < size * 80 / 100) blue else orange })
        check(
            "Due tinte (80% blu, 20% arancio): vince la più presente, la seconda è l'altra",
            angle(hueOf(p.vivo), hueOf(blue)) < 20 && angle(hueOf(p.secondo), hueOf(orange)) < 20,
            "vivo %.0f°, secondo %.0f°".format(hueOf(p.vivo), hueOf(p.secondo)),
        )
        val vivid = ArtPalette.from(IntArray(size) { i -> if (i < size * 60 / 100) blue else orange })
        check(
            "Due tinte (60% blu spento, 40% arancio vivo): vince la più viva",
            angle(hueOf(vivid.vivo), hueOf(orange)) < 20 && angle(hueOf(vivid.secondo), hueOf(blue)) < 20,
            "vivo %.0f°, secondo %.0f°".format(hueOf(vivid.vivo), hueOf(vivid.secondo)),
        )
        val one = ArtPalette.from(IntArray(size) { 0xFF1D4E89.toInt() })
        check("Una tinta sola: la seconda è girata di 40°", abs(angle(hueOf(one.secondo), hueOf(one.vivo)) - 40) < 8, "%.0f°".format(angle(hueOf(one.secondo), hueOf(one.vivo))))
    }
    // Le palette fisse.
    check("La palette del marchio: contrasti", problems(ArtPalette.BRAND).isEmpty(), problems(ArtPalette.BRAND).joinToString())
    check("La palette del marchio: play nell'ambra di sempre", ArtPalette.BRAND.vivo == ArtPalette.AMBER)
    check("La palette grigia: contrasti", problems(ArtPalette.GREY).isEmpty(), problems(ArtPalette.GREY).joinToString())

    // ---------- Sempre uguale, e in fretta ----------
    val pixels = IntArray(size) { i ->
        val x = i % 48
        val y = i / 48
        (0xFF shl 24) or ((x * 5) shl 16) or ((y * 5) shl 8) or (((x + y) * 2) and 0xFF)
    }
    val first = ArtPalette.from(pixels)
    check("Stessi pixel, stessa palette (100 giri)", (1..100).all { ArtPalette.from(pixels) == first })
    val covers = (0 until 200).map { seed ->
        val r = Random(seed)
        IntArray(size) { 0xFF000000.toInt() or r.nextInt(0x1000000) }
    }
    repeat(5) { covers.forEach { ArtPalette.from(it) } } // riscaldamento del JIT
    val t0 = System.nanoTime()
    covers.forEach { ArtPalette.from(it) }
    val each = (System.nanoTime() - t0) / covers.size / 1000.0
    check("Meno di 1 ms per copertina di 48×48 (pixel tutti diversi)", each < 1000, "%.0f µs".format(each))

    // ---------- Cache ----------
    repeat(100) { i -> ArtPalettes.of("http://x/$i", pixels) }
    check("La cache tiene al massimo 64 copertine", ArtPalettes.size == 64, "${ArtPalettes.size}")
    check("La cache tiene le più recenti", ArtPalettes.cached("http://x/99") != null && ArtPalettes.cached("http://x/0") == null)

    // ---------- Le copertine del Music Assistant di prova ----------
    if (System.getenv("MA_TOKEN") == null) {
        println("(senza MA_TOKEN: niente copertine vere)")
    } else {
        val repo = MaRepository(Settings())
        val library = Library(repo)
        repo.acquire()
        withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
        val base = repo.baseUrl ?: error("nessun indirizzo")
        val albums = library.libraryItems("albums").filter { it.image != null }.take(40)
        var real = 0
        var realBad = ""
        val hues = mutableListOf<String>()
        for (album in albums) {
            val url = MaRepository.imageUrl(base, album.image!!, 80)
            val bytes = repo.http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } ?: continue
            val image = ImageIO.read(bytes.inputStream()) ?: continue
            val small = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
            small.createGraphics().apply { drawImage(image, 0, 0, 48, 48, null); dispose() }
            val p = ArtPalette.from(small.getRGB(0, 0, 48, 48, null, 0, 48))
            real++
            val problems = problems(p)
            if (problems.isNotEmpty() && realBad.isEmpty()) realBad = problems.joinToString()
            hues += if (p.grey) "grigia" else "%.0f°".format(hueOf(p.vivo))
        }
        check("Le copertine vere del Music Assistant di prova ($real): contrasti", real > 0 && realBad.isEmpty(), realBad.ifEmpty { hues.joinToString() })
        repo.release()
    }

    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** I contrasti più bassi visti (per vedere quanto margine c'è). */
private data class Worst(val profondo: Double = 99.0, val vivo: Double = 99.0, val onVivo: Double = 99.0, val box: Double = 99.0) {
    fun add(p: ArtPalette) = Worst(
        minOf(profondo, ArtPalette.contrast(0xFFFFFFFF.toInt(), p.profondo)),
        minOf(vivo, ArtPalette.contrast(p.vivo, ArtPalette.BACKGROUND), ArtPalette.contrast(p.vivo, p.profondo)),
        minOf(onVivo, ArtPalette.contrast(p.suVivo, p.vivo)),
        minOf(box, ArtPalette.contrast(0xFFFFFFFF.toInt(), p.contenitore)),
    )

    override fun toString() = "minimi: bianco su profondo %.1f, vivo %.1f, suVivo %.1f, bianco sul contenitore %.1f".format(profondo, vivo, onVivo, box)
}

/** La tinta OKLCH di un colore, in gradi (qui, per non dipendere dai dettagli di ArtPalette). */
private fun hueOf(argb: Int): Double {
    val (_, a, b) = oklab(argb)
    val h = Math.toDegrees(atan2(b, a))
    return if (h < 0) h + 360 else h
}

private fun chroma(argb: Int): Double {
    val (_, a, b) = oklab(argb)
    return sqrt(a * a + b * b)
}

private fun angle(h1: Double, h2: Double): Double {
    val d = abs(h1 - h2) % 360
    return if (d > 180) 360 - d else d
}

private fun oklab(argb: Int): Triple<Double, Double, Double> {
    fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    val r = lin((argb shr 16) and 0xFF)
    val g = lin((argb shr 8) and 0xFF)
    val b = lin(argb and 0xFF)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return Triple(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}
