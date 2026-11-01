package it.xonos.ma.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.phone.model.AddServicePage
import it.xonos.ma.phone.model.ArtistPage
import it.xonos.ma.phone.model.FlowPage
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.ListPage
import it.xonos.ma.phone.model.Notice
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.Page
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.SearchPage
import it.xonos.ma.phone.model.ServicePage
import it.xonos.ma.phone.model.ServicesPage
import it.xonos.ma.phone.model.SettingsPage
import it.xonos.ma.phone.model.Tab
import kotlinx.coroutines.launch

/** Ciò che lo schermo chiede al telefono: lo dà l'Activity, o il banco di prova. */
interface Host {
    /** Il problema di connessione detto nella lingua scelta; null se va tutto bene. */
    fun describe(connection: Connection): String?

    fun describe(error: Throwable): String

    val language: Language

    /** Cambia la lingua: lo schermo si ridisegna nella nuova. */
    fun changeLanguage(language: Language)

    /** Vero se Android può fermare la musica a schermo spento (si legge dentro la composizione). */
    val batteryRestricted: Boolean

    fun askBatteryExemption()

    /** Chiede di leggere la musica del telefono: Music Assistant sul telefono la prende dalla cartella Music. */
    fun askMusicFiles()

    /** Un testo da mandare con la condivisione di Android (per esempio il registro di Music Assistant). */
    fun shareText(subject: String, text: String)

    /** La versione dell'app, in fondo alle impostazioni. */
    val version: String
}

/** L'app sul telefono: le tre schede, il mini-lettore e, sopra tutto, il lettore a schermo intero. */
@Composable
fun XonosScreen(model: PhoneModel, player: PlayerLink, host: Host) {
    XonosTheme {
        // Lo schermo comanda il telefono o, con "Suona su", un lettore di Music Assistant.
        val link = model.remote ?: player
        val nowPlaying by link.nowPlaying.collectAsState()
        val phoneNow by player.nowPlaying.collectAsState()
        val phonePlaying = phoneNow?.playing == true
        LaunchedEffect(phonePlaying) { model.phonePlaying(phonePlaying) }
        // Finché non c'è un indirizzo e il token, si comincia dalle impostazioni.
        var configured by remember { mutableStateOf(model.configured) }
        // Anche il lettore e il benvenuto, che stanno fuori dallo Scaffold, scrivono con i colori del tema.
        Surface(Modifier.fillMaxSize().automationIds(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            if (!configured) {
                Onboarding(model, host) { configured = model.configured }
                return@Surface
            }
            val snackbar = remember { SnackbarHostState() }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        val now = nowPlaying
                        val remote = model.remote
                        when {
                            now != null -> MiniPlayer(now, link, model)
                            model.starting -> StartingBar()
                            remote != null -> RemoteBar(remote.device, model)
                        }
                        TabBar(model)
                    }
                },
            ) { padding ->
                val pages = rememberSaveableStateHolder()
                val page = model.page
                Box(Modifier.padding(padding).fillMaxSize()) {
                    pages.SaveableStateProvider(page.id) { PageScreen(page, model, link, host) }
                }
            }
            Notices(model, host, snackbar)

            // Il lettore resta disegnato mentre scende, anche se la coda si è appena svuotata.
            var shown by remember { mutableStateOf<NowPlaying?>(null) }
            nowPlaying?.let { shown = it }
            AnimatedVisibility(
                visible = model.playerOpen && nowPlaying != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(),
            ) {
                shown?.let { PlayerScreen(it, link, model, host) }
            }
            model.picker?.let { DevicesSheet(it, model, host) }
            model.playlistPicker?.let { PlaylistSheet(it, model, host) }
        }
        PlatformBackHandler(enabled = configured && (model.playerOpen || model.depth > 0 || model.tab != Tab.HOME)) { model.back() }
    }
}

@Composable
private fun PageScreen(page: Page, model: PhoneModel, player: PlayerLink, host: Host) {
    LaunchedEffect(page) { model.start(page) }
    when (page) {
        is HomePage -> HomeScreen(page, model, host)
        is SearchPage -> SearchScreen(page, model, host)
        is LibraryPage -> LibraryScreen(page, model, host)
        is ItemPage -> ItemScreen(page, model, player, host)
        is ArtistPage -> ArtistScreen(page, model, host)
        is ListPage -> ListScreen(page, model, host)
        is SettingsPage -> SettingsScreen(page, model, host, onboarding = false) { model.back() }
        is ServicesPage -> ServicesScreen(page, model, host)
        is AddServicePage -> AddServiceScreen(page, model, host)
        is FlowPage -> FlowScreen(page, model, host)
        is ServicePage -> ServiceScreen(page, model, host)
    }
}

@Composable
private fun TabBar(model: PhoneModel) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val tabs = listOf(
            Triple(Tab.HOME, XIcons.Home, R.string.nav_home),
            Triple(Tab.SEARCH, XIcons.Search, R.string.nav_search),
            Triple(Tab.LIBRARY, XIcons.Library, R.string.nav_library),
        )
        for ((tab, icon, label) in tabs) {
            NavigationBarItem(
                selected = model.tab == tab,
                onClick = { model.select(tab) },
                icon = { Icon(icon, null) },
                label = { Text(stringResource(label)) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

/** Gli avvisi brevi dopo un comando ("Suona dopo", un errore...), in fondo allo schermo. */
@Composable
private fun Notices(model: PhoneModel, host: Host, snackbar: SnackbarHostState) {
    val notice = model.notice
    val text = when (notice) {
        null -> null
        Notice.PlayNext -> stringResource(R.string.notice_play_next)
        Notice.Added -> stringResource(R.string.notice_added)
        Notice.FavoriteAdded -> stringResource(R.string.notice_favorite_added)
        Notice.FavoriteRemoved -> stringResource(R.string.notice_favorite_removed)
        Notice.Saved -> stringResource(R.string.notice_saved)
        is Notice.AddedTo -> stringResource(R.string.notice_added_to, notice.name)
        is Notice.RemovedFrom -> stringResource(R.string.notice_removed_from, notice.name)
        Notice.PlaylistDeleted -> stringResource(R.string.notice_playlist_deleted)
        Notice.Removed -> stringResource(R.string.notice_removed)
        is Notice.Moved -> notice.device?.let { stringResource(R.string.notice_moved, it) } ?: stringResource(R.string.notice_moved_phone)
        is Notice.Failed -> host.describe(notice.error)
    }
    // L'avviso si mostra fuori dall'effetto, che riparte appena lo si toglie dal modello.
    val scope = rememberCoroutineScope()
    LaunchedEffect(notice) {
        if (text == null) return@LaunchedEffect
        model.notice = null
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(text) }
    }
}
