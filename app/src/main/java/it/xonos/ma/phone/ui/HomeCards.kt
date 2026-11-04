package it.xonos.ma.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.xonos.ma.R
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.MaItem
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.PhoneModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

// Le card della Home (vedi docs/per-te-piano.md, capitoli 2 e 3): l'eroe "Riprendi", le tessere delle Scelte rapide,
// le card del carosello Per te e delle righe, quelle di "In corso", le tessere dei generi e gli scheletri che le
// sostituiscono mentre arrivano. La Home le mette in fila (HomeScreen.kt).

/** L'altezza dell'eroe e della sua copertina. */
private val HERO_HEIGHT = 184.dp
private val HERO_COVER = 120.dp

/** Le Scelte rapide: tessere alte 56 dp con gli angoli da 10. */
private val TILE_HEIGHT = 56.dp
private val TILE_CORNER = 10.dp

/** Le card quadrate del carosello Per te. */
private val MIX_CARD = 168.dp

/** Le card delle righe: album e playlist 148 dp, brani 132 (Riscopri). */
internal val CARD_WIDTH = 148.dp
internal val TRACK_CARD_WIDTH = 132.dp

/** Le card di "In corso". */
private val PROGRESS_WIDTH = 280.dp
private val PROGRESS_HEIGHT = 88.dp

/** Le tessere dei generi. */
private val GENRE_WIDTH = 168.dp
private val GENRE_HEIGHT = 96.dp
private val GENRE_CORNER = 16.dp

/** Le card che cambiano (arriva Music Assistant dopo la Home salvata) compaiono e spariscono in 200 ms. */
private const val CARD_FADE_MS = 200

/** Trascinato a sinistra oltre questa parte della sua larghezza, l'eroe si nasconde. */
private const val HERO_SWIPE = 0.4f

// ---------- Riprendi ----------

/**
 * L'eroe "Riprendi": la copertina, quando, il titolo, l'artista e da dove suonava, la barra con il punto (o quanto manca
 * per podcast e audiolibri), i tre brani dopo, e i tasti Riprendi, Apri e il menu (Ricomincia, Nascondi). Nei colori
 * della sua copertina. Trascinato a sinistra si nasconde fino al prossimo ascolto.
 */
