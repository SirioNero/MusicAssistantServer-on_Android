package it.xonos.ma.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.MaItem
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.HomeRows
import it.xonos.ma.phone.model.HomeSection
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.value
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** L'alone dietro la testata, coi colori della copertina dell'eroe: fermo, cambia con una dissolvenza. */
private val GLOW_HEIGHT = 320.dp

/** Quanto ci mette l'alone a passare da una copertina all'altra. */
private const val GLOW_FADE_MS = 600

/**
 * La prima scheda, dall'alto (docs/per-te-piano.md, capitolo 2): saluto e riga viva, il banner della connessione,
 * "Riprendi", Scelte rapide, Per te, In corso, I tuoi generi, Riscopri, Dalla tua libreria e "Altro" (chiusa). Si
 * disegna subito con ciò che c'è sul telefono, poi Music Assistant aggiorna; trascinando in giù si ricarica.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(page: HomePage, model: PhoneModel, host: Host) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pull = rememberPullToRefreshState()
    // La Home è pronta quando si vede qualcosa: quella salvata sul disco, o ciò che è arrivato da Music Assistant.
    PlatformReportReady { page.shown || (page.recent !is Load.Loading && page.rows !is Load.Loading) }
    val list = rememberLazyListState()
    val resume = page.resume?.takeIf { model.resumeVisible(page) }
    val picks = page.quickPicks
    // L'alone: la copertina dell'eroe, sennò quella della prima scelta rapida.
    val glowUrl = resume?.let { model.image(it.track.image ?: it.source?.image, LARGE_ART) }
        ?: picks.firstOrNull()?.let { model.image(it.image, SMALL_ART) }
    val glowPalette = rememberArtPalette(glowUrl)
    val glow by animateColorAsState(Color((glowPalette ?: ArtPalette.BRAND).alone), tween(GLOW_FADE_MS), label = "glow")
    // Le sezioni entrano a cascata solo la prima volta che la Home si vede, dall'apertura dell'app.
    val cascade = remember(page.shown) { page.shown && CascadeOnce.take("home") }
    PullToRefreshBox(
        isRefreshing = page.refreshing,
        onRefresh = { model.refresh(page) },
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(pull, page.refreshing, Modifier.align(Alignment.TopCenter).padding(top = top))
        },
    ) {
        // Dietro la testata, e scorre con lei. Si legge mentre si disegna: scorrendo non si ricompone niente.
        Box(
            Modifier.fillMaxWidth().height(GLOW_HEIGHT + top)
                .graphicsLayer {
                    val first = list.firstVisibleItemIndex == 0
                    translationY = if (first) -list.firstVisibleItemScrollOffset.toFloat() else -size.height
                }
                .background(Brush.verticalGradient(listOf(glow, glow.copy(alpha = 0f)))),
        )
        LazyColumn(Modifier.fillMaxSize().testTag("home"), state = list, contentPadding = PaddingValues(top = top, bottom = Space.big)) {
            item(key = "header", contentType = "header") {
                Header(page, model, host)
                ConnectionBanner(model, host)
            }
            homeSections(page, model, host, resume, picks, cascade)
        }
    }
}

/** Le sezioni della Home, in ordine fisso; quelle vuote non lasciano spazio. */
private fun LazyListScope.homeSections(
    page: HomePage,
    model: PhoneModel,
    host: Host,
    resume: LastListen?,
    picks: List<MaItem>,
    cascade: Boolean,
) {
    // Il posto di ogni sezione nella cascata, contato qui (non mentre si disegna, che si ripete).
    var steps = 0
    fun step(): Int = steps++

    // 2. Riprendi.
    if (resume != null) {
        val index = step()
        item(key = "hero", contentType = "hero") {
            HeroResume(resume, page, model, fade().padding(top = Space.small).cascadeIn(index, cascade))
        }
    } else if (page.justHidden != null) {
        item(key = "hero_hidden", contentType = "hero_hidden") { HiddenResume(page, model, fade().padding(top = Space.small)) }
    }

    // 3. Scelte rapide (o, se non si è ascoltato ancora niente, due parole su cosa comparirà qui).
    val recent = page.recent
    when {
        picks.isNotEmpty() -> {
            val index = step()
            item(key = "quick", contentType = "quick") {
                Section(R.string.row_recently_played, R.string.home_quick_eyebrow, fade().cascadeIn(index, cascade)) { QuickPicks(picks, model) }
            }
        }
        recent is Load.Loading -> item(key = "quick_wait", contentType = "quick") {
            Section(R.string.row_recently_played, R.string.home_quick_eyebrow, fade()) { QuickSkeleton() }
        }
        // Non è arrivato niente: il perché e "Riprova". Mentre il server sul telefono parte no: si ricarica da sola.
        recent is Load.Failed && !model.phoneServerStarting -> item(key = "failed", contentType = "failed") {
            Problem(host.describe(recent.error), { model.reload(page) }, fade())
        }
        recent is Load.Ready && resume == null && page.justHidden == null && !empty(page) -> item(key = "first_run", contentType = "first_run") {
            FirstRun(fade().padding(top = Space.section))
        }
    }

    // 4. Per te: le Infinite Mix e gli artisti che ascolti.
    val perTe = page.perTe
    if (perTe.isNotEmpty()) {
        val index = step()
        item(key = "per_te", contentType = "carousel") {
            Section(R.string.cat_foryou, R.string.home_for_you_eyebrow, fade().cascadeIn(index, cascade)) { PerTeCarousel(perTe, model) }
        }
    } else if (page.rows is Load.Loading) {
        item(key = "per_te_wait", contentType = "carousel") {
            Section(R.string.cat_foryou, R.string.home_for_you_eyebrow, fade()) { CarouselSkeleton() }
        }
    }

    // 5. In corso.
    val progress = page.inProgress.value.orEmpty()
    if (progress.isNotEmpty()) {
        val index = step()
        item(key = "in_progress", contentType = "in_progress") {
            Section(R.string.row_in_progress, R.string.home_in_progress_eyebrow, fade().cascadeIn(index, cascade)) { InProgressRow(progress, page, model) }
        }
    }

    // 7. I tuoi generi, se Music Assistant ne ha.
    if (page.genres.isNotEmpty()) {
        val index = step()
        val genres = page.genres
        item(key = "genres", contentType = "genres") {
            Section(R.string.home_genres_title, R.string.home_genres_eyebrow, fade().cascadeIn(index, cascade)) { GenreTiles(genres, model) }
        }
    }

    // 9 e 10. Riscopri e Dalla tua libreria: le righe di Music Assistant coi nomi di Xonos.
    for (section in listOf(HomeSection.REDISCOVER, HomeSection.LIBRARY)) {
        val rows = page.section(section)
        if (rows.isEmpty()) continue
        val title = sectionTitle(section) ?: continue
        val eyebrow = sectionEyebrow(section)
        val index = step()
        item(key = "section_${section.name}", contentType = "section_header") {
            Column(fade().padding(top = Space.section).cascadeIn(index, cascade)) {
                SectionHeader(stringResource(title), eyebrow = eyebrow?.let { stringResource(it) })
            }
        }
        rowItems(rows, model)
    }

    // 11. Altro: chiusa finché non la si apre.
    if (page.more.any { it.items !is Load.Failed && it.items.value?.isEmpty() != true }) {
        item(key = "more", contentType = "more") { MoreHeader(page, model, fade()) }
        if (page.moreOpen) rowItems(page.moreRows, model)
    }

    if (empty(page)) item(key = "empty", contentType = "empty") { EmptyHome(model) }
}

