package it.xonos.ma.phone.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.Lyrics
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.PlayerPanel
import it.xonos.ma.phone.model.RemoteLink
import it.xonos.ma.phone.model.Repeat
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * La posizione nel brano, aggiornata spesso mentre suona (per la barra e i testi). È uno stato da
 * leggere solo dove serve: chi la legge si ridisegna cinque volte al secondo, gli altri no.
 */
@Composable
private fun rememberPosition(player: PlayerLink, now: NowPlaying): State<Long> {
    // Anche [player] fra le chiavi: con "Suona su" lo schermo passa dal telefono alla cassa (stesso brano).
    val position = remember(player) { mutableLongStateOf(player.positionMs()) }
    LaunchedEffect(player, now.playing, now.buffering, now.uri) {
        while (true) {
            position.longValue = player.positionMs()
            delay(if (now.playing && !now.buffering) 200 else 1_000)
        }
    }
    return position
}

/** La rotella al posto di play mentre la musica parte; chi usa un lettore di schermo sente "Avvio la musica…". */
@Composable
private fun Waiting(modifier: Modifier, stroke: Dp, color: Color) {
    val text = stringResource(R.string.play_starting)
    CircularProgressIndicator(modifier.semantics { contentDescription = text }, strokeWidth = stroke, color = color)
}

