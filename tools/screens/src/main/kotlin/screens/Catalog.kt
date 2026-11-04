package screens

import it.xonos.ma.data.MaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * La libreria finta delle schermate: qualche artista, album con i loro brani, playlist, podcast e
 * radio. Le copertine sono sfumature disegnate da [FakeServer], con i colori scelti qui.
 */
object Catalog {
    /** Id della copertina, colore di partenza e di arrivo. */
    val colors = mapOf(
        "emozioni" to (0x1D4E89 to 0x6EC6FF),
        "anima" to (0x7C2D12 to 0xFDBA74),
        "canto" to (0x14532D to 0x86EFAC),
        "bollicine" to (0xC2410C to 0xFBBF24),
        "studiouno" to (0x9D174D to 0xF9A8D4),
        "west" to (0x3F3F46 to 0xD6D3D1),
        "mare" to (0x0C4A6E to 0x38BDF8),
        "padrone" to (0x581C87 to 0xC084FC),
        "creuza" to (0x713F12 to 0xFDE68A),
        "nero" to (0x111827 to 0x6B7280),
        "battisti" to (0x334155 to 0x94A3B8),
        "vasco" to (0x7F1D1D to 0xF87171),
        "mina" to (0x831843 to 0xFBCFE8),
        "dalla" to (0x164E63 to 0x67E8F9),
        "battiato" to (0x3B0764 to 0xA78BFA),
        "viaggio" to (0x064E3B to 0x34D399),
        "domenica" to (0x78350F to 0xFCD34D),
        "ottanta" to (0x701A75 to 0xF0ABFC),
        "morning" to (0x1E3A8A to 0x93C5FD),
        "bordone" to (0x365314 to 0xBEF264),
        "deejay" to (0x991B1B to 0xFCA5A5),
    )

    private fun image(id: String) = JSONObject().put("type", "thumb").put("path", id).put("provider", "library")
        .put("proxy_id", id).put("remotely_accessible", false)

    private fun ref(type: String, id: String, name: String) =
        JSONObject().put("uri", "library://$type/$id").put("name", name).put("media_type", type).put("item_id", id)

    private fun base(type: String, id: String, name: String, cover: String?) = JSONObject()
        .put("uri", "library://$type/$id").put("media_type", type).put("item_id", id).put("provider", "library")
        .put("name", name).put("sort_name", name.lowercase()).apply { if (cover != null) put("image", image(cover)) }

    private val artistNames = mapOf(
        "battisti" to "Lucio Battisti", "vasco" to "Vasco Rossi", "mina" to "Mina", "morricone" to "Ennio Morricone",
        "dalla" to "Lucio Dalla", "battiato" to "Franco Battiato", "deandre" to "Fabrizio De André", "daniele" to "Pino Daniele",
    )

    fun artist(id: String, cover: String? = id): MaItem = MaItem.from(base("artist", id, artistNames.getValue(id), cover))!!

    private fun albumJson(id: String, name: String, artist: String, year: Int) = base("album", id, name, id)
        .put("year", year).put("artists", JSONArray().put(ref("artist", artist, artistNames.getValue(artist))))

    fun album(id: String): MaItem = MaItem.from(ALBUMS.getValue(id).let { (name, artist, year) -> albumJson(id, name, artist, year) })!!

    /** Il brano numero [n] (da 1) di un album. */
    fun track(album: String, n: Int): MaItem = MaItem.from(trackJson(album, n))!!

    fun trackJson(album: String, n: Int): JSONObject {
        val (albumName, artist, _) = ALBUMS.getValue(album)
        val (name, seconds) = TRACKS.getValue(album)[n - 1]
        return base("track", "$album-$n", name, null)
            .put("duration", seconds)
            .put("track_number", n)
            .put("disc_number", 1)
            .put("artists", JSONArray().put(ref("artist", artist, artistNames.getValue(artist))))
            .put("album", ref("album", album, albumName).put("image", image(album)))
    }

    fun tracks(album: String) = (1..TRACKS.getValue(album).size).map { track(album, it) }

    fun playlist(id: String, name: String, owner: String) = MaItem.from(base("playlist", id, name, id).put("owner", owner))!!

    /** Una playlist della libreria in cui si può aggiungere, del servizio [provider]. */
    fun playlistJson(id: String, name: String, owner: String, provider: String): JSONObject = base("playlist", id, name, id)
        .put("owner", owner).put("is_editable", true)
        .put("provider_mappings", JSONArray().put(JSONObject().put("provider_instance", provider).put("provider_domain", provider.substringBefore("--")).put("item_id", id)))

    fun podcast(id: String, name: String, publisher: String) = MaItem.from(base("podcast", id, name, id).put("publisher", publisher))!!