/** Le righe di una sezione: ognuna il suo nome piccolo e le sue card; mentre arriva, uno scheletro. */
private fun LazyListScope.rowItems(rows: List<HomePage.Row>, model: PhoneModel) {
    items(rows, key = { "row|${it.row.provider}|${it.row.itemId}" }, contentType = { "row" }) { row ->
        val title = rowTitle(row)
        when (val items = row.items) {
            Load.Loading -> Column(fade().padding(top = Space.medium)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.medium))
                CarouselSkeleton(size = CARD_WIDTH - 12.dp)
            }
            is Load.Ready -> CoverRow(title, items.value, model, { model.openRow(row, title) }, fade())
            is Load.Failed -> Unit
        }
    }
}

/** Una sezione: occhiello e titolo, poi il contenuto; 32 dp sopra. */
@Composable
private fun Section(title: Int, eyebrow: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = Space.section)) {
        SectionHeader(stringResource(title), eyebrow = stringResource(eyebrow))
        content()
    }
}

/**
 * Le sezioni che cambiano (arriva Music Assistant dopo la Home salvata) compaiono e spariscono in dissolvenza, 200 ms,
 * senza far scorrere le altre. Con le animazioni spente niente.
 */
@Composable
private fun LazyItemScope.fade(): Modifier =
    if (reducedMotion()) Modifier else Modifier.animateItem(tween(SECTION_FADE_MS), null, tween(SECTION_FADE_MS))

private const val SECTION_FADE_MS = 200

/**
 * La testata: il saluto per l'ora del giorno, sotto la data e quanto c'è "In corso"; a destra "Suona su" (se ha senso) e
 * le Impostazioni.
 */
