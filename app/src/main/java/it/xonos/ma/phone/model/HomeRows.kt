package it.xonos.ma.phone.model

import it.xonos.ma.data.MaItem

/**
 * Le sezioni della Home, in ordine fisso dall'alto (vedi docs/per-te-piano.md, capitolo 2). Ogni riga dei consigli di
 * Music Assistant ne ha una; [titleKey] ed [eyebrowKey] sono i nomi delle scritte in strings.xml (titolo e occhiello).
 */
enum class HomeSection(val titleKey: String?, val eyebrowKey: String?) {
    /** "In corso": episodi e audiolibri a metà. La riga non si disegna come le altre: dà le card con la barra. */
    RESUME("row_in_progress", "home_in_progress_eyebrow"),

    /** "Scelte rapide": la riga degli ascolti recenti, che sul telefono diventa la griglia di tessere. */
    QUICK("row_recently_played", "home_quick_eyebrow"),

    /** "Per te": il carosello (le playlist Infinite Mix di Music Assistant e gli artisti che ascolti). */
    PER_TE("cat_foryou", "home_for_you_eyebrow"),

    /** "Riscopri": brani e album che non si ascoltano da un po'. */
    REDISCOVER("home_rediscover_title", "home_rediscover_eyebrow"),

    /** "Dalla tua libreria": al massimo tre righe. */
    LIBRARY("home_library_title", "home_library_eyebrow"),

    /** "Altro", chiusa finché non si tocca: le righe a caso, quelle dei servizi che Xonos non conosce. */
    OTHER("home_other_title", "home_other_eyebrow"),

    /** Righe che Xonos non mostra né sul telefono né in auto (nella tappa 1 nessuna delle 16 della libreria). */
    HIDDEN(null, null),
    ;

    /** Sul telefono la riga si disegna come una riga di card (le altre danno In corso e le Scelte rapide). */
    val drawnAsRow: Boolean get() = this == PER_TE || this == REDISCOVER || this == LIBRARY || this == OTHER
}

/**
 * Dove va una riga dei consigli. [order] la mette in ordine dentro la sua sezione (prima i numeri piccoli; le righe
 * sconosciute dopo quelle di Xonos, nell'ordine in cui le manda Music Assistant). [titleKey] è la scritta di Xonos
 * (`row_<item_id>`); null per le righe che Xonos non conosce, che si chiamano col nome dato da Music Assistant.
 */
data class RowPlace(val section: HomeSection, val order: Int, val titleKey: String?) {
    val known: Boolean get() = titleKey != null
}

/** Una riga con il suo posto. */
data class PlacedRow(val row: MaItem, val place: RowPlace)

/**
 * Le righe di "Per te" di Music Assistant (`music/recommendations`) nella Home del telefono e nel menu dell'auto.
 *
 * Si riconoscono dal servizio (il dominio) e dall'`item_id`, mai dalla translation_key: su /ws Music Assistant la
 * toglie sempre. Le 16 righe della libreria (servizio "recommendations" dalla 2.10) arrivano tutte, anche quelle che
 * l'interfaccia di Music Assistant tiene spente: Xonos sceglie da sé dove metterle e le chiama coi suoi nomi, in 9
 * lingue. Senza Android: lo provano il banco (HomeRowsCheck) e tools/screens.
 */
object HomeRows {
    /** Il servizio delle righe della libreria in Music Assistant 2.10 e successivi. */
    const val LIBRARY_PROVIDER = "recommendations"

    /** Prima della 2.10 le stesse righe arrivavano dalla libreria stessa. */
    private val LIBRARY_DOMAINS = setOf(LIBRARY_PROVIDER, "library")

    /** Le 16 righe della libreria di Music Assistant 2.10.8 (`LibraryRowID`), nell'ordine in cui le manda. */
    val LIBRARY_ROWS = listOf(
        "in_progress",
        "recently_played",
        "recently_added_tracks",
        "recently_added_albums",
        "random_artists",
        "random_albums",
        "recent_favorite_tracks",
        "favorite_playlists",
        "favorite_radio",
        "recent_artists",
        "recent_tracks",
        "forgotten_tracks",
        "forgotten_albums",
        "forgotten_artists",
        "most_played_tracks",
        "never_played_tracks",
    )

    /** La riga che riempie le Scelte rapide quando i contesti recenti sono meno di [QUICK_PICKS]. */
    const val QUICK_FILLER = "favorite_playlists"

    /** Quante tessere hanno le Scelte rapide (griglia 2×3). */
    const val QUICK_PICKS = 6

    /** Le righe sconosciute vanno dopo quelle di Xonos, nell'ordine di Music Assistant. */
    private const val UNKNOWN_ORDER = 100

