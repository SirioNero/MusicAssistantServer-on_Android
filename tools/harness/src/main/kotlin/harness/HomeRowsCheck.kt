package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.HomeRows
import it.xonos.ma.phone.model.HomeSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Le lingue dell'app, con la cartella delle risorse (values-zh per il cinese). */
internal val APP_LANGS = listOf("en", "it", "es", "de", "fr", "pt", "el", "ru", "zh")

/** Le scritte di una lingua lette dai veri strings.xml (l'inglese sotto, come su Android). */
internal class ResStrings(lang: String) {
    val strings = mutableMapOf<String, String>()
    val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val res = System.getProperty("xonos.res") ?: "../../app/src/main/res"
        if (lang == "en") load(File("$res/values/strings.xml")) else load(File("$res/values-$lang/strings.xml"))
    }

    private fun load(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
    }
}

/**
 * Le righe di "Per te" di Music Assistant nella Home e in auto (HomeRows): le 16 della libreria hanno una sezione e un
 * titolo di Xonos in tutte e 9 le lingue, le sconosciute vanno in "Altro" col loro nome, niente dipende dalla
 * translation_key. Con MA_TOKEN anche contro il Music Assistant di prova: le righe vere, senza translation_key.
 */
fun main(): Unit = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    val langs = APP_LANGS.associateWith(::ResStrings)
    fun everywhere(key: String): List<String> = APP_LANGS.filter { langs.getValue(it).strings[key].isNullOrBlank() }

    fun row(provider: String, itemId: String, name: String = itemId, translationKey: String? = null): MaItem =
        MaItem.from(
            JSONObject()
                .put("item_id", itemId)
                .put("provider", provider)
                .put("name", name)
                .put("uri", "library://folder/$itemId")
                .put("media_type", "folder")
                .put("is_playable", false)
                .apply { if (translationKey != null) put("translation_key", translationKey) },
        )!!

    // ---------- Le 16 righe della libreria ----------
    check("16 righe della libreria", HomeRows.LIBRARY_ROWS.size == 16 && HomeRows.LIBRARY_ROWS.toSet().size == 16)
    for (id in HomeRows.LIBRARY_ROWS) {
        val place = HomeRows.place("recommendations", id)
        val missing = place.titleKey?.let(::everywhere) ?: APP_LANGS
        check(
            "$id → ${place.section} #${place.order}, titolo ${place.titleKey}",
            place.titleKey == "row_$id" && place.order < 100 && missing.isEmpty(),
            if (missing.isEmpty()) langs.getValue("it").strings[place.titleKey].orEmpty() else "manca in $missing",
        )
    }
    check("Le chiavi dei titoli sono quelle delle 16 righe", HomeRows.TITLE_KEYS == HomeRows.LIBRARY_ROWS.map { "row_$it" })
    // Due righe con lo stesso titolo in una lingua confonderebbero (in auto stanno nella stessa cartella).
    for (lang in APP_LANGS) {
        val titles = HomeRows.TITLE_KEYS.map { langs.getValue(lang).strings[it].orEmpty().lowercase() }
        check("$lang: titoli tutti diversi", titles.toSet().size == titles.size, titles.groupBy { it }.filterValues { it.size > 1 }.keys.joinToString())
    }

    // ---------- Sezioni come nel piano (tappa 1) ----------
    fun sectionOf(id: String) = HomeRows.place("recommendations", id).section
    check("In corso alimenta la sezione In corso", sectionOf("in_progress") == HomeSection.RESUME)
    check("Ascoltati di recente → Scelte rapide", sectionOf("recently_played") == HomeSection.QUICK)
    check("Artisti che ascolti → Per te", sectionOf("recent_artists") == HomeSection.PER_TE)
    check("Brani e album dimenticati → Riscopri", sectionOf("forgotten_tracks") == HomeSection.REDISCOVER && sectionOf("forgotten_albums") == HomeSection.REDISCOVER)
    val library = HomeRows.LIBRARY_ROWS.filter { sectionOf(it) == HomeSection.LIBRARY }
    check(
        "Dalla tua libreria: al massimo 3 righe (aggiunti, playlist preferite, più ascoltati)",
        library == listOf("recently_added_albums", "favorite_playlists", "most_played_tracks") ||
            library.toSet() == setOf("recently_added_albums", "favorite_playlists", "most_played_tracks"),
        library.joinToString(),
    )
    check("random_* e never_played → Altro", listOf("random_artists", "random_albums", "never_played_tracks").all { sectionOf(it) == HomeSection.OTHER })
    check("Riempitivo delle Scelte rapide", HomeRows.QUICK_FILLER in HomeRows.LIBRARY_ROWS)
    for (section in HomeSection.entries) {
        val keys = listOfNotNull(section.titleKey, section.eyebrowKey)
        val missing = keys.flatMap { k -> everywhere(k).map { "$k/$it" } }
        check("Sezione $section: titolo e occhiello in 9 lingue", section == HomeSection.HIDDEN || (keys.size == 2 && missing.isEmpty()), missing.joinToString())
    }
    for (k in listOf("home_genres_title", "home_genres_eyebrow", "home_more_show", "home_more_hide", "playing_from")) {
        check("$k in 9 lingue", everywhere(k).isEmpty(), everywhere(k).joinToString())
    }

    // ---------- Righe sconosciute, istanze, versioni vecchie ----------
    val podcasts = HomeRows.place(row("itunes_podcasts", "itunes-top-podcasts", "Trending Podcasts"))
    check("Riga di un servizio sconosciuta → Altro, col nome di MA", podcasts.section == HomeSection.OTHER && podcasts.titleKey == null && !podcasts.known)
    val soundcloud = HomeRows.place("soundcloud--Ab12", "soundcloud--Ab12_sc_subscribed_feed")
    check("Riga di SoundCloud (id con l'istanza) → Altro", soundcloud.section == HomeSection.OTHER && soundcloud.titleKey == null)
    check("Un item_id della libreria da un altro servizio non conta", HomeRows.place("spotify--x", "recently_played").titleKey == null)
    check("Istanza del servizio dei consigli (recommendations--x)", HomeRows.place("recommendations--x1", "forgotten_tracks").titleKey == "row_forgotten_tracks")
    check("Prima della 2.10 (servizio library)", HomeRows.place("library", "favorite_playlists").section == HomeSection.LIBRARY)
    check("Una riga nuova della libreria (sconosciuta) → Altro", HomeRows.place("recommendations", "top_this_week").let { it.section == HomeSection.OTHER && it.titleKey == null })

    // ---------- Niente dipende dalla translation_key ----------
    val misleading = row("recommendations", "recent_artists", "Recent artists", translationKey = "forgotten_tracks")
    val bare = row("recommendations", "recent_artists", "Recent artists")
    check(
        "Con una translation_key sbagliata o senza: stesso posto",
        HomeRows.place(misleading) == HomeRows.place(bare) && HomeRows.place(bare).section == HomeSection.PER_TE,
    )
    val source = File("src/main/kotlin/app/phone/model/HomeRows.kt").takeIf { it.exists() }?.readText()
    check(
        "HomeRows.kt non legge translation_key (solo nei commenti)",
        source == null || source.lines().none { line -> "translation_key" in line && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") },
    )

    // ---------- Ordine: per sezione, poi ordine, poi come le manda Music Assistant ----------
    val mixed = listOf(
        row("itunes_podcasts", "itunes-top-podcasts", "Trending Podcasts"),
        row("recommendations", "never_played_tracks"),
        row("recommendations", "most_played_tracks"),
        row("recommendations", "in_progress"),
        row("plex--a", "plex--a_hub1", "Plex hub"),
        row("recommendations", "recent_artists"),
        row("recommendations", "recently_added_albums"),
        row("recommendations", "forgotten_albums"),
        row("recommendations", "forgotten_tracks"),
    )
    val arranged = HomeRows.arrange(mixed).map { it.row.itemId }
    check(
        "Ordine della Home",
        arranged == listOf(
            "in_progress", "recent_artists", "forgotten_tracks", "forgotten_albums", "recently_added_albums",
            "most_played_tracks", "never_played_tracks", "itunes-top-podcasts", "plex--a_hub1",
        ),
        arranged.joinToString(),
    )
    val car = HomeRows.forCar(mixed)
    check("In auto le stesse righe, nessuna nascosta", car.size == mixed.size && car.none { it.place.section == HomeSection.HIDDEN })
    check("Solo le cartelle sono righe", HomeRows.arrange(mixed + MaItem.from(JSONObject().put("uri", "library://album/1").put("media_type", "album").put("name", "x"))!!).size == mixed.size)
    check("Una sezione sola", HomeRows.section(mixed, HomeSection.REDISCOVER).map { it.row.itemId } == listOf("forgotten_tracks", "forgotten_albums"))

    // ---------- Saluti ----------
    val hours = mapOf(
        4 to "greeting_night", 5 to "greeting_morning", 11 to "greeting_morning", 12 to "greeting_afternoon",
        17 to "greeting_afternoon", 18 to "greeting_evening", 22 to "greeting_evening", 23 to "greeting_night", 0 to "greeting_night",
    )
    check("Saluti per ora (05–12, 12–18, 18–23, 23–05)", hours.all { (h, k) -> HomeRows.greetingKey(h) == k })
    for (k in HomeRows.GREETING_KEYS) check("$k in 9 lingue", everywhere(k).isEmpty(), langs.getValue("it").strings[k].orEmpty())

    // ---------- Infinite Mix ----------
    fun playlist(id: String, mapping: Pair<String, String>?) = MaItem.from(
        JSONObject().put("uri", "library://playlist/$id").put("media_type", "playlist").put("name", "Infinite Mix (library)")
            .put("item_id", id).put("provider", "library")
            .put("provider_mappings", JSONArray().apply { if (mapping != null) put(JSONObject().put("provider_instance", mapping.first).put("item_id", mapping.second)) }),
    )!!
    check("Infinite Mix riconosciuto dal servizio builtin", HomeRows.isInfiniteMix(playlist("7", "builtin" to "infinite_mix")))
    check("Una playlist che si chiama così ma non lo è", !HomeRows.isInfiniteMix(playlist("9", "filesystem_local--x" to "Infinite Mix.m3u")))

    // ---------- Contro il Music Assistant di prova ----------
    val token = System.getenv("MA_TOKEN")
    if (token.isNullOrBlank()) {
        println("(senza MA_TOKEN: niente prove contro Music Assistant)")
    } else {
        val repo = MaRepository(Settings())
        val lib = Library(repo)
        repo.acquire()
        val connected = withTimeoutOrNull(20_000) { repo.connection.first { it is Connection.Connected } }
        check("Collegato a Music Assistant", connected != null)
        if (connected != null) {
            val raw = repo.command("music/recommendations", 60_000) as? JSONArray
            val anyKey = (0 until (raw?.length() ?: 0)).any { raw!!.optJSONObject(it)?.has("translation_key") == true }
            check("Su /ws le righe arrivano senza translation_key", raw != null && !anyKey, "${raw?.length()} righe")
            val rows = lib.recommendations()
            val ids = rows.filter { HomeRows.isLibraryRow(it.provider, it.itemId) }.map { it.itemId }.toSet()
            check("Arrivano tutte le 16 righe della libreria", ids == HomeRows.LIBRARY_ROWS.toSet(), (HomeRows.LIBRARY_ROWS - ids).joinToString())
            for (placed in HomeRows.arrange(rows)) {
                val name = placed.place.titleKey?.let { langs.getValue("it").strings[it] } ?: placed.row.name
                println("    ${placed.place.section.name.padEnd(10)} ${placed.row.providerDomain}/${placed.row.itemId} → $name")
            }
            val unknown = rows.filter { !HomeRows.isLibraryRow(it.provider, it.itemId) }
            check("Le righe degli altri servizi vanno in Altro col loro nome", unknown.all { HomeRows.place(it).let { p -> p.section == HomeSection.OTHER && p.titleKey == null } } && unknown.all { it.name.isNotBlank() }, unknown.joinToString { it.name })
            val mixes = lib.libraryItems("playlists").filter(HomeRows::isInfiniteMix)
            check("Le playlist Infinite Mix della libreria", mixes.size == 2, mixes.joinToString { it.name })
            // I contenitori recenti e i generi per la Home.
            val contexts = lib.recentContexts()
            check("Contesti recenti: solo contenitori scelti", contexts.all { it.mediaType in Library.CONTEXT_TYPES }, contexts.joinToString { "${it.mediaType}:${it.available}" })
            val progress = lib.inProgress()
            check("In corso: episodi e audiolibri", progress.all { it.mediaType == "podcast_episode" || it.mediaType == "audiobook" }, "${progress.size}")
            val genres = lib.genreTiles()
            check("Generi per le tessere", genres.all { it.mediaType == "genre" } && genres.size <= Library.GENRE_TILES, genres.joinToString { it.name })
        }
        repo.release()
    }

    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