/** Al posto del mini-lettore quando prima non suonava niente: Music Assistant sta preparando la musica. */
@Composable
internal fun StartingBar() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("starting_bar").fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.play_starting), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Il mini-lettore sopra le schede: copertina, titolo, play e avanti; toccandolo si apre il lettore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiniPlayer(now: NowPlaying, player: PlayerLink, model: PhoneModel) {
    val backdrop = backdropOf(now.artwork)
    val position = rememberPosition(player, now)
    Surface(
        onClick = { model.playerOpen = true },
        color = backdrop,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("mini_player").fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(now.artwork, now.mediaType ?: "track", Modifier.size(44.dp), RoundedCornerShape(6.dp), 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val remote = model.remote
                    if (remote != null) {
                        // Su un altro lettore: al posto dell'artista, dove suona.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(XIcons.Speaker, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(remote.device.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        now.artist?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                PlayPauseButton(now, player, Modifier.size(48.dp), 28.dp, busy = model.starting)
                IconButton(onClick = player::next) { Icon(XIcons.Next, stringResource(R.string.next)) }
            }
            if (now.durationMs > 0) {
                // La posizione si legge mentre si disegna: la barra avanza senza ricomporre il mini-lettore.
                Box(
                    Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp).drawBehind {
                        drawRect(Color.White.copy(alpha = 0.2f))
                        val fraction = (position.value.toFloat() / now.durationMs).coerceIn(0f, 1f)
                        drawRect(Color.White, size = size.copy(width = size.width * fraction))
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(now: NowPlaying, player: PlayerLink, modifier: Modifier, iconSize: Dp, busy: Boolean = false) {
    // Mentre Music Assistant prepara un "suona" un play qui farebbe ripartire il brano da capo (resume).
    IconButton(onClick = player::playPause, modifier = modifier, enabled = !busy) {
        if (busy || now.playing && now.buffering) {
            Waiting(Modifier.size(iconSize - 4.dp), 2.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            Icon(
                if (now.playing) XIcons.Pause else XIcons.Play,
                stringResource(if (now.playing) R.string.pause else R.string.play),
                Modifier.size(iconSize),
            )
        }
    }
}

/** Il lettore a schermo intero, con lo sfondo del colore della copertina. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerScreen(now: NowPlaying, player: PlayerLink, model: PhoneModel, host: Host) {
    val backdrop = backdropOf(now.artwork)
    val background = MaterialTheme.colorScheme.background
    val position = rememberPosition(player, now)
    val problem by player.problem.collectAsState()
    Box(
        Modifier.testTag("player").fillMaxSize()
            .background(Brush.verticalGradient(listOf(backdrop, backdrop.copy(alpha = 0.55f).compositeOver(background), background)))
            // Il lettore copre tutto: i tocchi non devono arrivare alle schermate sotto.
            .pointerInput(Unit) { detectTapGestures() },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.playerOpen = false }) { Icon(XIcons.Down, stringResource(R.string.close_player), Modifier.size(30.dp)) }
                // Dove suona: toccandolo si sceglie un altro lettore ("Suona su").
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    if (model.canPlayElsewhere) PlayOnButton(model)
                }
                NowPlayingMenu(now, model)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Crossfade(model.playerPanel, label = "panel") { shown ->
                    when (shown) {
                        PlayerPanel.COVER -> BigCover(now)
                        PlayerPanel.LYRICS -> LyricsPanel(now, model, player, position, host)
                        PlayerPanel.QUEUE -> QueuePanel(now, player)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        // Un titolo lungo scorre, ma solo dopo qualche secondo: prima lo si legge com'è.
                        modifier = Modifier.basicMarquee(initialDelayMillis = 3_000),
                    )
                    now.artist?.let { artist ->
                        Text(
                            artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = now.artistUri != null) { now.artistUri?.let(model::openUri) },
                        )
                    }
                }
                if (now.uri != null && now.mediaType == "track") {
                    IconButton(onClick = { player.setFavorite(!now.favorite) }) {
                        Icon(
                            if (now.favorite) XIcons.Favorite else XIcons.FavoriteBorder,
                            stringResource(if (now.favorite) R.string.favorite_remove else R.string.favorite_add),
                            tint = if (now.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SeekBar(now, player, position)
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Controls(now, player, busy = model.starting)
            (player as? RemoteLink)?.let { RemoteVolume(it) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelButton(XIcons.Lyrics, R.string.lyrics, model, PlayerPanel.LYRICS)
                PanelButton(XIcons.Queue, R.string.queue, model, PlayerPanel.QUEUE)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BigCover(now: NowPlaying) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        Cover(now.artwork, now.mediaType ?: "track", Modifier.size(side).shadow(32.dp, RoundedCornerShape(16.dp)), RoundedCornerShape(16.dp), 96.dp)
    }
}

@Composable
private fun SeekBar(now: NowPlaying, player: PlayerLink, position: State<Long>) {
    if (now.durationMs <= 0 || !now.canSeek) {
        // Una radio: niente barra, solo "in diretta".
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live), style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (position.value.toFloat() / now.durationMs).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seekTo((it * now.durationMs).toLong()) }
            dragging = null
        },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.onSurface,
            activeTrackColor = MaterialTheme.colorScheme.onSurface,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = (fraction * now.durationMs).toLong()
        Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        Text("-" + clock(now.durationMs - shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun Controls(now: NowPlaying, player: PlayerLink, busy: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        // Tasti a due stati (IconToggleButton): chi usa TalkBack sente se sono accesi, non solo il nome.
        IconToggleButton(checked = now.shuffle, onCheckedChange = { player.setShuffle(it) }) {
            Icon(XIcons.Shuffle, stringResource(R.string.shuffle), tint = if (now.shuffle) accent else normal.copy(alpha = 0.7f))
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
            Icon(XIcons.Previous, stringResource(R.string.previous), Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = player::playPause,
            enabled = !busy,
            modifier = Modifier.size(76.dp),
            // Spento mentre la musica parte, ma con lo stesso aspetto: si vede la rotella, non un tasto grigio.
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = normal,
                contentColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = normal,
                disabledContentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            if (busy || now.playing && now.buffering) {
                Waiting(Modifier.size(32.dp), 3.dp, MaterialTheme.colorScheme.background)
            } else {
                Icon(
                    if (now.playing) XIcons.Pause else XIcons.Play,
                    stringResource(if (now.playing) R.string.pause else R.string.play),
                    Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
            Icon(XIcons.Next, stringResource(R.string.next), Modifier.size(36.dp))
        }
        IconToggleButton(
            checked = now.repeat != Repeat.OFF,
            onCheckedChange = {
                player.setRepeat(
                    when (now.repeat) {
                        Repeat.OFF -> Repeat.ALL
                        Repeat.ALL -> Repeat.ONE
                        Repeat.ONE -> Repeat.OFF
                    },
                )
            },
        ) {
            Icon(
                if (now.repeat == Repeat.ONE) XIcons.RepeatOne else XIcons.Repeat,
                stringResource(R.string.repeat),
                tint = if (now.repeat != Repeat.OFF) accent else normal.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: Int, model: PhoneModel, panel: PlayerPanel) {
    val selected = model.playerPanel == panel
    IconToggleButton(
        checked = selected,
        onCheckedChange = { model.playerPanel = if (selected) PlayerPanel.COVER else panel },
        modifier = Modifier.clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent),
    ) {
        Icon(icon, stringResource(label), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
    }
}

/** I tre puntini del lettore: in una playlist, vai all'album, vai all'artista. */
@Composable
private fun NowPlayingMenu(now: NowPlaying, model: PhoneModel) {
    val track = now.uri?.takeIf { now.mediaType == "track" }
    if (track == null && now.albumUri == null && now.artistUri == null) {
        Spacer(Modifier.width(48.dp))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(XIcons.More, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            track?.let { uri ->
                MenuEntry(XIcons.PlaylistAdd, stringResource(R.string.add_to_playlist)) {
                    open = false
                    model.addToPlaylist(uri)
                }
            }
            now.albumUri?.let { uri ->
                MenuEntry(XIcons.Album, stringResource(R.string.go_to_album)) {
                    open = false
                    model.openUri(uri)
                }
            }
            now.artistUri?.let { uri ->
                MenuEntry(XIcons.Artist, stringResource(R.string.go_to_artist)) {
                    open = false
                    model.openUri(uri)
                }
            }
        }
    }
}

/** Il testo del brano: con i tempi segue la musica e si può toccare una riga per andarci. */
@Composable
private fun LyricsPanel(now: NowPlaying, model: PhoneModel, player: PlayerLink, position: State<Long>, host: Host) {
    val uri = now.uri?.takeIf { now.mediaType == "track" }
    if (uri == null) {
        Empty(stringResource(R.string.lyrics_none))
        return
    }
    val state = remember(uri) { model.lyrics(uri) }
    when (val load = state.load) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Problem(host.describe(load.error), null)
        is Load.Ready -> {
            val lyrics = load.value
            if (lyrics == null || lyrics.lines.isEmpty()) Empty(stringResource(R.string.lyrics_none)) else LyricsLines(lyrics, position, player)
        }
    }
}

@Composable
private fun LyricsLines(lyrics: Lyrics, position: State<Long>, player: PlayerLink) {
    val list = rememberLazyListState()
    // Un attimo prima: la riga si accende quando la si sta per cantare. Si ricompone solo quando cambia riga.
    val current by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value + LYRICS_LEAD_MS) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val third = maxHeight / 3
        LaunchedEffect(current) {
            if (lyrics.synced && current >= 0) list.animateScrollToItem(current)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().fadingEdges(),
            // La riga in corso resta a un terzo dall'alto.
            contentPadding = PaddingValues(top = if (lyrics.synced) third else 16.dp, bottom = maxHeight / 2),
        ) {
            itemsIndexed(lyrics.lines, contentType = { _, _ -> "line" }) { i, line ->
                val alpha = when {
                    !lyrics.synced -> 0.9f
                    i == current -> 1f
                    i < current -> 0.4f
                    else -> 0.6f
                }
                Text(
                    line.text.ifEmpty { "♪" },
                    style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = lyrics.synced) { player.seekTo(line.timeMs) }
                        .padding(vertical = if (lyrics.synced) 8.dp else 2.dp),
                )
            }
        }
    }
}

/** I prossimi brani della coda: toccandone uno si salta lì. */
@Composable
private fun QueuePanel(now: NowPlaying, player: PlayerLink) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(max(0, now.queueIndex)) }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(now.queue, contentType = { _, _ -> "entry" }) { i, entry ->
                val current = i == now.queueIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (current) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable { player.skipTo(i) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(entry.artwork, "track", Modifier.size(44.dp), RoundedCornerShape(6.dp), 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (i < now.queueIndex) 0.5f else 1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.artist?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    when {
                        current -> Icon(XIcons.Equalizer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        i >= now.queueEditableFrom -> QueueEntryMenu(i, now, player)
                    }
                }
            }
        }
    }
}

/** I tre puntini di un brano in arrivo: suona dopo (se non è già il prossimo) e togli dalla coda. */
@Composable
private fun QueueEntryMenu(index: Int, now: NowPlaying, player: PlayerLink) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp).testTag("queue_menu")) {
            Icon(XIcons.More, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (index > now.queueIndex + 1) {
                MenuEntry(XIcons.PlayNext, stringResource(R.string.play_next)) {
                    open = false
                    player.playNextInQueue(index)
                }
            }
            MenuEntry(XIcons.Close, stringResource(R.string.remove_from_queue)) {
                open = false
                player.removeFromQueue(index)
            }
        }
    }
}

private const val LYRICS_LEAD_MS = 250L

/** Il testo sfuma in alto e in basso invece di essere tagliato di netto. */
private fun Modifier.fadingEdges(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = size.height * 0.12f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = edge), blendMode = BlendMode.DstIn)
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - edge, endY = size.height),
            topLeft = Offset(0f, size.height - edge),
            blendMode = BlendMode.DstIn,
        )
    }