    /**
     * Il posto delle righe della libreria (tappa 1 del piano): sezione e ordine. Quelle che il piano non nomina vanno in
     * "Altro", dopo le righe a caso e "mai ascoltati".
     */
    private val PLACES: Map<String, Pair<HomeSection, Int>> = mapOf(
        "in_progress" to (HomeSection.RESUME to 0),
        "recently_played" to (HomeSection.QUICK to 0),
        "recent_artists" to (HomeSection.PER_TE to 0),
        "forgotten_tracks" to (HomeSection.REDISCOVER to 0),
        "forgotten_albums" to (HomeSection.REDISCOVER to 1),
        "recently_added_albums" to (HomeSection.LIBRARY to 0),
        "favorite_playlists" to (HomeSection.LIBRARY to 1),
        "most_played_tracks" to (HomeSection.LIBRARY to 2),
        "random_albums" to (HomeSection.OTHER to 0),
        "random_artists" to (HomeSection.OTHER to 1),
        "never_played_tracks" to (HomeSection.OTHER to 2),
        "recent_favorite_tracks" to (HomeSection.OTHER to 3),
        "recently_added_tracks" to (HomeSection.OTHER to 4),
        "forgotten_artists" to (HomeSection.OTHER to 5),
        "recent_tracks" to (HomeSection.OTHER to 6),
        "favorite_radio" to (HomeSection.OTHER to 7),
    )

    /** Le chiavi delle scritte di tutte le righe conosciute (`row_<item_id>`), per chi le traduce in id di risorse. */
    val TITLE_KEYS: List<String> = LIBRARY_ROWS.map(::titleKeyOf)

    private fun titleKeyOf(itemId: String) = "row_$itemId"

    /** Vero per le 16 righe della libreria di Music Assistant. [provider] è l'istanza o il dominio. */
    fun isLibraryRow(provider: String, itemId: String): Boolean =
        domainOf(provider) in LIBRARY_DOMAINS && itemId in PLACES

    /** Il posto di una riga, da servizio (istanza o dominio) e item_id. */
    fun place(provider: String, itemId: String): RowPlace {
        if (!isLibraryRow(provider, itemId)) return RowPlace(HomeSection.OTHER, UNKNOWN_ORDER, null)
        val (section, order) = PLACES.getValue(itemId)
        return RowPlace(section, order, titleKeyOf(itemId))
    }

    fun place(row: MaItem): RowPlace = place(row.provider, row.itemId)

    /**
     * Le righe con il loro posto, in ordine: per sezione, poi per [RowPlace.order], poi come le manda Music Assistant.
     * Solo le cartelle (le righe vere); con [withHidden] falso via quelle di [HomeSection.HIDDEN].
     */
    fun arrange(rows: List<MaItem>, withHidden: Boolean = false): List<PlacedRow> =
        rows.filter { it.mediaType == "folder" }
            .mapIndexed { i, row -> Triple(i, row, place(row)) }
            .filter { (_, _, place) -> withHidden || place.section != HomeSection.HIDDEN }
            .sortedWith(compareBy({ it.third.section.ordinal }, { it.third.order }, { it.first }))
            .map { (_, row, place) -> PlacedRow(row, place) }

    /** Le righe di una sezione sola, in ordine. */
    fun section(rows: List<MaItem>, section: HomeSection): List<PlacedRow> =
        arrange(rows, withHidden = true).filter { it.place.section == section }

    /**
     * Per "Per te" in auto: le stesse righe e nello stesso ordine della Home, coi nomi di Xonos (le sconosciute col
     * loro), senza le nascoste.
     */
    fun forCar(rows: List<MaItem>): List<PlacedRow> = arrange(rows)

    /**
     * Le playlist "Infinite Mix" di Music Assistant (della libreria o dei preferiti): non finiscono mai, e vanno nel
     * carosello "Per te". Si riconoscono dalla loro voce nel servizio builtin, non dal nome (che è in inglese).
     */
    fun isInfiniteMix(item: MaItem): Boolean {
        if (item.mediaType != "playlist") return false
        if (item.providerDomain == "builtin" && item.itemId in INFINITE_MIXES) return true
        return item.mappings.any { (instance, id) -> domainOf(instance) == "builtin" && id in INFINITE_MIXES }
    }

    private val INFINITE_MIXES = setOf("infinite_mix", "infinite_mix_favorites")

    /**
     * Il saluto della testata per l'ora [hour] (0-23): "Buongiorno" dalle 5 alle 12, "Buon pomeriggio" fino alle 18,
     * "Buonasera" fino alle 23, poi "Buonanotte". È il nome della scritta.
     */
    fun greetingKey(hour: Int): String = when (((hour % 24) + 24) % 24) {
        in 5..11 -> "greeting_morning"
        in 12..17 -> "greeting_afternoon"
        in 18..22 -> "greeting_evening"
        else -> "greeting_night"
    }

    /** Titolo e occhiello della riga di tessere "I tuoi generi" (non è una riga di Music Assistant: Library.genreTiles). */
    const val GENRES_TITLE = "home_genres_title"
    const val GENRES_EYEBROW = "home_genres_eyebrow"

    /** Le scritte dei saluti, per chi le traduce in id di risorse. */
    val GREETING_KEYS = listOf("greeting_morning", "greeting_afternoon", "greeting_evening", "greeting_night")

    private fun domainOf(provider: String) = provider.substringBefore("--")
}