@Composable
private fun Header(page: HomePage, model: PhoneModel, host: Host) {
    Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.tiny, top = Space.medium), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(greeting(LocalTime.now().hour)), style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(liveLine(page, host), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (model.canPlayElsewhere) {
            IconButton(onClick = model::openDevices, modifier = Modifier.testTag("home_play_on")) { Icon(XIcons.Cast, stringResource(R.string.play_on)) }
        }
        IconButton(onClick = model::openSettings) { Icon(XIcons.Settings, stringResource(R.string.settings)) }
    }
}

/** "Mercoledì 4 novembre · 2 in corso": la data nella lingua scelta e, se c'è, quanto resta da finire. */
@Composable
private fun liveLine(page: HomePage, host: Host): String {
    val pattern = stringResource(R.string.home_date_pattern)
    val language = host.language
    val today = LocalDate.now()
    val date = remember(pattern, language, today) {
        try {
            DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(language.tag)).format(today)
        } catch (e: IllegalArgumentException) {
            DateTimeFormatter.ISO_LOCAL_DATE.format(today)
        }.replaceFirstChar { it.titlecase(Locale.forLanguageTag(language.tag)) }
    }
    val progress = page.inProgress.value?.size ?: 0
    return if (progress > 0) date + " · " + pluralStringResource(R.plurals.home_live_in_progress, progress, progress) else date
}

/** Il saluto dell'ora (vedi HomeRows.greetingKey), come risorsa. */
private fun greeting(hour: Int): Int = when (HomeRows.greetingKey(hour)) {
    "greeting_morning" -> R.string.greeting_morning
    "greeting_afternoon" -> R.string.greeting_afternoon
    "greeting_evening" -> R.string.greeting_evening
    else -> R.string.greeting_night
}

/** Il nome di una riga: quello di Xonos per le 16 righe della libreria, sennò quello di Music Assistant. */
@Composable
private fun rowTitle(row: HomePage.Row): String = rowTitleRes(row.place.titleKey)?.let { stringResource(it) } ?: row.row.name

/** Le scritte delle righe, una per una (con shrinkResources le risorse cercate per nome sparirebbero). */
private fun rowTitleRes(key: String?): Int? = when (key) {
    "row_in_progress" -> R.string.row_in_progress
    "row_recently_played" -> R.string.row_recently_played
    "row_recently_added_tracks" -> R.string.row_recently_added_tracks
    "row_recently_added_albums" -> R.string.row_recently_added_albums
    "row_random_artists" -> R.string.row_random_artists
    "row_random_albums" -> R.string.row_random_albums
    "row_recent_favorite_tracks" -> R.string.row_recent_favorite_tracks
    "row_favorite_playlists" -> R.string.row_favorite_playlists
    "row_favorite_radio" -> R.string.row_favorite_radio
    "row_recent_artists" -> R.string.row_recent_artists
    "row_recent_tracks" -> R.string.row_recent_tracks
    "row_forgotten_tracks" -> R.string.row_forgotten_tracks
    "row_forgotten_albums" -> R.string.row_forgotten_albums
    "row_forgotten_artists" -> R.string.row_forgotten_artists
    "row_most_played_tracks" -> R.string.row_most_played_tracks
    "row_never_played_tracks" -> R.string.row_never_played_tracks
    else -> null
}

private fun sectionTitle(section: HomeSection): Int? = when (section) {
    HomeSection.REDISCOVER -> R.string.home_rediscover_title
    HomeSection.LIBRARY -> R.string.home_library_title
    HomeSection.OTHER -> R.string.home_other_title
    else -> null
}

private fun sectionEyebrow(section: HomeSection): Int? = when (section) {
    HomeSection.REDISCOVER -> R.string.home_rediscover_eyebrow
    HomeSection.LIBRARY -> R.string.home_library_eyebrow
    HomeSection.OTHER -> R.string.home_other_eyebrow
    else -> null
}

/** "Altro": occhiello e titolo, e il tasto per aprirla o chiuderla. */
@Composable
private fun MoreHeader(page: HomePage, model: PhoneModel, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.tiny, top = Space.section).testTag("home_more"),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f).padding(bottom = Space.small)) {
            Eyebrow(stringResource(R.string.home_other_eyebrow))
            Text(stringResource(R.string.home_other_title), style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = { model.toggleMore(page) }) {
            Text(stringResource(if (page.moreOpen) R.string.home_more_hide else R.string.home_more_show))
            Icon(XIcons.Down, null, Modifier.size(18.dp).graphicsLayer { rotationZ = if (page.moreOpen) 180f else 0f })
        }
    }
}

