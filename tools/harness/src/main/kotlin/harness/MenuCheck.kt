package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.HomeRows
import it.xonos.ma.phone.model.HomeSection
import it.xonos.ma.playback.Menu
import it.xonos.ma.playback.MenuNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Percorre il menu come farebbe Gearslip: cartelle, iniziali, artisti, podcast e ricerche. */
fun main(): Unit = runBlocking {
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val lang = System.getenv("LANG_XONOS") ?: "en"
    val labels = XmlLabels(lang)
    val menu = Menu(library, labels) { System.getenv("PLAYER_ID") ?: "autoradio-harness1" }
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "OK   " else "MALE ") + name + if (detail.isNotEmpty()) ": $detail" else "")
        if (!ok) failures++
    }

    fun line(n: MenuNode) = "${n.title} | ${n.subtitle ?: ""} | ${n.id}${if (n.browsable) " [apri]" else ""}${if (n.playable) " [suona]" else ""}"
    suspend fun show(id: String, max: Int = 6, indent: String = ""): List<MenuNode> {
        val t0 = System.nanoTime()
        val kids = menu.children(id)
        println("$indent[$id] ${kids.size} voci in ${(System.nanoTime() - t0) / 1_000_000} ms")
        kids.take(max).forEach { println("$indent   - ${line(it)}") }
        if (kids.size > max) println("$indent   ... ultima: ${line(kids.last())}")
        return kids
    }

    val root = show(Menu.ROOT, 20)
    for (category in root) {
        val kids = show(category.id, 4, "  ")
        // Le iniziali: si apre la prima e una a metà.
        if (kids.firstOrNull()?.id?.startsWith("abc|") == true) {
            show(kids.first().id, 3, "    ")
            kids.getOrNull(kids.size / 2)?.let { show(it.id, 3, "    ") }
        }
    }
    // "Per te" come la Home del telefono (HomeRows): stesse righe nello stesso ordine, coi nomi di Xonos; quelle che
    // Xonos non conosce col nome di Music Assistant; le nascoste no.
    val rows = library.recommendations()
    val placed = HomeRows.forCar(rows)
    val forYou = menu.children("cat|foryou")
    println("\nPER TE ($lang): ${forYou.size} righe")
    forYou.forEach { println("   - ${line(it)}") }
    check(
        "Per te: le righe nell'ordine della Home",
        forYou.map { it.id } == placed.map { "rec|${it.row.provider}|${it.row.itemId}" },
        forYou.joinToString(", ") { it.id.substringAfterLast('|') },
    )
    check(
        "Per te: le prime sono In corso e Torna ad ascoltare",
        forYou.take(2).map { it.id.substringAfterLast('|') } == listOf("in_progress", "recently_played"),
    )
    val named = placed.zip(forYou).filter { (p, _) -> p.place.known }
    check(
        "Per te: le righe della libreria coi nomi di Xonos, senza il sottotitolo di Music Assistant",
        named.isNotEmpty() && named.all { (p, n) -> n.title == labels.row(p.row.itemId) && n.subtitle == null },
        named.joinToString(", ") { it.second.title },
    )
    val unknown = placed.zip(forYou).filter { (p, _) -> !p.place.known }
    check(
        "Per te: le righe sconosciute col nome di Music Assistant",
        unknown.all { (p, n) -> n.title == p.row.name && p.place.section == HomeSection.OTHER } &&
            (rows.none { it.provider.startsWith("itunes_podcasts") } || unknown.any { (p, _) -> p.row.provider.startsWith("itunes_podcasts") }),
        unknown.joinToString(", ") { it.second.title },
    )
    check(
        "Per te: nessuna riga nascosta e nessuna in più",
        placed.none { it.place.section == HomeSection.HIDDEN } && forYou.size == rows.count { it.mediaType == "folder" },
    )
    check("Per te: sotto le ${Menu.PART} voci di un pezzo", forYou.size < Menu.PART)
    // Una riga si apre ancora (gli ascolti recenti), con i suoi elementi.
    forYou.getOrNull(1)?.let { recent ->
        val kids = menu.children(recent.id)
        check("Per te: \"${recent.title}\" si apre", kids.isNotEmpty(), "${kids.size} voci")
    }
    // I nomi di Xonos ci sono in tutte le lingue, diversi tra loro.
    for (code in listOf("en", "it", "es", "de", "fr", "pt", "el", "ru", "zh")) {
        val names = HomeRows.LIBRARY_ROWS.map { XmlLabels(code).row(it) }
        check("Per te: 16 nomi in $code", names.all { !it.isNullOrBlank() } && names.toSet().size == names.size)
    }

    // Un artista con tutto: brani più ascoltati, album, tutti i brani.
    val artists = menu.children("cat|artists")
    val v = artists.firstOrNull { it.title == "V" }?.let { menu.children(it.id) } ?: artists
    v.firstOrNull { it.title.startsWith("Vasco") }?.let { vasco ->
        val page = show(vasco.id, 10, "  ")
        page.firstOrNull { it.id.startsWith("tracks|") }?.let { show(it.id, 3, "    ") }
        page.firstOrNull { it.id.startsWith("open|") }?.let { show(it.id, 3, "    ") }
    }
    // Un podcast della libreria.
    menu.children("cat|podcasts").firstOrNull()?.let { show(it.id, 3, "  ") }

    val queries = (System.getenv("QUERIES") ?: "morning,muschio,vasco,canzone,podcast elisir,elisir podcast,storie,album 2 di vasco,artista,xyz123").split(',')
    for (q in queries) {
        val t0 = System.nanoTime()
        val results = menu.search(q)
        println("\nRICERCA \"$q\": ${results.size} voci in ${(System.nanoTime() - t0) / 1_000_000} ms")
        results.forEach { println("   - ${line(it)}") }
        results.filter { it.id.startsWith("find|") }.forEach { folder ->
            val kids = menu.children(folder.id)
            println("     > ${folder.title}: ${kids.size} voci, prima: ${kids.firstOrNull()?.let(::line)}")
        }
    }
    repo.release()
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
