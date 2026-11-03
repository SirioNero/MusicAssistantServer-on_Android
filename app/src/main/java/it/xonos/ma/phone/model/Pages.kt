package it.xonos.ma.phone.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.xonos.ma.data.Device
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaItem
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

/** Qualcosa che si carica dal server: in corso, non riuscito o pronto. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val error: Throwable) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** Il valore, se è pronto. */
val <T> Load<T>.value: T? get() = (this as? Load.Ready)?.value

/**
 * Una schermata nella pila di una scheda. Tiene i suoi dati, così tornando indietro li ritrova;
 * [id] serve allo schermo per ricordare dove si era arrivati scorrendo.
 */
sealed class Page {
    val id: Long = ids.incrementAndGet()

    /** Vero dopo il primo caricamento: il PhoneModel non lo ripete. */
    internal var started = false

    private companion object {
        val ids = AtomicLong()
    }
}

/** La prima scheda: ascolti recenti e i consigli di Music Assistant ("Per te"). */
class HomePage : Page() {
    var recent by mutableStateOf<Load<List<MaItem>>>(Load.Loading)
    var rows by mutableStateOf<Load<List<Row>>>(Load.Loading)

    /** Vero mentre si aggiorna trascinando in giù: intanto resta quello di prima. */
    var refreshing by mutableStateOf(false)

    /** Quando si è caricata l'ultima volta (System.currentTimeMillis). */
    internal var loadedAt = 0L

    /** Vero quando ciò che si vede è arrivato da Music Assistant, non dalla Home salvata sul disco. */
    internal var fresh = false
    internal var job: Job? = null

    /** Una riga di consigli: il suo titolo e cosa c'è dentro. */
    class Row(val row: MaItem, items: Load<List<MaItem>>) {
        var items by mutableStateOf(items)
    }
}