    fun episode(podcast: String, n: Int, name: String, minutes: Int, resumeMinutes: Int = 0, played: Boolean = false) = MaItem.from(
        base("podcast_episode", "$podcast-$n", name, podcast)
            .put("duration", minutes * 60)
            .put("resume_position_ms", resumeMinutes * 60_000)
            .put("fully_played", played)
            .put("podcast", ref("podcast", podcast, "Morning")),
    )!!

    fun radio(id: String, name: String) = MaItem.from(base("radio", id, name, id))!!

    /** Un genere della libreria (le tessere della Home). */
    fun genre(id: String, name: String, cover: String? = null) = MaItem.from(base("genre", id, name, cover))!!

    /** Una Infinite Mix di Music Assistant: una playlist del servizio builtin ([kind]: infinite_mix o infinite_mix_favorites). */
    fun infiniteMix(kind: String, name: String) = MaItem.from(playlistJson(kind, name, "Music Assistant", "builtin").put("is_editable", false))!!

    /** Una riga dei consigli di un servizio che Xonos non conosce (per esempio i podcast di tendenza). */
    fun serviceRow(provider: String, id: String, name: String) =
        MaItem.from(JSONObject().put("uri", "$provider://folder/$id").put("media_type", "folder").put("item_id", id).put("provider", provider).put("name", name))!!

    fun folder(id: String, name: String, subtitle: String?) =
        MaItem.from(JSONObject().put("uri", "library://folder/$id").put("media_type", "folder").put("item_id", id).put("provider", "recommendations").put("name", name).put("subtitle", subtitle))!!

    /** Il testo sincronizzato del brano che suona nelle scene: righe inventate, una ogni sei secondi. */
    val LYRICS = listOf(
        "Le luci della sera sull'autostrada",
        "il finestrino aperto e il vento caldo",
        "una canzone che conosco a memoria",
        "e la strada che non finisce mai",
        "",
        "Corri, corri verso il mare",
        "non c'è niente da aspettare",
        "il cielo si fa blu cobalto",
        "e la radio canta forte",
        "",
        "Ogni curva un ricordo nuovo",
        "ogni galleria un respiro",
    ).mapIndexed { i, line -> "[00:%02d.00]%s".format(i * 6, line) }.joinToString("\n")

    val ALBUMS = mapOf(
        "emozioni" to Triple("Emozioni", "battisti", 1970),
        "anima" to Triple("Anima latina", "battisti", 1974),
        "canto" to Triple("Il mio canto libero", "battisti", 1972),
        "bollicine" to Triple("Bollicine", "vasco", 1983),
        "studiouno" to Triple("Studio Uno", "mina", 1965),
        "west" to Triple("C'era una volta il West", "morricone", 1968),
        "mare" to Triple("Com'è profondo il mare", "dalla", 1977),
        "padrone" to Triple("La voce del padrone", "battiato", 1981),
        "creuza" to Triple("Crêuza de mä", "deandre", 1984),
        "nero" to Triple("Nero a metà", "daniele", 1980),
    )

    private val TRACKS = mapOf(
        "emozioni" to listOf(
            "Emozioni" to 287, "Acqua azzurra, acqua chiara" to 219, "Mi ritorni in mente" to 230, "Fiori rosa, fiori di pesco" to 283,
            "Il tempo di morire" to 256, "Dieci ragazze" to 185, "Non è Francesca" to 245, "Anna" to 290, "Era" to 201, "7 e 40" to 222,
        ),
        "anima" to listOf("Abbracciala abbracciali abbracciati" to 280, "Due mondi" to 207, "Anima latina" to 362, "Il salame" to 233),
        "canto" to listOf("La luce dell'est" to 304, "Luci-ah" to 271, "L'aquila" to 247, "Il mio canto libero" to 306),
        "bollicine" to listOf("Bollicine" to 340, "Vita spericolata" to 294, "Portatemi Dio" to 251, "Una canzone per te" to 275),
        "studiouno" to listOf("Se telefonando" to 213, "Un anno d'amore" to 207, "Brava" to 176),
        "west" to listOf("C'era una volta il West" to 223, "L'uomo dell'armonica" to 211, "Jill's America" to 167),
        "mare" to listOf("Com'è profondo il mare" to 307, "Il cucciolo Alfredo" to 252),
        "padrone" to listOf("Summer on a solitary beach" to 225, "Bandiera bianca" to 194, "Centro di gravità permanente" to 223),
        "creuza" to listOf("Crêuza de mä" to 376, "Jamin-a" to 290),
        "nero" to listOf("Quanno chiove" to 254, "A me me piace 'o blues" to 230),
    )
}
