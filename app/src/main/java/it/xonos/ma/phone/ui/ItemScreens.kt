package it.xonos.ma.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.phone.model.ArtistPage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.ListPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.value
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Album, playlist, podcast o genere: copertina grande, pulsanti e l'elenco dei brani (o episodi). */
@Composable
internal fun ItemScreen(page: ItemPage, model: PhoneModel, player: PlayerLink, host: Host) {
    val item = page.item
    val cover = model.image(item.image, LARGE_ART)
    // Solo quale brano suona: il resto del lettore (posizione, coda...) non deve ridisegnare l'elenco.
    val playingUri by remember(player) { player.nowPlaying.map { it?.uri }.distinctUntilChanged() }
        .collectAsState(player.nowPlaying.value?.uri)
    val state = rememberLazyListState()
    val tracks = page.tracks.value.orEmpty()
    // Nei podcast l'ordine è quello delle puntate, non un "da qui in poi".
    val numbered = item.mediaType == "album"
    // Nelle playlist che si possono modificare i brani si tolgono dal loro menu.
    val editable = model.canEdit(item)
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("item_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header", contentType = "header") {
                Header(
                    title = item.name,
                    cover = cover,
                    mediaType = item.mediaType,
                    lines = listOfNotNull(
                        item.subtitle,
                        listOfNotNull(kindName(item.mediaType), if (tracks.isNotEmpty() && item.mediaType != "podcast") tracksSummary(tracks) else null)
                            .joinToString(" · "),
                    ),
                    onFirstLine = item.artists.firstOrNull()?.let { artist -> { model.openUri(artist.uri) } },
                )
                PlayBar(
                    onPlay = { if (item.mediaType == "genre") model.playAll(tracks, false) else model.play(item) },
                    // Le puntate di un podcast non si mescolano.
                    onShuffle = when (item.mediaType) {
                        "podcast" -> null
                        "genre" -> ({ model.playAll(tracks, true) })
                        else -> ({ model.play(item, shuffle = true) })
                    },
                    favorite = page.favorite.takeIf { item.mediaType != "genre" },
                    onFavorite = { model.setFavorite(page, it) },
                    menu = { ItemMenu(item, model, onDelete = if (model.canDelete(item)) ({ confirmDelete = true }) else null) },
                )
            }
            loaded(page.tracks, host, { model.reload(page) }) { list ->
                itemsIndexed(list, key = { i, it -> "$i|${it.uri}" }, contentType = { _, _ -> "track" }) { i, track ->
                    ItemRow(
                        track,
                        model,
                        { model.play(list, i, page.context) },
                        number = if (numbered) track.trackNumber.takeIf { it > 0 } ?: (i + 1) else null,
                        subtitle = if (numbered) track.artists.joinToString(", ") { it.name }.ifEmpty { null } else track.subtitle,
                        current = playingUri == track.uri,
                        showAlbum = item.mediaType != "album",
                        onRemove = if (editable) ({ model.removeFromPlaylist(page, track) }) else null,
                    )
                }
            }
            if (page.more) {
                item(key = "more", contentType = "more") { More() }
            }
            if (item.mediaType == "genre") {
                loaded(page.albums, host, { model.reload(page) }) { albums ->
                    if (albums.isNotEmpty()) {
                        item(key = "albums", contentType = "row") {
                            CardRow(stringResource(R.string.cat_albums), albums, model) { _, album -> model.open(album) }
                        }
                    }
                }
            }
        }
        TopBar(item.name, state, model)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.delete_playlist_confirm, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    model.deletePlaylist(page)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.flow_cancel)) } },
        )
    }
}