/** Le voci della libreria, con l'ordine possibile e se si mostrano a griglia. */
enum class Shelf(
    val kind: String,
    val grid: Boolean = true,
    val favorite: Boolean = false,
    val orders: List<String> = listOf(ORDER_NAME, ORDER_ADDED, ORDER_PLAYED),
) {
    PLAYLISTS("playlists"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    TRACKS("tracks", grid = false),
    FAVORITES("tracks", grid = false, favorite = true, orders = listOf(ORDER_ADDED, ORDER_NAME)),
    PODCASTS("podcasts"),
    AUDIOBOOKS("audiobooks"),
    RADIOS("radios", orders = listOf(ORDER_NAME, ORDER_PLAYED)),
    GENRES("genres", orders = listOf(ORDER_NAME)),

    /** I servizi e le loro cartelle: un elenco solo, senza pagine. */
    BROWSE("browse", grid = false, orders = emptyList()),
}

const val ORDER_NAME = "sort_name"
const val ORDER_ADDED = "timestamp_added_desc"
const val ORDER_PLAYED = "last_played_desc"

/** Un elenco della libreria che si carica a pagine mentre si scorre. */
class ShelfList(val shelf: Shelf) {
    var order by mutableStateOf(shelf.orders.firstOrNull() ?: ORDER_NAME)
    val items = mutableStateListOf<MaItem>()
    var loading by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var refreshing by mutableStateOf(false)
    internal var job: Job? = null

    /** Vero dopo la prima volta che lo schermo l'ha mostrato (vedi PhoneModel.show). */
    internal var shown = false

    /** Vero mentre gli elementi sono quelli salvati sul disco, finché non arriva la prima pagina nuova. */
    internal var stale = false

    /** Il file della cache con la prima pagina, per voce e ordine. */
    internal val cacheName: String get() = "shelf-${shelf.name.lowercase()}-$order"
}

/** La scheda Libreria: le voci che hanno qualcosa (null finché non si sa) e quella scelta. */
class LibraryPage : Page() {
    var shelves by mutableStateOf<List<Shelf>?>(null)
    var shelf by mutableStateOf(Shelf.PLAYLISTS)
    internal val lists = mutableMapOf<Shelf, ShelfList>()
}

/** Album, playlist, podcast o genere: i suoi brani (o episodi); per un genere anche gli album. */
class ItemPage(val item: MaItem) : Page() {
    var tracks by mutableStateOf<Load<List<MaItem>>>(Load.Loading)

    /** Vero mentre arrivano altri brani: quelli già arrivati si vedono (le playlist lunghe dei servizi). */
    var more by mutableStateOf(false)
    var albums by mutableStateOf<Load<List<MaItem>>>(Load.Ready(emptyList()))
    var favorite by mutableStateOf(item.favorite)

    /** Dentro un album o una playlist un brano fa partire tutto da lì; nei podcast ogni episodio suona da solo. */
    val context: String? get() = item.uri.takeIf { item.mediaType == "album" || item.mediaType == "playlist" }
}

/** Un artista: i più ascoltati, gli album (della libreria e di tutti i servizi) e tutti i brani. */
class ArtistPage(val artist: MaItem) : Page() {
    var top by mutableStateOf<Load<List<MaItem>>>(Load.Loading)
    var albums by mutableStateOf<Load<List<MaItem>>>(Load.Loading)

    /**
     * Tutti i brani: null finché non li si apre. Si chiedono solo allora (PhoneModel.openArtistTracks): per un artista
     * di Spotify sono circa tre richieste per album, e Spotify blocca chi ne fa troppe. Arrivati, restano qui: tornando
     * all'artista la riga dice quanti sono, e riaprendoli non si richiedono.
     */
    var tracks by mutableStateOf<Load<List<MaItem>>?>(null)

    /** Vero mentre rispondono ancora dei servizi: intanto si vede la libreria. */
    var albumsMore by mutableStateOf(false)
    var favorite by mutableStateOf(artist.favorite)
}

/**
 * Un elenco qualsiasi: i brani di un artista, una riga di "Per te", una cartella di un servizio,
 * tutti i risultati di un tipo. [source] lo carica; se l'elenco arriva a pezzi (i brani di un artista, prima dalla
 * libreria e poi da ogni servizio), li passa alla funzione che riceve, e quelli arrivati si vedono subito.
 */
class ListPage(
    val title: String,
    val subtitle: String?,
    val image: MaImage?,
    internal val source: suspend (part: (List<MaItem>) -> Unit) -> List<MaItem>,
) : Page() {
    var items by mutableStateOf<Load<List<MaItem>>>(Load.Loading)

    /** Vero mentre arrivano altri elementi: quelli già arrivati si vedono. */
    var more by mutableStateOf(false)
}

/** La ricerca: ciò che si è scritto e i risultati divisi per tipo. */
class SearchPage : Page() {
    var query by mutableStateOf("")

    /** Null prima di cercare; mentre arrivano i risultati nuovi restano quelli di prima. */
    var results by mutableStateOf<Load<SearchResults>?>(null)
    var searching by mutableStateOf(false)
    internal var job: Job? = null
}

/** I risultati di una ricerca: il migliore e poi tutti, per tipo ("tracks", "artists", ...). */
data class SearchResults(val query: String, val best: MaItem?, val byType: Map<String, List<MaItem>>)

/**
 * Le impostazioni: indirizzi, accesso (nome utente e password, o il token), nome del lettore, e
 * le prove della connessione.
 */
class SettingsPage(home: String, remote: String, token: String, name: String, signedIn: String?) : Page() {
    var home by mutableStateOf(home)
    var remote by mutableStateOf(remote)
    var token by mutableStateOf(token)
    var name by mutableStateOf(name)
    var username by mutableStateOf(signedIn.orEmpty())

    /** Non si salva mai: serve solo per farsi dare il token. */
    var password by mutableStateOf("")

    /** Chi ha fatto l'accesso l'ultima volta (null: si è usato un token copiato). */
    var signedIn by mutableStateOf(signedIn)

    /** Accedere con un token copiato da Music Assistant invece che con nome utente e password. */
    var useToken by mutableStateOf(token.isNotBlank() && signedIn == null)

    /** Il server non ha ancora account: si propone di creare il primo. */
    var setupNeeded by mutableStateOf(false)

    /** La password per il nuovo account è troppo corta. */
    var passwordShort by mutableStateOf(false)
    var checking by mutableStateOf(false)

    /** Vero dopo il primo "Salva": da lì si dice se manca qualcosa. */
    var tried by mutableStateOf(false)
    var results by mutableStateOf<List<ConnectionCheck.Result>>(emptyList())

    /** Perché Music Assistant sul telefono non è partito, se è successo. */
    var phoneServerError by mutableStateOf<String?>(null)

    /** Il pacchetto di Music Assistant sul telefono è scaricato (nella versione che serve). */
    var phoneServerInstalled by mutableStateOf(false)

    /** La domanda prima di scaricarlo, con quanto pesa (null finché non lo si sa). */
    var phoneServerAsk by mutableStateOf(false)
    var phoneServerSize by mutableStateOf<Long?>(null)

    /** Mentre si scarica: byte arrivati e totale (-1 se non si sa). */
    var phoneServerProgress by mutableStateOf<Pair<Long, Long>?>(null)

    /** Perché il download non è riuscito, se è successo. */
    var phoneServerDownloadError by mutableStateOf<String?>(null)
}

/** "Aggiungi a una playlist": dove mettere [uri] (un brano, un album o una playlist), o una playlist nuova. */
class PlaylistPicker(val uri: String) {
    var playlists by mutableStateOf<Load<List<MaItem>>>(Load.Loading)

    /** Mentre si crea la playlist nuova. */
    var creating by mutableStateOf(false)
}

/** La scelta "Suona su": i lettori di Music Assistant e il telefono. */
class DevicePicker {
    var devices by mutableStateOf<Load<List<Device>>>(Load.Loading)

    /** Il lettore verso cui si sta spostando la musica ([PhoneModel.PHONE] per il telefono). */
    var moving by mutableStateOf<String?>(null)
}
