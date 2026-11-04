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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.stateDescription
import it.xonos.ma.R
import it.xonos.ma.data.Lyrics
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.LyricsState
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.PlayContext
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
        shape = RoundedCornerShape(CoverCorners.miniPlayer),
        modifier = Modifier.testTag("starting_bar").fillMaxWidth().padding(horizontal = Space.small, vertical = Space.tiny).height(MINI_HEIGHT),
    ) {
        Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Space.medium))
            Text(stringResource(R.string.play_starting), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Alto quanto una pillola: la copertina da 48 dp con 8 dp intorno. */
private val MINI_HEIGHT = 64.dp

/**
 * Il mini-lettore sopra le schede: una pillola del colore della copertina (il suo «contenitore»), con copertina,
 * titolo, play e avanti, e la barra del brano nel colore «vivo» sul bordo basso; toccandolo si apre il lettore.
 * Con TalkBack ha anche le azioni Avanti e Indietro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiniPlayer(now: NowPlaying, player: PlayerLink, model: PhoneModel) {
    ArtTheme(rememberArtPalette(now.artwork)) {
        val art = LocalArt.current
        val position = rememberPosition(player, now)
        val nextLabel = stringResource(R.string.next)
        val previousLabel = stringResource(R.string.previous)
        Surface(
            onClick = { model.playerOpen = true },
            color = art.contenitore,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(CoverCorners.miniPlayer),
            modifier = Modifier.testTag("mini_player")
                .fillMaxWidth()
                .padding(horizontal = Space.small, vertical = Space.tiny)
                .height(MINI_HEIGHT)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { player.next(); true },
                        CustomAccessibilityAction(previousLabel) { player.previous(); true },
                    )
                },
        ) {
            Box {
                Row(Modifier.fillMaxSize().padding(start = Space.small, end = Space.tiny), verticalAlignment = Alignment.CenterVertically) {
                    // Gli angoli della copertina seguono quelli della pillola (20 meno gli 8 dp di margine).
                    Cover(now.artwork, now.mediaType ?: "track", Modifier.size(48.dp), RoundedCornerShape(CoverCorners.miniPlayer - Space.small), 20.dp)
                    Spacer(Modifier.width(Space.medium))
                    Column(Modifier.weight(1f)) {
                        Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val remote = model.remote
                        if (remote != null) {
                            // Su un altro lettore: al posto dell'artista, dove suona.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(XIcons.Speaker, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(Space.tiny))
                                Text(remote.device.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            now.artist?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    PlayPauseButton(now, player, Modifier.size(48.dp), 28.dp, busy = model.starting)
                    IconButton(onClick = player::next) { Icon(XIcons.Next, nextLabel) }
                }
                if (now.durationMs > 0) {
                    // Sul bordo basso, dentro gli angoli tondi. La posizione si legge mentre si disegna: la barra avanza
                    // senza ricomporre il mini-lettore.
                    val vivo = art.vivo
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = CoverCorners.miniPlayer).height(2.dp).drawBehind {
                            drawRect(Color.White.copy(alpha = 0.14f))
                            val fraction = (position.value.toFloat() / now.durationMs).coerceIn(0f, 1f)
                            drawRect(vivo, size = size.copy(width = size.width * fraction))
                        },
                    )
                }
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

/**
 * Il lettore a schermo intero, nei colori della copertina: lo sfondo va dall'alone in alto al «profondo», play e barra
 * sono «vivi». In alto da dove suona ("In riproduzione da · Playlist", si tocca per aprirla).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerScreen(now: NowPlaying, player: PlayerLink, model: PhoneModel, host: Host) {
    ArtTheme(rememberArtPalette(now.artwork)) {
        val art = LocalArt.current
        val background = MaterialTheme.colorScheme.background
        val position = rememberPosition(player, now)
        val problem by player.problem.collectAsState()
        Box(
            Modifier.testTag("player").fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to art.alone.compositeOver(art.profondo),
                        0.55f to art.profondo,
                        1f to lerp(art.profondo, background, 0.35f),
                    ),
                )
                // Il lettore copre tutto: i tocchi non devono arrivare alle schermate sotto.
                .pointerInput(Unit) { detectTapGestures() },
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.big)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.playerOpen = false }) { Icon(XIcons.Down, stringResource(R.string.close_player), Modifier.size(30.dp)) }
                    Box(Modifier.weight(1f).padding(horizontal = Space.small), contentAlignment = Alignment.Center) {
                        now.context?.let { PlayingFrom(it, model) }
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
                Spacer(Modifier.height(Space.gutter))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // Un titolo lungo scorre, ma solo dopo qualche secondo: prima lo si legge com'è. Ai bordi sfuma
                        // invece di tagliare le lettere a metà.
                        Text(
                            now.title,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            modifier = Modifier.fadingSides(TITLE_FADE).basicMarquee(initialDelayMillis = 3_000).padding(horizontal = TITLE_FADE),
                        )
                        now.artist?.let { artist ->
                            Text(
                                artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.72f),
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
                Spacer(Modifier.height(Space.medium))
                SeekBar(now, player, position)
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
                Spacer(Modifier.height(Space.small))
                Controls(now, player, busy = model.starting)
                (player as? RemoteLink)?.let { RemoteVolume(it) }
                Spacer(Modifier.height(Space.medium))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelButton(XIcons.Lyrics, R.string.lyrics, model, PlayerPanel.LYRICS)
                    // Dove suona: toccandolo si sceglie un altro lettore ("Suona su").
                    Box(Modifier.weight(1f).padding(horizontal = Space.small), contentAlignment = Alignment.Center) {
                        if (model.canPlayElsewhere) PlayOnButton(model)
                    }
                    PanelButton(XIcons.Queue, R.string.queue, model, PlayerPanel.QUEUE)
                }
                Spacer(Modifier.height(Space.small))
            }
        }
    }
}

/** Il margine del titolo che sfuma, a sinistra e a destra. */
private val TITLE_FADE = 12.dp

/**
 * "In riproduzione da · Playlist" e sotto il nome. Si tocca per aprire album, playlist, artista, podcast o genere
 * (una radio no: aprirla la farebbe ripartire).
 */
@Composable
private fun PlayingFrom(context: PlayContext, model: PhoneModel) {
    val opens = context.type in OPENABLE_CONTEXTS
    val eyebrow = stringResource(R.string.playing_from) + " · " + kindName(context.type)
    Column(
        Modifier.clip(MaterialTheme.shapes.small)
            .clickable(enabled = opens, role = Role.Button) { model.openUri(context.uri) }
            .padding(horizontal = Space.small, vertical = Space.tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(eyebrow, color = Color.White.copy(alpha = 0.72f))
        Text(context.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** I contesti che si aprono dal lettore (gli altri suonerebbero di nuovo). */
private val OPENABLE_CONTEXTS = setOf("album", "playlist", "artist", "podcast", "genre")

@Composable
private fun BigCover(now: NowPlaying) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val shape = MaterialTheme.shapes.large
        Cover(now.artwork, now.mediaType ?: "track", Modifier.size(side).shadow(32.dp, shape), shape, 96.dp)
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
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = (fraction * now.durationMs).toLong()
        val style = MaterialTheme.typography.labelMedium.tabular()
        Text(clock(shown), style = style, color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.weight(1f))
        Text("-" + clock(now.durationMs - shown), style = style, color = Color.White.copy(alpha = 0.72f))
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
        // Play nel colore «vivo» della copertina, l'icona nel suo «suVivo».
        val onAccent = MaterialTheme.colorScheme.onPrimary
        FilledIconButton(
            onClick = player::playPause,
            enabled = !busy,
            modifier = Modifier.size(72.dp),
            // Spento mentre la musica parte, ma con lo stesso aspetto: si vede la rotella, non un tasto grigio.
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accent,
                contentColor = onAccent,
                disabledContainerColor = accent,
                disabledContentColor = onAccent,
            ),
        ) {
            if (busy || now.playing && now.buffering) {
                Waiting(Modifier.size(32.dp), 3.dp, onAccent)
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
        // Ripeti ha tre stati: acceso o spento lo dice il tasto, quale dei due accesi lo dice questa descrizione.
        val repeatState = when (now.repeat) {
            Repeat.ALL -> stringResource(R.string.repeat_all_state)
            Repeat.ONE -> stringResource(R.string.repeat_one_state)
            Repeat.OFF -> null
        }
        IconToggleButton(
            checked = now.repeat != Repeat.OFF,
            modifier = Modifier.semantics { repeatState?.let { stateDescription = it } },
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
    // Il testo si chiede dopo la composizione, non mentre la si fa: lo stato nato dentro una composizione non ancora
    // applicata perdeva la risposta arrivata nel frattempo (succedeva nel banco delle schermate, con una prima
    // composizione lenta), e la rotella restava per sempre.
    var state by remember(uri) { mutableStateOf<LyricsState?>(null) }
    LaunchedEffect(uri) { state = model.lyrics(uri) }
    when (val load = state?.load ?: Load.Loading) {
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
                // La riga che si canta piena, le altre al 40% (tutte in Manrope 800: cambiando peso le righe andrebbero a capo
                // in un altro punto e il testo salterebbe).
                val alpha = when {
                    !lyrics.synced -> 0.9f
                    i == current -> 1f
                    else -> 0.4f
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
        Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = Space.small))
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

/**
 * Il contenuto sfuma a sinistra e a destra per [edge] invece di essere tagliato di netto. Si allarga di [edge] per parte
 * (chi lo usa mette lo stesso margine dentro): così il testo fermo resta allineato al resto e non sbiadisce.
 */
private fun Modifier.fadingSides(edge: Dp): Modifier = layout { measurable, constraints ->
    val extra = edge.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra) else constraints
    val placeable = measurable.measure(wide)
    val width = (placeable.width - 2 * extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra, 0) }
}.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = edge.toPx()
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade), size = size.copy(width = fade), blendMode = BlendMode.DstIn)
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f),
            size = size.copy(width = fade),
            blendMode = BlendMode.DstIn,
        )
    }

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