@Composable
internal fun HeroResume(listen: LastListen, page: HomePage, model: PhoneModel, modifier: Modifier = Modifier) {
    val url = model.image(listen.track.image ?: listen.source?.image, LARGE_ART)
    ArtTheme(rememberArtPalette(url)) {
        val art = LocalArt.current
        val scope = rememberCoroutineScope()
        val offset = remember(listen.key) { Animatable(0f) }
        var width by remember { mutableIntStateOf(1) }
        val restartLabel = stringResource(R.string.hero_restart)
        val hideLabel = stringResource(R.string.hero_hide)
        val soft = Color.White.copy(alpha = SOFT_TEXT)
        Box(
            modifier.testTag("hero_resume")
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .height(HERO_HEIGHT)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .graphicsLayer {
                    translationX = offset.value
                    alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f)
                }
                .draggable(
                    rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtMost(0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = {
                        if (offset.value < -width * HERO_SWIPE) {
                            offset.animateTo(-width.toFloat(), tween(Motion.PAGE_BACK_MS))
                            model.hideResume(page)
                        } else {
                            offset.animateTo(0f, tween(Motion.PAGE_BACK_MS))
                        }
                    },
                )
                .clip(RoundedCornerShape(CoverCorners.hero))
                .background(Brush.horizontalGradient(listOf(art.contenitore, art.profondo)))
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(restartLabel) { model.resume(page, restart = true); true },
                        CustomAccessibilityAction(hideLabel) { model.hideResume(page); true },
                    )
                },
        ) {
            Column(Modifier.fillMaxSize().padding(start = Space.large, end = Space.large, top = Space.large, bottom = Space.medium)) {
                Row(Modifier.height(HERO_COVER)) {
                    Cover(url, listen.track.mediaType, Modifier.size(HERO_COVER), RoundedCornerShape(CoverCorners.hero - Space.small), 44.dp)
                    Spacer(Modifier.width(Space.medium))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Eyebrow(stringResource(R.string.hero_eyebrow, ago(listen.timeMs)), color = soft)
                        Text(
                            listen.track.name,
                            style = MaterialTheme.typography.titleMedium.copy(lineHeight = 20.sp),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        heroSubtitle(listen)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.weight(1f))
                        HeroButtons(listen, page, model)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val fraction = when {
                    listen.ended -> 1f
                    listen.track.durationSec > 0 -> (listen.positionSec / listen.track.durationSec).toFloat().coerceIn(0f, 1f)
                    else -> null
                }
                if (fraction != null) {
                    ProgressBar(fraction, art.vivo, Color.White.copy(alpha = 0.18f), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    heroTime(listen)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall.tabular(), color = soft, maxLines = 1)
                        Spacer(Modifier.width(Space.small))
                    }
                    val then = listen.next.takeIf { !listen.ended && it.isNotEmpty() }
                    if (then != null) {
                        Text(
                            stringResource(R.string.hero_then) + " " + then.joinToString(" · ") { it.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = soft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Riprendi (o Ascolta di nuovo), Apri (se c'è un posto da aprire e lo spazio) e il menu con Apri, Ricomincia e Nascondi. */
@Composable
private fun HeroButtons(listen: LastListen, page: HomePage, model: PhoneModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = stringResource(if (listen.ended) R.string.hero_listen_again else R.string.hero_resume)
        val description = stringResource(R.string.hero_a11y, listen.track.name)
        Button(
            onClick = { model.resume(page) },
            enabled = !model.starting,
            modifier = Modifier.height(48.dp).testTag("hero_resume_play").semantics { contentDescription = description },
            contentPadding = PaddingValues(start = Space.medium, end = Space.large),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (model.starting) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(XIcons.Play, null, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1)
        }
        // "Apri" prende lo spazio che resta e, se non basta (tedesco, greco, russo), si accorcia: il menu resta sempre.
        Box(Modifier.weight(1f)) {
            if (PhoneModel.resumeContext(listen) != null) {
                TextButton(onClick = { model.openResume(page) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Text(stringResource(R.string.hero_open), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(XIcons.More, stringResource(R.string.more), tint = Color.White) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // Anche qui: dove "Riprendi" è una parola lunga il tasto "Apri" non ci sta.
                if (PhoneModel.resumeContext(listen) != null) {
                    MenuEntry(XIcons.OpenInNew, stringResource(R.string.hero_open)) {
                        open = false
                        model.openResume(page)
                    }
                }
                MenuEntry(XIcons.Refresh, stringResource(R.string.hero_restart)) {
                    open = false
                    model.resume(page, restart = true)
                }
                MenuEntry(XIcons.Hidden, stringResource(R.string.hero_hide)) {
                    open = false
                    model.hideResume(page)
                }
            }
        }
    }
}

/** "Artista · dalla playlist Viaggio in auto"; senza sorgente l'artista e l'album. */
@Composable
private fun heroSubtitle(listen: LastListen): String? {
    val source = listen.source?.takeIf { it.uri != listen.track.uri && it.name.isNotBlank() }
    val from = source?.let {
        when (it.mediaType) {
            "playlist" -> stringResource(R.string.hero_from_playlist, it.name)
            "album" -> stringResource(R.string.hero_from_album, it.name)
            "artist" -> stringResource(R.string.hero_from_artist, it.name)
            "genre" -> stringResource(R.string.hero_from_genre, it.name)
            "podcast" -> stringResource(R.string.hero_from_podcast, it.name)
            else -> stringResource(R.string.hero_from, it.name)
        }
    } ?: listen.track.album?.name?.takeIf { it.isNotBlank() }
    return listOfNotNull(listen.track.artist, from).joinToString(" · ").ifEmpty { null }
}

/** "1:42 di 3:58 · brano 4 di 18"; per podcast e audiolibri "Mancano 23 min"; niente per una radio. */
@Composable
private fun heroTime(listen: LastListen): String? {
    val duration = listen.track.durationSec
    if (duration <= 0) return null
    if (listen.spoken && !listen.ended) {
        val left = ceil((duration - listen.positionSec).coerceAtLeast(60.0) / 60).toInt()
        return pluralStringResource(R.plurals.episode_left, left, left)
    }
    val time = stringResource(R.string.hero_time, clock((listen.positionSec * 1000).toLong()), clock(duration * 1000L))
    if (listen.count <= 1) return time
    return time + " · " + stringResource(R.string.hero_track_of, listen.index + 1, listen.count)
}

/** Quanto tempo fa: "adesso", "12 min fa", "2 ore fa", "3 giorni fa". */
@Composable
private fun ago(timeMs: Long): String {
    val minutes = ((System.currentTimeMillis() - timeMs) / 60_000).coerceAtLeast(0).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.hero_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.ago_minutes, minutes, minutes)
        minutes < 24 * 60 -> (minutes / 60).let { pluralStringResource(R.plurals.ago_hours, it, it) }
        else -> (minutes / (24 * 60)).let { pluralStringResource(R.plurals.ago_days, it, it) }
    }
}

/** Al posto dell'eroe appena nascosto, per qualche secondo: "Nascosto finché non ascolti di nuovo · Annulla". */
@Composable
internal fun HiddenResume(page: HomePage, model: PhoneModel, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(CoverCorners.miniPlayer),
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter).heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(start = Space.large, end = Space.tiny), verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.Hidden, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Space.medium))
            Text(stringResource(R.string.hero_hidden), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.unhideResume(page) }) { Text(stringResource(R.string.undo)) }
        }
    }
}

// ---------- Scelte rapide ----------

/** La griglia 2×3 delle Scelte rapide. */
@Composable
internal fun QuickPicks(items: List<MaItem>, model: PhoneModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        for (pair in items.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                for (item in pair) QuickTile(item, model, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Una tessera: copertina (tonda per gli artisti) e nome su due righe, su un fondo velato al 10% dal colore vivo della
 * sua copertina. Toccata apre, tenuta premuta suona.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTile(item: MaItem, model: PhoneModel, modifier: Modifier) {
    val url = model.image(item.image, SMALL_ART)
    val palette = rememberArtPalette(url)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val target = palette?.let { lerp(base, Color(it.vivo), TILE_TINT) } ?: base
    val background by animateColorAsState(target, tween(ART_FADE), label = "tile")
    val interaction = remember { MutableInteractionSource() }
    val artist = item.mediaType == "artist"
    Row(
        modifier.testTag("quick_pick")
            .height(TILE_HEIGHT)
            .pressScale(interaction)
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(background)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClickLabel = stringResource(R.string.play),
                onLongClick = { model.play(item) },
                onClick = { model.open(item) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (artist) CircleShape else RoundedCornerShape(topStart = TILE_CORNER, bottomStart = TILE_CORNER)
        Cover(url, item.mediaType, Modifier.size(TILE_HEIGHT), shape, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = Space.small),
        )
    }
}

// ---------- Per te ----------

/** Il carosello Per te: le Infinite Mix con la loro copertina disegnata, poi gli artisti che si ascoltano. */
@Composable
internal fun PerTeCarousel(items: List<MaItem>, model: PhoneModel, modifier: Modifier = Modifier) {
    val fade = cardFade()
    LazyRow(
        modifier.testTag("row"),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.medium),
    ) {
        items(items, key = { it.uri }, contentType = { if (HomePage.mixKind(it) != null) "mix" else it.mediaType }) { item ->
            MixCard(item, model, if (fade) Modifier.animateItem(tween(CARD_FADE_MS), null, tween(CARD_FADE_MS)) else Modifier)
        }
    }
}

/** Una card quadrata del carosello: copertina (o quella disegnata delle Infinite Mix), il tasto play, nome e sottotitolo. */
@Composable
private fun MixCard(item: MaItem, model: PhoneModel, modifier: Modifier = Modifier) {
    val kind = HomePage.mixKind(item)
    val title = when (kind) {
        HomePage.MIX_LIBRARY -> stringResource(R.string.mix_infinite_library)
        HomePage.MIX_FAVORITES -> stringResource(R.string.mix_infinite_favorites)
        else -> item.name
    }
    val subtitle = when (kind) {
        HomePage.MIX_LIBRARY -> stringResource(R.string.mix_infinite_library_sub)
        HomePage.MIX_FAVORITES -> stringResource(R.string.mix_infinite_favorites_sub)
        else -> item.subtitle ?: kindName(item.mediaType)
    }
    val artist = item.mediaType == "artist"
    val interaction = remember { MutableInteractionSource() }
    // Solo la copertina ha gli angoli tondi: tagliare tutta la card toglierebbe la prima lettera del sottotitolo.
    Column(
        modifier.testTag("mix_card")
            .width(MIX_CARD)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { model.open(item) },
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.size(MIX_CARD)) {
            if (kind != null) {
                MixCover(title, kind, Modifier.fillMaxSize())
            } else {
                Cover(model.image(item.image, LARGE_ART), item.mediaType, Modifier.fillMaxSize(), coverShape(item.mediaType, 20.dp), 48.dp)
            }
            PlayOverlay({ model.play(item) }, Modifier.align(Alignment.BottomEnd).padding(Space.small), 44.dp)
        }
        Spacer(Modifier.height(Space.small))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * La copertina disegnata di un mix: una sfumatura diagonale fra due tinte (sempre le stesse per lo stesso [seed]),
 * l'occhiello «MIX» e il nome in Manrope 800.
 */
@Composable
internal fun MixCover(name: String, seed: String, modifier: Modifier = Modifier) {
    val palette = remember(seed) { mixPalette(seed) }
    val ink = Color(palette.suVivo)
    Box(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(palette.vivo), Color(palette.secondo)))),
    ) {
        // In alto, così il tasto play in basso a destra non copre il nome.
        Column(Modifier.padding(Space.medium)) {
            Eyebrow(stringResource(R.string.mix_eyebrow), color = ink.copy(alpha = 0.8f))
            Spacer(Modifier.height(Space.tiny))
            BoxWithConstraints {
                val measurer = rememberTextMeasurer()
                val base = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                // Una parola lunga (il russo «Бесконечный») non si spezza a metà: il nome rimpicciolisce finché ogni
                // parola sta in una riga.
                val style = remember(name, width, base) {
                    val words = name.split(' ').filter { it.isNotEmpty() }
                    MIX_TITLE_SIZES.map { size -> base.copy(fontSize = size.sp, lineHeight = (size + 4).sp) }
                        .firstOrNull { candidate -> words.all { measurer.measure(it, candidate, softWrap = false, maxLines = 1).size.width <= width } }
                        ?: base.copy(fontSize = MIX_TITLE_SIZES.last().sp, lineHeight = (MIX_TITLE_SIZES.last() + 4).sp)
                }
                Text(name, style = style, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Le misure del nome sulla copertina di un mix, dalla più grande: si usa la prima in cui ogni parola sta in una riga. */
private val MIX_TITLE_SIZES = listOf(22, 20, 18, 16)

/** Le tinte di un mix: le due Infinite Mix hanno le loro, gli altri una tinta presa dal nome. */
private fun mixPalette(seed: String): ArtPalette {
    val hue = when (seed) {
        HomePage.MIX_LIBRARY -> 285.0
        HomePage.MIX_FAVORITES -> 15.0
        else -> ((seed.hashCode() and 0x7fffffff) % 360).toDouble()
    }
    return Roles.palette(hue, 0.15, hue + 50, 0.14, grey = false)
}

/** Il tasto play tondo sopra una copertina. */
@Composable
internal fun PlayOverlay(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(XIcons.Play, stringResource(R.string.play), Modifier.size(size * 0.55f))
    }
}

// ---------- Le righe di Music Assistant ----------

/**
 * Una riga di card (Riscopri, Dalla tua libreria, Altro): il suo nome piccolo sopra, "Tutto" per aprirla intera, le card
 * con il tasto play. I brani hanno card più strette.
 */
@Composable
internal fun CoverRow(
    title: String,
    items: List<MaItem>,
    model: PhoneModel,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.tiny), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text(stringResource(R.string.see_all))
                    Icon(XIcons.Chevron, null, Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
        val fade = cardFade()
        LazyRow(
            Modifier.testTag("row"),
            contentPadding = PaddingValues(horizontal = Space.gutter - 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.hair),
        ) {
            itemsIndexed(items, key = { i, item -> "$i|${item.uri}" }, contentType = { _, item -> item.mediaType }) { _, item ->
                val width = if (item.mediaType == "track") TRACK_CARD_WIDTH else CARD_WIDTH
                CoverCard(item, model, width, { model.open(item) }, if (fade) Modifier.animateItem(tween(CARD_FADE_MS), null, tween(CARD_FADE_MS)) else Modifier)
            }
        }
    }
}

/**
 * Una card con copertina, nome e sottotitolo; album, playlist, artisti e podcast hanno anche il tasto play sulla
 * copertina (toccata la card si apre). Premuta si rimpicciolisce.
 */
@Composable
internal fun CoverCard(item: MaItem, model: PhoneModel, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val artist = item.mediaType == "artist"
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.testTag("card")
            .width(width)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(model.image(item.image, SMALL_ART), item.mediaType, Modifier.fillMaxSize(), coverShape(item.mediaType), 40.dp)
            if (item.browsable && item.playable && item.mediaType != "folder") {
                PlayOverlay({ model.play(item) }, Modifier.align(Alignment.BottomEnd).padding(6.dp), 36.dp)
            }
        }
        Spacer(Modifier.height(Space.small))
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
        )
        val subtitle = item.subtitle ?: if (artist) null else kindName(item.mediaType)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- In corso ----------

/** La riga di "In corso": card 280×88 con quanto manca e la barra. */
@Composable
internal fun InProgressRow(items: List<MaItem>, page: HomePage, model: PhoneModel, modifier: Modifier = Modifier) {
    val fade = cardFade()
    LazyRow(
        modifier.testTag("row"),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.medium),
    ) {
        items(items, key = { it.uri }) { item ->
            ProgressCard(item, page, model, if (fade) Modifier.animateItem(tween(CARD_FADE_MS), null, tween(CARD_FADE_MS)) else Modifier)
        }
    }
}

/**
 * Un episodio o un audiolibro a metà: toccato riprende da dove era (lo fa Music Assistant), il menu ha "Dall'inizio" e
 * "Segna come ascoltato". Segnato, resta qualche secondo con "Annulla".
 */
@Composable
private fun ProgressCard(item: MaItem, page: HomePage, model: PhoneModel, modifier: Modifier = Modifier) {
    val marked = item.uri in page.marked
    Surface(
        onClick = { if (!marked) model.play(item) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.testTag("progress_card").width(PROGRESS_WIDTH).height(PROGRESS_HEIGHT),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(
                model.image(item.image, SMALL_ART),
                item.mediaType,
                Modifier.size(PROGRESS_HEIGHT),
                RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                28.dp,
            )
            if (marked) {
                Column(Modifier.weight(1f).padding(start = Space.medium)) {
                    Text(stringResource(R.string.notice_marked_played), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
                TextButton(onClick = { model.undoMarkPlayed(page, item) }) { Text(stringResource(R.string.undo)) }
                return@Row
            }
            Column(Modifier.weight(1f).padding(start = Space.medium, top = 10.dp, bottom = 10.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (item.subtitle == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                val total = item.durationSec * 1000L
                val left = when {
                    item.fullyPlayed -> stringResource(R.string.episode_played)
                    item.resumeMs > 0 && total > item.resumeMs ->
                        ((total - item.resumeMs + 59_999) / 60_000).toInt().let { pluralStringResource(R.plurals.episode_left, it, it) }
                    total > 0 -> length(total)
                    else -> null
                }
                left?.let { Text(it, style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (total > 0 && item.resumeMs > 0) {
                    Spacer(Modifier.height(Space.tiny))
                    ProgressBar(
                        (item.resumeMs.toFloat() / total).coerceIn(0f, 1f),
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            ProgressMenu(item, page, model)
        }
    }
}

@Composable
private fun ProgressMenu(item: MaItem, page: HomePage, model: PhoneModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(XIcons.More, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuEntry(XIcons.Refresh, stringResource(R.string.in_progress_from_start)) {
                open = false
                model.playFromStart(item)
            }
            MenuEntry(XIcons.Check, stringResource(R.string.in_progress_mark_played)) {
                open = false
                model.markPlayed(page, item)
            }
        }
    }
}

// ---------- I tuoi generi ----------

/** Due righe di tessere dei generi che scorrono di lato. */
@Composable
internal fun GenreTiles(genres: List<MaItem>, model: PhoneModel, modifier: Modifier = Modifier) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        modifier = modifier.height(GENRE_HEIGHT * 2 + Space.small),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(genres, key = { it.uri }) { GenreTile(it, model) }
    }
}

/** Una tessera: la tinta piena del genere (presa dal nome), il nome in Manrope 800 e, se c'è, l'immagine inclinata. */
@Composable
private fun GenreTile(item: MaItem, model: PhoneModel) {
    val color = remember(item.name) { Color(genreColor(item.name)) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.testTag("genre_tile")
            .size(GENRE_WIDTH, GENRE_HEIGHT)
            .pressScale(interaction)
            .clip(RoundedCornerShape(GENRE_CORNER))
            .background(color)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { model.open(item) },
    ) {
        val url = model.image(item.image, SMALL_ART)
        if (url != null) {
            Cover(
                url,
                item.mediaType,
                Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 10.dp).size(68.dp).rotate(GENRE_TILT),
                RoundedCornerShape(Space.small),
                24.dp,
            )
        }
        Text(
            item.name.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(Space.medium).padding(end = if (url != null) 40.dp else 0.dp),
        )
    }
}

/** La tinta di un genere: sempre la stessa per lo stesso nome, scura abbastanza per il bianco sopra. */
internal fun genreColor(name: String): Int {
    val hue = ((name.lowercase().hashCode() and 0x7fffffff) % 360).toDouble()
    return Oklch(GENRE_LIGHTNESS, GENRE_CHROMA, hue).toArgb()
}

private const val GENRE_LIGHTNESS = 0.45
private const val GENRE_CHROMA = 0.12
private const val GENRE_TILT = 20f

// ---------- Pezzi comuni ----------

/** Una barra di avanzamento sottile (4 dp), tonda alle estremità. */
@Composable
internal fun ProgressBar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.height(4.dp).clip(CircleShape).drawBehind {
            drawRect(track)
            drawRect(color, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
        },
    )
}

/** Le card che cambiano entrano ed escono in dissolvenza, se le animazioni non sono spente. */
@Composable
private fun cardFade(): Boolean = !reducedMotion()

// ---------- Scheletri ----------

/** Al posto delle Scelte rapide mentre arrivano: sei tessere vuote che pulsano. */
@Composable
internal fun QuickSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                repeat(2) { Skeleton(Modifier.weight(1f).height(TILE_HEIGHT), RoundedCornerShape(TILE_CORNER)) }
            }
        }
    }
}

/** Al posto di un carosello o di una riga: tre copertine vuote. */
@Composable
internal fun CarouselSkeleton(modifier: Modifier = Modifier, size: Dp = MIX_CARD) {
    Row(modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
        repeat(3) {
            Column {
                Skeleton(Modifier.size(size), MaterialTheme.shapes.large)
                Spacer(Modifier.height(Space.small))
                Skeleton(Modifier.width(size * 0.7f).height(14.dp), MaterialTheme.shapes.extraSmall)
            }
        }
    }
}

/** Testo bianco smorzato sopra i fondi della copertina (eroe). */
private const val SOFT_TEXT = 0.72f

/** Il velo delle tessere: il 10% del colore vivo della copertina. */
private const val TILE_TINT = 0.10f

/** I colori delle copertine cambiano in 600 ms, come in ArtTheme. */
private const val ART_FADE = 600