/** La prima volta, prima di ogni ascolto: cosa comparirà qui. */
@Composable
private fun FirstRun(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(CoverCorners.hero),
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter).testTag("home_first_run"),
    ) {
        Row(Modifier.padding(Space.large), verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.Recent, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.large))
            Text(stringResource(R.string.home_first_run), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Una pillola sotto il saluto quando Music Assistant non risponde: il motivo, o che ci si sta collegando. Se è quello
 * sul telefono e Xonos è stato aggiornato senza il suo pacchetto nuovo, lo dice, con il tasto per aggiornarlo.
 */
@Composable
internal fun ConnectionBanner(model: PhoneModel, host: Host) {
    val connection by model.connection.collectAsState()
    // Il server sul telefono parte solo con il pacchetto della versione di Xonos: finché non lo si scarica, niente.
    val serverOld = connection !is Connection.Connected && model.onPhoneServer && model.phoneServerOutdated
    // Mentre il server sul telefono parte la connessione non riesce: non è un errore di rete, è l'avvio.
    val starting = connection !is Connection.Connected && !serverOld && (model.phoneServerStarting || model.phoneServerRetrying)
    // Non è partito, o si è fermato: il perché (non solo "non raggiungibile"), con il tasto per riprovare.
    val failure = if (connection !is Connection.Connected && !serverOld && !starting) model.phoneServerFailure else null
    val text = when {
        serverOld -> stringResource(R.string.phone_server_outdated)
        connection is Connection.Connected -> return
        starting -> stringResource(R.string.phone_server_starting)
        failure != null -> stringResource(R.string.phone_server_failed, failure)
        connection is Connection.Connecting -> stringResource(R.string.connecting)
        else -> host.describe(connection) ?: return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(BANNER_HEIGHT / 2),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.small).heightIn(min = BANNER_HEIGHT).testTag("connection_banner"),
    ) {
        // Il tasto (Aggiorna, Riprova) va sotto il testo, a destra: accanto gli toglierebbe quasi tutta la riga
        // ("Aggiorna Music Assistant su questo telefono", "Erneut versuchen").
        Column(Modifier.padding(start = Space.large, end = Space.tiny)) {
            Row(
                Modifier.heightIn(min = BANNER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if ((connection is Connection.Connecting || starting) && !serverOld) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(XIcons.Offline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
                // Un avvio sta in due righe; un errore va letto tutto (perché il server non è partito, cosa controllare).
                val progress = (connection is Connection.Connecting || starting) && !serverOld && failure == null
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (progress) 2 else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = Space.small),
                )
                Spacer(Modifier.width(Space.small))
            }
            if (serverOld) {
                TextButton(onClick = { model.openSettings() }, modifier = Modifier.align(Alignment.End).testTag("update_phone_server")) {
                    Text(stringResource(R.string.phone_server_update), textAlign = TextAlign.End)
                }
            } else if (failure != null) {
                TextButton(onClick = { model.retryPhoneServer() }, modifier = Modifier.align(Alignment.End).testTag("retry_phone_server")) {
                    Text(stringResource(R.string.retry), maxLines = 1)
                }
            }
        }
    }
}

/** La pillola della connessione è alta almeno 40 dp. */
private val BANNER_HEIGHT = 40.dp

/** Niente da mostrare: né ascolti né consigli né mix (un Music Assistant appena installato). */
private fun empty(page: HomePage): Boolean {
    val recent = page.recent.value ?: return false
    val rows = page.rows.value ?: return false
    if (page.inProgress is Load.Loading) return false
    return recent.isEmpty() && page.resume == null && page.mixes.isEmpty() && page.genres.isEmpty() &&
        page.inProgress.value.isNullOrEmpty() &&
        rows.all { it.items is Load.Failed || it.items.value?.isEmpty() == true } &&
        page.more.all { it.items !is Load.Ready || it.items.value?.isEmpty() == true }
}

/** La Home vuota: chi amministra Music Assistant può aggiungere subito un servizio. */
@Composable
private fun EmptyHome(model: PhoneModel) {
    val admin = rememberAdmin(model)
    Column(Modifier.fillMaxWidth().padding(Space.section), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(XIcons.Services, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.medium))
        Text(
            stringResource(if (admin) R.string.home_empty_admin else R.string.home_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (admin) {
            Spacer(Modifier.height(Space.large))
            // "Aggiungi un servizio" porta dritto alla scelta del servizio (prima all'elenco di quelli interni di Music Assistant).
            Button(onClick = { model.services.add() }, modifier = Modifier.testTag("home_services")) { Text(stringResource(R.string.services_add)) }
        }
    }
}
