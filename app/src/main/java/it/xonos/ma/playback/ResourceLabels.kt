package it.xonos.ma.playback

import android.content.Context
import android.content.res.Resources
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.wrap

/** Le scritte del menu dalle risorse dell'app, nella lingua scelta sul telefono, anche se cambia con l'auto collegata. */
class ResourceLabels(private val context: Context, private val language: () -> Language) : MenuLabels {

    private var cached: Pair<Language, Resources>? = null

    private val resources: Resources
        @Synchronized get() {
            val current = language()
            cached?.let { (chosen, res) -> if (chosen == current) return res }
            return current.wrap(context).resources.also { cached = current to it }
        }

    override fun category(id: String) = resources.getString(CATEGORIES.getValue(id))

    override fun kind(type: String) = resources.getString(KINDS.getValue(type))

    override fun results(count: Int) = resources.getQuantityString(R.plurals.results, count, count)

    override fun count(type: String, count: Int) = resources.getQuantityString(COUNTS.getValue(type), count, count)

    override val topTracks: String get() = resources.getString(R.string.top_tracks)

    override val allTracks: String get() = resources.getString(R.string.all_tracks)

    override fun row(itemId: String): String? = ROWS[itemId]?.let { resources.getString(it) }

    private companion object {
        val CATEGORIES = mapOf(
            "recent" to R.string.cat_recent,
            "foryou" to R.string.cat_foryou,
            "playlists" to R.string.cat_playlists,
            "albums" to R.string.cat_albums,
            "artists" to R.string.cat_artists,
            "tracks" to R.string.cat_tracks,
            "favorites" to R.string.cat_favorites,
            "genres" to R.string.cat_genres,
            "radios" to R.string.cat_radios,
            "podcasts" to R.string.cat_podcasts,
            "audiobooks" to R.string.cat_audiobooks,
            "browse" to R.string.cat_browse,
        )

        /** I tipi della ricerca si chiamano come le voci del menu. */
        val KINDS = mapOf(
            "artists" to R.string.cat_artists,
            "tracks" to R.string.cat_tracks,
            "albums" to R.string.cat_albums,
            "playlists" to R.string.cat_playlists,
            "podcasts" to R.string.cat_podcasts,
            "audiobooks" to R.string.cat_audiobooks,
            "radio" to R.string.cat_radios,
            "genres" to R.string.cat_genres,
        )

        /**
         * Le righe di "Per te" della libreria di Music Assistant (HomeRows.LIBRARY_ROWS): le stesse scritte della Home.
         * Scritte una per una e non cercate per nome: con shrinkResources una risorsa cercata per nome può mancare.
         */
        val ROWS = mapOf(
            "in_progress" to R.string.row_in_progress,
            "recently_played" to R.string.row_recently_played,
            "recently_added_tracks" to R.string.row_recently_added_tracks,
            "recently_added_albums" to R.string.row_recently_added_albums,
            "random_artists" to R.string.row_random_artists,
            "random_albums" to R.string.row_random_albums,
            "recent_favorite_tracks" to R.string.row_recent_favorite_tracks,
            "favorite_playlists" to R.string.row_favorite_playlists,
            "favorite_radio" to R.string.row_favorite_radio,
            "recent_artists" to R.string.row_recent_artists,
            "recent_tracks" to R.string.row_recent_tracks,
            "forgotten_tracks" to R.string.row_forgotten_tracks,
            "forgotten_albums" to R.string.row_forgotten_albums,
            "forgotten_artists" to R.string.row_forgotten_artists,
            "most_played_tracks" to R.string.row_most_played_tracks,
            "never_played_tracks" to R.string.row_never_played_tracks,
        )

        val COUNTS = mapOf(
            "tracks" to R.plurals.count_tracks,
            "albums" to R.plurals.count_albums,
            "artists" to R.plurals.count_artists,
            "playlists" to R.plurals.count_playlists,
            "radio" to R.plurals.count_radio,
            "podcasts" to R.plurals.count_podcasts,
            "audiobooks" to R.plurals.count_audiobooks,
            "genres" to R.plurals.count_genres,
        )
    }
}