/** Un artista: foto, i brani più ascoltati, gli album (dal più recente) e tutti i brani. */
@Composable
internal fun ArtistScreen(page: ArtistPage, model: PhoneModel, host: Host) {
    val artist = page.artist
    val state = rememberLazyListState()
    val topTitle = stringResource(R.string.top_tracks)
    val allTitle = stringResource(R.string.all_tracks)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("item_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header", contentType = "header") {
                Header(artist.name, model.image(artist.image, LARGE_ART), "artist", listOf(kindName("artist")), coverSize = 196.dp)
                PlayBar(
                    onPlay = { model.play(artist) },
                    onShuffle = { model.play(artist, shuffle = true) },
                    favorite = page.favorite,
                    onFavorite = { model.setFavorite(page, it) },
                )
            }
            val top = page.top.value.orEmpty()
            if (top.isNotEmpty()) {
                item(key = "top", contentType = "title") {
                    SectionTitle(topTitle, onSeeAll = if (top.size > SHOWN_TOP) ({ model.openArtistTracks(page, topTitle, top = true) }) else null)
                }
                itemsIndexed(top.take(SHOWN_TOP), key = { i, it -> "top$i|${it.uri}" }, contentType = { _, _ -> "track" }) { i, track ->
                    ItemRow(track, model, { model.play(top, i, null) }, number = i + 1)
                }
            }
            loaded(page.albums, host, { model.reload(page) }) { albums ->
                if (albums.isNotEmpty()) {
                    item(key = "albums", contentType = "row") {
                        CardRow(stringResource(R.string.cat_albums), albums, model) { _, album -> model.open(album) }
                    }
                }
            }
            // La discografia dei servizi arriva dopo quella della libreria.
            if (page.albumsMore) {
                item(key = "albums_more", contentType = "more") { More() }
            }
            // Tutti i brani si chiedono solo quando si aprono: fino ad allora la riga non dice quanti sono. Se poi non ce
            // n'è nessuno, la riga sparisce.
            val tracks = page.tracks?.value
            if (tracks == null || tracks.isNotEmpty()) {
                item(key = "all", contentType = "all") {
                    SectionTitle(allTitle)
                    Row(
                        Modifier.fillMaxWidth().clickable { model.openArtistTracks(page, allTitle, top = false) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(XIcons.Track, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            if (tracks != null) tracksSummary(tracks) else stringResource(R.string.see_all),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(XIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TopBar(artist.name, state, model)
    }
}

/** Un elenco qualsiasi: brani di un artista, una riga di "Per te", una cartella, tutti i risultati di un tipo. */
@Composable
internal fun ListScreen(page: ListPage, model: PhoneModel, host: Host) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("item_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header", contentType = "header") {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 4.dp)) {
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    page.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val items = page.items.value.orEmpty()
                if (items.any { it.mediaType in PhoneModel.TRACK_LIKE }) {
                    PlayBar(onPlay = { model.playAll(items, false) }, onShuffle = { model.playAll(items, true) })
                }
            }
            loaded(page.items, host, { model.reload(page) }) { items ->
                if (items.isEmpty()) {
                    item { Empty() }
                }
                itemsIndexed(items, key = { i, it -> "$i|${it.uri}" }, contentType = { _, _ -> "track" }) { i, item ->
                    ItemRow(item, model, { model.play(items, i, null) })
                }
            }
            // Gli elenchi a pezzi (i brani di un artista): prima la libreria, poi i servizi.
            if (page.more) {
                item(key = "more", contentType = "more") { More() }
            }
        }
        TopBar(page.title, state, model)
    }
}

/**
 * La testata di una schermata: lo sfondo prende il colore della copertina e sfuma nel nero;
 * sotto, il titolo e le righe di dettaglio. La prima riga (l'artista) si può toccare.
 */
@Composable
private fun Header(
    title: String,
    cover: String?,
    mediaType: String,
    lines: List<String>,
    onFirstLine: (() -> Unit)? = null,
    coverSize: Dp = 232.dp,
) {
    val backdrop = backdropOf(cover)
    val background = MaterialTheme.colorScheme.background
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(backdrop, backdrop.copy(alpha = 0.6f).compositeOver(background), background)))
            .statusBarsPadding()
            .padding(top = 56.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = coverShape(mediaType, 12.dp)
        Cover(cover, mediaType, Modifier.size(coverSize).shadow(24.dp, shape), shape, 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
            Spacer(Modifier.height(4.dp))
            val clickable = i == 0 && onFirstLine != null
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                color = if (i == 0) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (clickable) Modifier.clip(RoundedCornerShape(6.dp)).clickable { onFirstLine?.invoke() }.padding(4.dp) else Modifier,
            )
        }
    }
}

/**
 * Il pulsante per tornare indietro, sopra la testata. Quando la testata esce scorrendo, la barra
 * diventa piena e mostra il titolo.
 */
@Composable
private fun TopBar(title: String, state: LazyListState, model: PhoneModel) {
    val solid by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 600 } }
    val background = MaterialTheme.colorScheme.surfaceContainer
    Row(
        Modifier.fillMaxWidth()
            .background(if (solid) background else Color.Transparent)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { model.back() },
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (solid) Color.Transparent else Color.Black.copy(alpha = 0.25f)),
        ) {
            Icon(XIcons.Back, stringResource(R.string.back))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).graphicsLayer { alpha = if (solid) 1f else 0f },
        )
    }
}

/** Sotto un elenco di cui arrivano ancora dei pezzi. */
@Composable
private fun More() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** Una parte di elenco che si carica: rotella, errore con "Riprova", o il contenuto. */
private fun <T> LazyListScope.loaded(
    load: Load<T>,
    host: Host,
    onRetry: () -> Unit,
    content: LazyListScope.(T) -> Unit,
) {
    when (load) {
        is Load.Ready -> content(load.value)
        else -> item(contentType = "loading") { Loaded(load, host::describe, onRetry) {} }
    }
}

private const val SHOWN_TOP = 5
