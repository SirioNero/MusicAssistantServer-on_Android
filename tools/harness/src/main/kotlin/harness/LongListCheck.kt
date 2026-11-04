package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.playback.Menu
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Gli elenchi lunghi in auto ([Menu.PART]): Media3 taglia a 256 KB l'elenco per Gearslip, quindi una playlist di
 * migliaia di brani diventa cartelle da 150, e ogni brano dentro un pezzo ha lo stesso identificativo che avrebbe
 * nell'elenco intero (suona la playlist da lì). Va con la "Playlist grande" del Music Assistant di prova (MA_URL, MA_TOKEN).
 */
fun main(): Unit = runBlocking {
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val menu = Menu(library, XmlLabels("it")) { "autoradio-harness1" }
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "OK   " else "MALE ") + name + if (detail.isNotEmpty()) ": $detail" else "")
        if (!ok) failures++
    }
    val playlists = library.libraryPage("playlists", 0)
    val big = playlists.maxByOrNull { if (it.name.contains("grande", ignoreCase = true)) 1 else 0 }!!
    val tracks = library.playlistTracks(big)
    val parts = menu.children("open|${big.uri}")
    check("Playlist di ${tracks.size} brani: cartelle da ${Menu.PART}", parts.size == (tracks.size + Menu.PART - 1) / Menu.PART && parts.all { it.id.startsWith("part|") }, "${parts.size} cartelle, la prima \"${parts.firstOrNull()?.title}\"")
    val third = menu.children(parts[3].id)
    val expected = tracks.subList(3 * Menu.PART, minOf(4 * Menu.PART, tracks.size))
    check("Quarto pezzo: le voci giuste", third.size == expected.size && third.first().title == expected.first().name, "${third.size} voci, prima \"${third.firstOrNull()?.title}\"")
    check("Stesso identificativo dell'elenco intero", third.first().id == "in|${big.uri}|${expected.first().uri}", third.first().id)
    val last = menu.children(parts.last().id)
    check("Ultimo pezzo fino all'ultimo brano", last.lastOrNull()?.title == tracks.last().name, "${last.size} voci")
    val small = playlists.firstOrNull { it.uri != big.uri }?.let { library.playlistTracks(it) to it }
    if (small != null && small.first.size <= Menu.PART) {
        val kids = menu.children("open|${small.second.uri}")
        check("Playlist corta (${small.first.size}): come prima, senza cartelle", kids.none { it.id.startsWith("part|") })
    }
    // "Per te": le righe di Music Assistant in una cartella sola, ben sotto un pezzo; e una riga aperta resta divisa
    // come gli altri elenchi se è lunga.
    val forYou = menu.children("cat|foryou")
    check("Per te: ${forYou.size} righe, nessuna cartella di pezzi", forYou.isNotEmpty() && forYou.size < Menu.PART && forYou.none { it.id.startsWith("part|") })
    for (row in forYou.take(4)) {
        val kids = menu.children(row.id)
        check("Per te, \"${row.title}\": ${kids.size} voci entro un pezzo o divise", kids.size <= Menu.PART)
    }
    repo.release()
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
