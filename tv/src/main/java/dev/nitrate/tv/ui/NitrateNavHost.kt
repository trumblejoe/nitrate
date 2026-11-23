package dev.nitrate.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import dev.nitrate.android.sendPlaybackReport
import dev.nitrate.android.playbackReport
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import dev.nitrate.tv.ui.admin.ServerAdminScreen
import dev.nitrate.tv.ui.admin.ManageItemScreen
import dev.nitrate.tv.ui.pages.rememberCustomPages
import dev.nitrate.tv.ui.pages.PagesScreen
import dev.nitrate.tv.ui.pages.DisplaySettingsScreen
import dev.nitrate.tv.ui.pages.CustomPageScreen
import dev.nitrate.tv.ui.theme.NitrateTheme
import dev.nitrate.tv.ui.components.ProvideDisplayPrefs
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.SessionState
import dev.nitrate.tv.ui.home.OfflineScreen
import dev.nitrate.tv.ui.connect.ConnectScreen
import dev.nitrate.tv.ui.details.DetailsScreen
import androidx.navigation.NavController
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.settings.AudioChannelTestScreen
import dev.nitrate.tv.ui.settings.PluginsScreen
import dev.nitrate.tv.plugins.core.PlaybackGateDialog
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.writing.TV_PLUGIN_UI
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.ScreenPlugin
import dev.nitrate.tv.plugins.core.rememberPlaybackGate
import androidx.compose.runtime.collectAsState
import dev.nitrate.tv.ui.settings.PlaybackSettingsScreen
import dev.nitrate.tv.ui.settings.PlaybackTestScreen
import dev.nitrate.tv.ui.library.LibraryScreen
import dev.nitrate.tv.ui.playlist.PlaylistScreen
import dev.nitrate.tv.ui.login.LoginScreen
import dev.nitrate.tv.ui.player.PlayerScreen
import kotlinx.serialization.Serializable

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object HomeRoute
/** The listening player (music). */
@Serializable object NowPlayingRoute
@Serializable object OfflineRoute
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null)
@Serializable object SettingsRoute
@Serializable object SettingsHubRoute
@Serializable object AdvancedPlaybackRoute
@Serializable object SubtitleSettingsRoute
@Serializable object WhileWatchingRoute
@Serializable object PrerollSettingsRoute
@Serializable object ShownSettingsRoute
@Serializable object UpdatesRoute
@Serializable object PlaybackTestRoute
@Serializable object AudioChannelTestRoute
@Serializable object PluginsRoute
@Serializable data class PluginScreenRoute(val pluginId: String)
@Serializable data class LibraryRoute(val libraryId: String)
@Serializable data class PlaylistRoute(val playlistId: String)
@Serializable object PagesRoute
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)
@Serializable object DisplaySettingsRoute
@Serializable object PageSetupRoute
@Serializable object HomeSetupRoute
@Serializable object EffectsSettingsRoute
@Serializable data class LibraryTabsRoute(val libraryId: String)
@Serializable object MenuStopsRoute
@Serializable object ServerAddressesRoute
@Serializable object LicensesRoute
@Serializable object DeviceBackupRoute
@Serializable object DisplayProfilesRoute
@Serializable object SpeedCheckRoute
@Serializable object QueryAnalyzerRoute
@Serializable object StatsRoute
@Serializable object DiaryRoute
@Serializable object ServerSettingsRoute
@Serializable data class ManageItemRoute(val itemId: String)
@Serializable object ServerAdminRoute
@Serializable object LibraryHealthRoute
@Serializable object PlaybackOverviewRoute

/** Settings → Tools → TV & receiver check (the Companion plugin's clips). */
@Serializable object TvCheckRoute

/** Settings → Tools → Checking how things play (the guide to the playback tools). */
@Serializable object GuideRoute

/** Settings → Tools → Household devices (admins). */
@Serializable object HouseholdDevicesRoute
@Serializable object WatchTogetherRoute
@Serializable object ChannelsRoute
/** The menu's "More": the stops kept out of the menu itself. */
@Serializable object MoreRoute
@Serializable object WatchPartnersRoute
@Serializable data class ChannelEditorRoute(val channelId: String = "")
@Serializable object ProfilesRoute
@Serializable data class SearchRoute(val query: String = "", val voice: Boolean = false)
@Serializable data class PersonRoute(val personId: String, val name: String, val studio: String? = null, val year: Int? = null)

/** Asked of the app from outside: the home screen, voice search, the remote's search button. */
sealed interface TvRequest {
    data class Search(val query: String, val voice: Boolean) : TvRequest
    data class Open(val itemId: String) : TvRequest
    data class Play(val itemId: String) : TvRequest
}

/** [queue] is comma-separated item ids to play after [itemId]; [clipEndMs] >= 0: a recap clip from [startMs] to there, unreported. */
/** [audioIndex]/[subtitleIndex]: tracks asked for by another device ([NO_TRACK]: not asked; subtitles -1 = off). */
@Serializable data class PlayerRoute(
    val itemId: String, val fromStart: Boolean = false, val queue: String = "", val startMs: Long = -1, val clipEndMs: Long = -1,
    val audioIndex: Int = NO_TRACK, val subtitleIndex: Int = NO_TRACK,
    /** A test play from the playback overview; [queue] then holds the titles still to test. */
    val test: Boolean = false,
)

const val NO_TRACK = -2

/** Where the player was when an extra opened on top of it (saved on its back-stack entry). */
private const val RESUME_AT = "resumeAt"

/** "Last time": the recap in [AppContainer.pendingRecap], before playing on. */
@Serializable object RecapRoute

/** Reading a comic from a Books library. */
@Serializable data class ComicRoute(val itemId: String)
@Serializable object CatalogsRoute
@Serializable data class CatalogFeedRoute(val catalogId: String, val target: String? = null)
@Serializable data class CatalogBookRoute(val catalogId: String, val book: String)
private val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** What plays before [itemId] ([dev.nitrate.tv.ui.preroll.PrerollPlan.steps]): a scene, a verse, then intros. */
@Serializable data class PrerollRoute(val itemId: String, val fromStart: Boolean = false)

@Composable
fun NitrateNavHost(container: AppContainer, requests: kotlinx.coroutines.flow.MutableStateFlow<TvRequest?> = kotlinx.coroutines.flow.MutableStateFlow(null)) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        val state = container.server.restoreSession()
        start = when {
            container.shouldPickProfile(state) -> ProfilesRoute
            state == SessionState.VALID -> { container.onSignedIn(); HomeRoute }
            state == SessionState.UNREACHABLE -> OfflineRoute
            else -> ConnectRoute
        }
    }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            Text("Nitrate", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val account by container.account.collectAsState()
    // Every "play this item" goes through plugin guards first; queues (playlists) don't.
    // Resuming after a break: the "Last time" recap first, when one is due.
    // Before a title starts from the top: the user's pre-rolls (verse, scene, intros), when switched on.
    val displayPrefs by androidx.compose.runtime.rememberUpdatedState(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Songs go to the listening player (which carries on while browsing), anything else to the player.
    fun listen(items: List<dev.nitrate.core.model.MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val listening = container.listening
        if (listening != null && items.isNotEmpty() && items.all { it.kind == dev.nitrate.core.model.ItemKind.AUDIO }) {
            listening.play(items, start, shuffle)
            nav.navigate(NowPlayingRoute) { launchSingleTop = true }
        } else nav.playQueue((if (shuffle) items.drop(start).shuffled() else items.drop(start)).map { it.id })
    }
    val gate = rememberPlaybackGate(container.plugins) { item, fromStart, startMs ->
        scope.launch {
            // A book opens in the reader, from wherever Play was pressed.
            if (item.kind == dev.nitrate.core.model.ItemKind.BOOK) nav.navigate(ComicRoute(item.id))
            // A book carries on where it was left in the listening player.
            else if (item.kind == dev.nitrate.core.model.ItemKind.AUDIOBOOK && container.listening != null) {
                container.listening!!.play(listOf(item), startMs = if (fromStart || item.user.played) 0L else item.user.positionMs)
                nav.navigate(NowPlayingRoute) { launchSingleTop = true }
            }
            // A song plays in the listening player, its album queued around it.
            else if (item.kind == dev.nitrate.core.model.ItemKind.AUDIO && container.listening != null) {
                val album = item.albumId?.let { runCatching { container.server.albumTracks(it) }.getOrNull() }.orEmpty()
                listen(album.ifEmpty { listOf(item) }, album.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            else if (startMs != null) nav.navigate(PlayerRoute(item.id, fromStart = false, startMs = startMs))
            else if (!fromStart && container.recapBefore(item) != null) nav.navigate(RecapRoute)
            else {
                val steps = if (displayPrefs.easyMode) emptyList() else dev.nitrate.tv.ui.preroll.planPrerolls(container, context, item, displayPrefs.prerolls, fromStart)
                if (steps.isEmpty()) nav.navigate(PlayerRoute(item.id, fromStart))
                else { dev.nitrate.tv.ui.preroll.PrerollPlan.steps = steps; nav.navigate(PrerollRoute(item.id, fromStart)) }
            }
        }
    }
    // The TV screens of plugins that are switched on.
    val enabledIds by container.plugins.enabledIds.collectAsState(emptySet())
    val enabledPlugins = TV_PLUGIN_UI.filter { it.pluginId in enabledIds }
    val pluginContext = remember(container) { PluginContext(container) }
    // Display choices belong to the signed-in user, known only after sign-in.
    val backEntry by nav.currentBackStackEntryAsState()
    val userId = remember(backEntry) { container.server.userId }
    // The main menu: the same stops on every top-level page (Home, libraries, custom pages).
    // Going somewhere from it starts from Home, so Back always leads home.
    val libraries = dev.nitrate.tv.ui.home.rememberLibraries(container)
    val customPages = rememberCustomPages(container)
    val fromHome: androidx.navigation.NavOptionsBuilder.() -> Unit = { popUpTo(HomeRoute); launchSingleTop = true }
    // A long press on "Switch user" in the menu: switch from right there.
    var quickSwitch by remember { mutableStateOf(false) }
    // The household's channels: the server's Companion plugin has them and the Channels plugin is on.
    val companion by container.companion.collectAsState()
    val channelsOn = companion?.features?.contains(dev.nitrate.core.channels.ChannelsPlugin.FEATURE) == true &&
        dev.nitrate.core.channels.ChannelsPlugin.id in enabledIds
    // Watch partners: the server's Companion plugin has them and the plugin is on.
    val partnersOn = companion?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true &&
        dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabledIds
    // Playlists added to the menu (stored with the display settings).
    val storedPrefs by remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: kotlinx.coroutines.flow.flowOf(dev.nitrate.core.pages.DisplayPrefs()) }
        .collectAsState(dev.nitrate.core.pages.DisplayPrefs())
    val menuPlaylists = storedPrefs.menuPlaylists
    val menuCatalog = remember(libraries, customPages, nav, channelsOn, menuPlaylists) {
        dev.nitrate.tv.ui.home.menuCatalog(
            libraries, customPages, playlists = menuPlaylists, actions =
            dev.nitrate.tv.ui.home.MenuActions(
                onSearch = { nav.navigate(SearchRoute(), fromHome) },
                onHome = {
                    // Already home: nothing to do (popping to Home from Home pops nothing, and
                    // navigating would stack a second Home).
                    val atHome = nav.currentBackStackEntry?.destination?.hasRoute(HomeRoute::class) == true
                    if (!atHome && !nav.popBackStack(HomeRoute, inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                },
                onLibrary = { lib -> nav.navigate(LibraryRoute(lib.id), fromHome) },
                onPage = { id -> nav.navigate(CustomPageRoute(id), fromHome) },
                onPages = { nav.navigate(PagesRoute, fromHome) },
                onTogether = { nav.navigate(WatchTogetherRoute, fromHome) },
                onChannels = if (channelsOn) ({ nav.navigate(ChannelsRoute, fromHome) }) else null,
                onComics = { nav.navigate(CatalogsRoute, fromHome) },
                onSettings = { nav.navigate(SettingsHubRoute, fromHome) },
                onProfiles = { nav.navigate(ProfilesRoute) },
                onQuickSwitch = { quickSwitch = true },
                onNowPlaying = { nav.navigate(NowPlayingRoute) { launchSingleTop = true } },
                onMore = { nav.navigate(MoreRoute, fromHome) },
                onPlaylist = { id -> nav.navigate(PlaylistRoute(id), fromHome) },
            ),
        )
    }
    // A playlist's page: on its own (PlaylistRoute) or as a Home tab (pinned), with pinning in its More.
    fun savePrefs(change: (dev.nitrate.core.pages.DisplayPrefs) -> dev.nitrate.core.pages.DisplayPrefs) {
        val user = userId ?: return
        scope.launch { container.displaySettings.savePrefs(user, change(container.displaySettings.prefs(user).first())) }
    }
    val playlistPage: @Composable (playlistId: String, header: (@Composable () -> Unit)?, onDeleted: () -> Unit) -> Unit = { playlistId, header, onDeleted ->
            val pinning = dev.nitrate.tv.ui.playlist.PlaylistPinning(
                onHome = playlistId in storedPrefs.homePlaylists,
                inMenu = playlistId in storedPrefs.menuPlaylists,
                setHome = { id, name, on -> savePrefs { p -> p.copy(homePlaylists = if (on) p.homePlaylists + (id to name) else p.homePlaylists - id) } },
                setMenu = { id, name, on -> savePrefs { p -> p.copy(menuPlaylists = if (on) p.menuPlaylists + (id to name) else p.menuPlaylists - id) } },
            )
            dev.nitrate.tv.ui.playlist.PlaylistScreen(
                repository = container.server,
                settings = container.displaySettings,
                playlists = container.playlists,
                playlistId = playlistId,
                onPlayQueue = { items -> listen(items) },
                onOpen = nav::openItem,
                onDeleted = onDeleted,
                header = header,
                pinning = pinning,
                onContinue = { pl, next, rest ->
                    scope.launch {
                        if (container.recapBefore(next, pl) != null) nav.navigate(RecapRoute) else nav.playQueue(rest.map { it.id })
                    }
                },
                onStoryOrder = { id, on -> scope.launch { container.setRecapPlaylist(id, on) } },
                onPlayFrom = { pl, item ->
                    scope.launch {
                        container.playlists.startedFrom(pl.id, item.id)
                        // In story order, a "Last time" recap first when one is due.
                        if (pl.id in displayPrefs.recapPlaylists && container.recapBefore(item, pl) != null) nav.navigate(RecapRoute) else gate.play(item)
                    }
                },
            )
    }
    ProvideDisplayPrefs(container, userId) {
    NitrateTheme {
    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides menuCatalog) {
    NavHost(
        navController = nav,
        startDestination = startRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<ProfilesRoute> {
            dev.nitrate.tv.ui.profiles.ProfilesScreen(
                container,
                onOpened = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } },
                onSignInAgain = { a -> nav.navigate(LoginRoute(a.serverUrl, a.serverName)) },
                onAdd = { nav.navigate(ConnectRoute) },
            )
        }
        composable<ConnectRoute> {
            ConnectScreen(
                repository = container.server,
                onConnected = { url, name -> nav.navigate(LoginRoute(url, name)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                repository = container.server,
                serverUrl = route.serverUrl,
                serverName = route.serverName,
                onLoggedIn = {
                    scope.launch { container.onSignedIn() }
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<HomeRoute> {
            dev.nitrate.tv.ui.partners.TogetherHost(container, partnersOn, onPlay = { item -> gate.play(item) }, onOpenShow = { id -> nav.navigate(DetailsRoute(id)) }) {
            HomeScreen(
                repository = container.server,
                displaySettings = container.displaySettings,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
                onSettings = { nav.navigate(SettingsHubRoute) },
                onOffline = { nav.navigate(OfflineRoute) { popUpTo(0) { inclusive = true } } },
                changes = container.serverChanges,
                dataCache = container.dataCache,
                customPages = customPages,
                onOpenPage = { id -> nav.navigate(CustomPageRoute(id)) },
                playlistPage = { id, header -> playlistPage(id, header) { savePrefs { p -> p.copy(homePlaylists = p.homePlaylists - id, menuPlaylists = p.menuPlaylists - id) } } },
                onSignOut = {
                    scope.launch {
                        container.signOut()
                        val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                        nav.navigate(next) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
            }
        }
        composable<DetailsRoute> { entry ->
            // Theme music while on details pages (on by default on TV).
            val themeOn = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.themeMusic ?: true
            val themeId = entry.toRoute<DetailsRoute>().itemId
            androidx.compose.runtime.DisposableEffect(themeId, themeOn) {
                if (themeOn) container.themeMusic.show(themeId) else container.themeMusic.stop()
                onDispose { container.themeMusic.hide(themeId) }
            }
            val route = entry.toRoute<DetailsRoute>()
            DetailsScreen(
                repository = container.server,
                itemId = route.itemId,
                seasonId = route.seasonId,
                onPlay = { item, fromStart -> gate.play(item, fromStart) },
                onPlayFrom = { item, at -> gate.play(item, startMs = at) },
                onOpen = nav::openItem,
                onManage = if (account?.isAdmin == true) { id -> nav.navigate(ManageItemRoute(id)) } else null,
                canCollect = account?.canManageCollections == true,
                onPlayQueue = { items -> listen(items) },
                onBrowse = { title, studio, year -> nav.navigate(PersonRoute("", title, studio, year)) },
                checkPlayback = { item -> container.checkPlayback(item.id, item.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO, item) },
                trackSetup = { item -> container.trackSetup(item) },
                pickTrack = { item, setup, kind, index -> container.pickTrack(item, setup, kind, index) },
                applyPlaybackFix = { item, fix -> container.applyPlaybackFix(item.id, fix) },
                watchPartners = partnersOn,
            )
        }
        composable<ComicRoute> { entry ->
            dev.nitrate.tv.ui.comics.ComicReaderScreen(
                container, entry.toRoute<ComicRoute>().itemId, onExit = { nav.popBackStack() },
                onNext = { id -> nav.navigate(ComicRoute(id)) { popUpTo<ComicRoute> { inclusive = true } } },
            )
        }
        composable<RecapRoute> {
            dev.nitrate.tv.ui.player.RecapScreen(container, onPlayOn = { recap ->
                nav.popBackStack()
                if (recap.then.isNotEmpty()) nav.playQueue(listOf(recap.next.id) + recap.then) else nav.navigate(PlayerRoute(recap.next.id))
            }, onClip = { clip -> nav.navigate(PlayerRoute(clip.item.id, startMs = clip.startMs, clipEndMs = clip.endMs)) }, onBack = { nav.popBackStack() })
        }
        composable<PrerollRoute> { entry ->
            val route = entry.toRoute<PrerollRoute>()
            dev.nitrate.tv.ui.preroll.PrerollScreen(dev.nitrate.tv.ui.preroll.PrerollPlan.steps, dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.prerolls) { videos ->
                nav.popBackStack()
                // The intros and own videos play in the player, then the title.
                if (videos.isEmpty()) nav.navigate(PlayerRoute(route.itemId, route.fromStart)) else nav.playQueue(videos.map { it.id } + route.itemId)
            }
        }
        composable<PrerollSettingsRoute> { dev.nitrate.tv.ui.preroll.PrerollSettingsScreen(container) }
        composable<WatchPartnersRoute> {
            dev.nitrate.tv.ui.partners.WatchPartnersScreen(container) { id -> nav.navigate(DetailsRoute(id)) }
        }
        composable<SettingsHubRoute> {
            val easy = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.easyMode
            var askSignOut by remember { mutableStateOf(false) }
            val signOut: () -> Unit = {
                scope.launch {
                    container.signOut()
                    val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                    nav.navigate(next) { popUpTo(0) { inclusive = true } }
                }
            }
            var reportMessage by remember { mutableStateOf<String?>(null) }
            dev.nitrate.tv.ui.settings.SettingsHubScreen(
                listOf(
                    dev.nitrate.tv.ui.settings.SettingsSection(null, listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Picture & sound", "Sound, speakers, audio sync, frame rate; Advanced for formats and fixes") { nav.navigate(SettingsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Subtitles", "Size, colour, background and styled subtitles") { nav.navigate(SubtitleSettingsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Display", "Home and menu, colours, screensaver, what's shown about titles") { nav.navigate(DisplaySettingsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("While watching", "Before it plays, scenes in the credits, recaps, spoilers, audio description, theme music, \"Still watching?\"") { nav.navigate(WhileWatchingRoute) },
                    )),
                    dev.nitrate.tv.ui.settings.SettingsSection("Account & servers", listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Switch user", "Other users and servers, profile PINs") { nav.navigate(ProfilesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Servers", "Local, VPN and remote addresses; add, switch and choose the primary") { nav.navigate(ServerAddressesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Server-side settings", "Languages, subtitles and permissions set on your Jellyfin server, not on this TV") { nav.navigate(ServerSettingsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Display profile", "Which profile this TV uses; new, rename, import a radial menu") { nav.navigate(DisplayProfilesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Device backup", "This TV's settings on your server; restore another device's") { nav.navigate(DeviceBackupRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Sign out", container.server.serverUrl) { if (easy) askSignOut = true else signOut() },
                    )),
                    dev.nitrate.tv.ui.settings.SettingsSection("Your watching", listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Comic catalogs", "Read from an OPDS server (Komga, Kavita, Calibre…) or CLU; add \"Comics\" to the menu in Display → Menu stops") { nav.navigate(CatalogsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Your year", "What you've watched and listened to: top shows, genres and artists") { nav.navigate(StatsRoute) },
                    ) + (if (companion?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Watch diary", "Every film and episode you finish, with your ratings and notes") { nav.navigate(DiaryRoute) },
                    ) else emptyList()) + (if (partnersOn) listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Watch partners", "Shows you watch with someone, requests, and keeping your account private") { nav.navigate(WatchPartnersRoute) },
                    ) else emptyList()) +
                        enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> dev.nitrate.tv.ui.settings.SettingsLink(p.screenLabel) { nav.navigate(PluginScreenRoute(p.pluginId)) } }),
                    dev.nitrate.tv.ui.settings.SettingsSection("Tools", listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Checking how things play", "A guide to these tools: which to use, how, and what to do about washed-out HDR, missing surround or stutter") { nav.navigate(GuideRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Advanced playback settings", "The formats this TV plays (Always, Never), learned failures, Dolby Vision profile 7, tunneling: what the tools' fixes change") { nav.navigate(AdvancedPlaybackRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Playback test", "Plays short samples to find what this TV really plays") { nav.navigate(PlaybackTestRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Audio channel test", "A voice from each speaker in turn") { nav.navigate(AudioChannelTestRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("TV & receiver check", "Clips that should make the TV show HDR or Dolby Vision and the receiver its sound format: say what you see") { nav.navigate(TvCheckRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Library playback overview", "Every film and episode you can see, and how each plays on this TV: as it is, converted, and why") { nav.navigate(PlaybackOverviewRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Server speed check", "Is slowness the network, the server or this TV? Times it and shows you") { nav.navigate(SpeedCheckRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Query analyzer", "Every request Nitrate makes to your server: which page asked, what it cost, what was shared, and hints for making pages lighter") { nav.navigate(QueryAnalyzerRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Send device report", "What this TV plays and how it's set, saved in your server's logs for troubleshooting") {
                            reportMessage = "Sending…"
                            scope.launch {
                                reportMessage = container.sendPlaybackReport().fold(
                                    { name -> "Device report saved on your server as $name (Jellyfin Dashboard → Logs)" },
                                    { e -> "Couldn't send the device report: ${e.message ?: e::class.simpleName}" },
                                )
                            }
                        },
                    ) + (if (account?.isAdmin == true) listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Household devices", "How every device in the house plays the library, its tests and what its users saw at their TVs") { nav.navigate(HouseholdDevicesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Library health", "Missing artwork and details, duplicates, missing episodes, what this TV always converts") { nav.navigate(LibraryHealthRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Manage server", "Libraries, tasks, activity, devices and users") { nav.navigate(ServerAdminRoute) },
                    ) else emptyList())),
                    dev.nitrate.tv.ui.settings.SettingsSection("About", listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Updates", "Nitrate ${container.updater.versionName}; check now or when Nitrate opens") { nav.navigate(UpdatesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Plugins", "Built-in extras, on or off") { nav.navigate(PluginsRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Open-source licences", "FFmpeg, Media3, Kotlin and the rest Nitrate is built with") { nav.navigate(LicensesRoute) },
                    )),
                ),
                message = reportMessage,
                admin = account?.isAdmin == true,
                onFound = { place ->
                    when (place) {
                        dev.nitrate.core.settings.SettingsPlace.SOUND -> nav.navigate(SettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.SOUND_ADVANCED -> nav.navigate(AdvancedPlaybackRoute)
                        dev.nitrate.core.settings.SettingsPlace.SUBTITLES -> nav.navigate(SubtitleSettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DISPLAY, dev.nitrate.core.settings.SettingsPlace.DISPLAY_LAYOUT -> nav.navigate(DisplaySettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DISPLAY_SHOWN -> nav.navigate(ShownSettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DISPLAY_EFFECTS -> nav.navigate(EffectsSettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DISPLAY_PAGES -> nav.navigate(PageSetupRoute)
                        dev.nitrate.core.settings.SettingsPlace.MENU_STOPS -> nav.navigate(MenuStopsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DISPLAY_PROFILE -> nav.navigate(DisplayProfilesRoute)
                        dev.nitrate.core.settings.SettingsPlace.WATCHING -> nav.navigate(WhileWatchingRoute)
                        dev.nitrate.core.settings.SettingsPlace.BEFORE_IT_PLAYS -> nav.navigate(PrerollSettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.PROFILES -> nav.navigate(ProfilesRoute)
                        dev.nitrate.core.settings.SettingsPlace.SERVERS -> nav.navigate(ServerAddressesRoute)
                        dev.nitrate.core.settings.SettingsPlace.SERVER_SETTINGS -> nav.navigate(ServerSettingsRoute)
                        dev.nitrate.core.settings.SettingsPlace.BACKUP -> nav.navigate(DeviceBackupRoute)
                        dev.nitrate.core.settings.SettingsPlace.STATS -> nav.navigate(StatsRoute)
                        dev.nitrate.core.settings.SettingsPlace.DIARY -> nav.navigate(DiaryRoute)
                        dev.nitrate.core.settings.SettingsPlace.PARTNERS -> nav.navigate(WatchPartnersRoute)
                        dev.nitrate.core.settings.SettingsPlace.CATALOGS -> nav.navigate(CatalogsRoute)
                        dev.nitrate.core.settings.SettingsPlace.GUIDE -> nav.navigate(GuideRoute)
                        dev.nitrate.core.settings.SettingsPlace.PLAYBACK_TEST -> nav.navigate(PlaybackTestRoute)
                        dev.nitrate.core.settings.SettingsPlace.AUDIO_TEST -> nav.navigate(AudioChannelTestRoute)
                        dev.nitrate.core.settings.SettingsPlace.TV_CHECK -> nav.navigate(TvCheckRoute)
                        dev.nitrate.core.settings.SettingsPlace.OVERVIEW -> nav.navigate(PlaybackOverviewRoute)
                        dev.nitrate.core.settings.SettingsPlace.SPEED_CHECK -> nav.navigate(SpeedCheckRoute)
                        dev.nitrate.core.settings.SettingsPlace.QUERY_ANALYZER -> nav.navigate(QueryAnalyzerRoute)
                        dev.nitrate.core.settings.SettingsPlace.HOUSEHOLD -> nav.navigate(HouseholdDevicesRoute)
                        dev.nitrate.core.settings.SettingsPlace.HEALTH -> nav.navigate(LibraryHealthRoute)
                        dev.nitrate.core.settings.SettingsPlace.MANAGE_SERVER -> nav.navigate(ServerAdminRoute)
                        dev.nitrate.core.settings.SettingsPlace.UPDATES -> nav.navigate(UpdatesRoute)
                        dev.nitrate.core.settings.SettingsPlace.PLUGINS -> nav.navigate(PluginsRoute)
                        dev.nitrate.core.settings.SettingsPlace.LICENCES -> nav.navigate(LicensesRoute)
                        // Phone only.
                        dev.nitrate.core.settings.SettingsPlace.NOTIFICATIONS, dev.nitrate.core.settings.SettingsPlace.REMOTE -> Unit
                    }
                },
            )
            // Easy mode asks first.
            if (askSignOut) androidx.compose.ui.window.Dialog(onDismissRequest = { askSignOut = false }) {
                val yes = remember { androidx.compose.ui.focus.FocusRequester() }
                Column(
                    Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(28.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    Text("Sign out?", style = MaterialTheme.typography.headlineSmall)
                    Text("You'll need your password to sign in again.", style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        androidx.tv.material3.Button(onClick = { askSignOut = false }, modifier = Modifier.focusRequester(yes)) { Text("Cancel") }
                        androidx.tv.material3.OutlinedButton(onClick = { askSignOut = false; signOut() }) { Text("Sign out") }
                    }
                }
                LaunchedEffect(Unit) { runCatching { yes.requestFocus() } }
            }
        }
        composable<SettingsRoute> {
            PlaybackSettingsScreen(container.playbackSettings, container.capabilities, onAdvanced = { nav.navigate(AdvancedPlaybackRoute) })
        }
        composable<AdvancedPlaybackRoute> { PlaybackSettingsScreen(container.playbackSettings, container.capabilities, dev.nitrate.tv.ui.settings.PlaybackPage.ADVANCED) }
        composable<SubtitleSettingsRoute> { PlaybackSettingsScreen(container.playbackSettings, container.capabilities, dev.nitrate.tv.ui.settings.PlaybackPage.SUBTITLES) }
        composable<UpdatesRoute> { dev.nitrate.tv.ui.settings.UpdatesScreen(container.updater) }
        composable<OfflineRoute> {
            OfflineScreen(
                repository = container.server,
                screens = enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.pluginId)) } },
                onPlugins = { nav.navigate(PluginsRoute) },
                onServers = { nav.navigate(ServerAddressesRoute) },
                onOnline = {
                    container.settingsSync.pull()
                    container.backups.onSignedIn()
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
                onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<PagesRoute> { PagesScreen(container) { id, editNow -> nav.navigate(CustomPageRoute(id, editNow)) } }
        composable<CustomPageRoute> { entry ->
            val route = entry.toRoute<CustomPageRoute>()
            // Laid out like Home: its own layout (full art lays the menu over it).
            val pagePrefs = dev.nitrate.core.pages.PageLooks.forPage(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current, dev.nitrate.core.pages.PageKeys.custom(route.pageId))
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.page(route.pageId), overlay = pagePrefs.tvArrangement.fullArt) { insets ->
                CustomPageScreen(container, route.pageId, route.editNow, onOpen = nav::openItem, onPlay = { item -> gate.play(item) }, onDeleted = { nav.popBackStack() }, insets = insets)
            }
        }
        composable<DisplaySettingsRoute> { DisplaySettingsScreen(container, onMenuStops = { nav.navigate(MenuStopsRoute) }, onShown = { nav.navigate(ShownSettingsRoute) }, onPages = { nav.navigate(PageSetupRoute) }, onEffects = { nav.navigate(EffectsSettingsRoute) }) }
        composable<EffectsSettingsRoute> { dev.nitrate.tv.ui.effects.EffectsSettingsScreen(container) }
        composable<PageSetupRoute> { dev.nitrate.tv.ui.pages.PageSetupScreen(container, onHome = { nav.navigate(HomeSetupRoute) }) { lib -> nav.navigate(LibraryTabsRoute(lib.id)) } }
        composable<HomeSetupRoute> { dev.nitrate.tv.ui.pages.HomeSetupScreen(container) }
        composable<LibraryTabsRoute> { entry -> dev.nitrate.tv.ui.pages.LibraryTabsScreen(container, entry.toRoute<LibraryTabsRoute>().libraryId) }
        composable<ShownSettingsRoute> { DisplaySettingsScreen(container, dev.nitrate.tv.ui.pages.DisplayPage.SHOWN) }
        composable<WhileWatchingRoute> { DisplaySettingsScreen(container, dev.nitrate.tv.ui.pages.DisplayPage.WATCHING, onPrerolls = { nav.navigate(PrerollSettingsRoute) }) }
        composable<LicensesRoute> { dev.nitrate.tv.ui.settings.LicensesScreen() }
        composable<SpeedCheckRoute> { dev.nitrate.tv.ui.settings.SpeedCheckScreen(container) }
        composable<QueryAnalyzerRoute> { dev.nitrate.tv.ui.settings.QueryAnalyzerScreen(container) }
        composable<StatsRoute> { dev.nitrate.tv.ui.settings.StatsScreen(container.server) }
        composable<DiaryRoute> { dev.nitrate.tv.ui.settings.DiaryScreen(container.server) }
        composable<ServerSettingsRoute> { dev.nitrate.tv.ui.settings.ServerSettingsScreen(container) }
        composable<DisplayProfilesRoute> { dev.nitrate.tv.ui.settings.DisplayProfilesScreen(container) }
        composable<DeviceBackupRoute> { dev.nitrate.tv.ui.settings.DeviceBackupScreen(container.backups) }
        composable<ServerAddressesRoute> {
            dev.nitrate.tv.ui.settings.ServersScreen(
                container,
                onSwitch = { server ->
                    scope.launch {
                        // A single unlocked account opens straight away; otherwise pick on the profiles screen.
                        val only = server.accounts.singleOrNull()
                        if (only != null && !container.profiles.hasPin(only.key) && container.switchAccount(only.key) == dev.nitrate.core.model.SessionState.VALID) {
                            nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                        } else {
                            nav.navigate(ProfilesRoute)
                        }
                    }
                },
                onAdd = { nav.navigate(ConnectRoute) },
            )
        }
        composable<MenuStopsRoute> { dev.nitrate.tv.ui.settings.MenuStopsScreen(container) }
        composable<ManageItemRoute> { entry ->
            ManageItemScreen(container.server, entry.toRoute<ManageItemRoute>().itemId, themePreview = { id, on -> if (on) container.themeMusic.show(id) else container.themeMusic.stop() })
        }
        composable<ServerAdminRoute> { ServerAdminScreen(container.server) }
        composable<HouseholdDevicesRoute> { dev.nitrate.tv.ui.settings.HouseholdDevicesScreen(container, onOpen = { nav.navigate(DetailsRoute(it)) }) }
        composable<GuideRoute> { dev.nitrate.tv.ui.settings.GuideScreen(admin = account?.isAdmin == true) }
        composable<TvCheckRoute> { dev.nitrate.tv.ui.settings.TvCheckScreen(container) }
        composable<PlaybackOverviewRoute> {
            dev.nitrate.tv.ui.settings.PlaybackOverviewScreen(
                container,
                onOpen = { nav.navigate(DetailsRoute(it)) },
                // Test plays go straight to the player: no guards, recaps or pre-rolls.
                onTest = { ids -> ids.firstOrNull()?.let { nav.navigate(PlayerRoute(it, queue = ids.drop(1).joinToString(","), test = true)) } },
            )
        }
        composable<LibraryHealthRoute> {
            dev.nitrate.tv.ui.settings.LibraryHealthScreen(container, onOpen = { nav.navigate(DetailsRoute(it)) }, onManage = { nav.navigate(ManageItemRoute(it)) })
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            dev.nitrate.tv.ui.search.SearchScreen(
                repository = container.server,
                history = container.searchHistory,
                initialQuery = route.query,
                startWithVoice = route.voice,
                onOpen = nav::openItem,
                onPerson = { p -> nav.navigate(PersonRoute(p.id, p.name)) },
                linesOn = companion?.features?.contains(dev.nitrate.core.lines.SpokenLines.FEATURE) == true,
                onPlayLine = { line ->
                    scope.launch {
                        runCatching { container.server.getItem(line.itemId) }.getOrNull()?.let { gate.play(it, startMs = dev.nitrate.core.lines.SpokenLines.startAt(line)) }
                    }
                },
            )
        }
        composable<PersonRoute> { entry ->
            val route = entry.toRoute<PersonRoute>()
            dev.nitrate.tv.ui.search.PersonScreen(container.server, dev.nitrate.core.model.Person(route.personId, route.name), onOpen = nav::openItem, studio = route.studio, year = route.year, displaySettings = container.displaySettings)
        }
        composable<CatalogsRoute> {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.COMICS, overlay = false) {
                dev.nitrate.tv.ui.comics.CatalogsScreen(container, onOpen = { id -> nav.navigate(CatalogFeedRoute(id)) })
            }
        }
        composable<CatalogFeedRoute> { entry ->
            val route = entry.toRoute<CatalogFeedRoute>()
            dev.nitrate.tv.ui.comics.CatalogFeedScreen(
                container, route.catalogId, route.target,
                onOpen = { target -> nav.navigate(CatalogFeedRoute(route.catalogId, target)) },
                onRead = { book -> nav.navigate(CatalogBookRoute(route.catalogId, catalogJson.encodeToString(dev.nitrate.core.catalogs.CatalogBook.serializer(), book))) },
            )
        }
        composable<CatalogBookRoute> { entry ->
            val route = entry.toRoute<CatalogBookRoute>()
            dev.nitrate.tv.ui.comics.CatalogReaderScreen(container, route.catalogId, catalogJson.decodeFromString(dev.nitrate.core.catalogs.CatalogBook.serializer(), route.book), onExit = { nav.popBackStack() })
        }
        composable<MoreRoute> { dev.nitrate.tv.ui.home.MoreScreen() }
        composable<ChannelsRoute> {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.CHANNELS, overlay = false) {
                dev.nitrate.tv.ui.channels.ChannelsScreen(
                    container.server,
                    admin = account?.isAdmin == true,
                    onPlayQueue = { ids -> nav.playQueue(ids) },
                    onEdit = { id -> nav.navigate(ChannelEditorRoute(id.orEmpty())) },
                )
            }
        }
        composable<ChannelEditorRoute> { entry ->
            val id = entry.toRoute<ChannelEditorRoute>().channelId.ifEmpty { null }
            dev.nitrate.tv.ui.channels.ChannelEditorScreen(container.server, id) { nav.popBackStack() }
        }
        composable<WatchTogetherRoute> {
            dev.nitrate.tv.ui.together.WatchTogetherScreen(container.syncPlay, account?.name?.let { "$it's group" } ?: "Movie night", chat = container.chat)
        }
        composable<PluginsRoute> { PluginsScreen(container.plugins, TV_PLUGIN_UI, pluginContext) }
        composable<PluginScreenRoute> { entry ->
            val id = entry.toRoute<PluginScreenRoute>().pluginId
            TV_PLUGIN_UI.filterIsInstance<ScreenPlugin>().firstOrNull { it.pluginId == id }?.Screen(pluginContext)
        }
        composable<AudioChannelTestRoute> { AudioChannelTestScreen(container.server) }
        composable<PlaybackTestRoute> {
            PlaybackTestScreen(store = container.playbackSettings, capabilities = container.capabilities)
        }
        composable<LibraryRoute> { entry ->
            val libraryId = entry.toRoute<LibraryRoute>().libraryId
            val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
            // Library pages are laid out like Home unless set otherwise; full art has the menu over it.
            val libraryFullArt = dev.nitrate.core.pages.PageLooks.forPage(prefs, dev.nitrate.core.pages.PageKeys.library(libraryId)).tvArrangement.fullArt
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library(libraryId), overlay = libraryFullArt) { insets ->
                LibraryScreen(
                    fullArt = libraryFullArt,
                    insets = insets,
                    repository = container.server,
                    displaySettings = container.displaySettings,
                    libraryId = libraryId,
                    changes = container.serverChanges,
                    dataCache = container.dataCache,
                    onOpen = nav::openItem,
                    onPlay = { item -> gate.play(item) },
                    admin = account?.isAdmin == true,
                )
            }
        }
        composable<PlaylistRoute> { entry ->
            val id = entry.toRoute<PlaylistRoute>().playlistId
            playlistPage(id, null) {
                savePrefs { p -> p.copy(homePlaylists = p.homePlaylists - id, menuPlaylists = p.menuPlaylists - id) }
                nav.popBackStack()
            }
        }
        composable<NowPlayingRoute> {
            val listening = container.listening
            if (listening == null) LaunchedEffect(Unit) { nav.popBackStack() }
            else dev.nitrate.tv.ui.music.NowPlayingScreen(listening, container.server, onBack = { nav.popBackStack() }, bookmarkStore = container.bookmarks)
        }
        composable<PlayerRoute> { entry ->
            LaunchedEffect(Unit) {
                container.themeMusic.stop()
                // A video takes over from the music.
                container.listening?.pause()
            }
            // Leaving the player: what was watched with partners counts for them too.
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { container.afterWatching() } }
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                repository = container.server,
                playbackSettings = container.playbackSettings,
                itemId = route.itemId,
                fromStart = route.fromStart,
                onExit = { nav.popBackStack() },
                panels = enabledPlugins.filterIsInstance<PlayerPanel>(),
                pluginContext = pluginContext,
                trackMemory = container.trackMemory,
                remote = container.remote,
                // Back from an extra: on from where it was left.
                startMs = entry.savedStateHandle.get<Long>(RESUME_AT) ?: route.startMs.takeIf { it >= 0 },
                clipEndMs = route.clipEndMs.takeIf { it >= 0 },
                // An extra plays on top; Back or its end comes back here, at the same spot.
                onPlayExtra = if (route.clipEndMs < 0 && !route.test) { extra, at ->
                    entry.savedStateHandle[RESUME_AT] = at
                    nav.navigate(PlayerRoute(extra.id, fromStart = true))
                } else null,
                audioIndex = route.audioIndex.takeIf { it != NO_TRACK },
                subtitleIndex = route.subtitleIndex.takeIf { it != NO_TRACK },
                describedAudio = container::prefersDescribedAudio,
                // Up Next (episodes, a film's collection) only outside queues and recap clips.
                onPlayNext = if (route.queue.isBlank() && route.clipEndMs < 0 && !route.test) { next -> nav.popBackStack(); gate.play(next) } else null,
                syncPlay = container.syncPlay.takeIf { !route.test },
                stillWatching = container.stillWatching,
                skipWatch = container.skipWatch,
                playlistNext = if (route.queue.isBlank() && route.clipEndMs < 0 && !route.test) { item -> container.playlists.contextOf(item) } else null,
                stillWatchingRewind = container::stillWatchingRewind,
                sendLog = { session -> container.sendPlaybackReport(session) },
                capabilities = container.capabilities,
                chat = container.chat,
                testPlay = if (route.test) { result -> scope.launch { container.saveTestPlay(result) } } else null,
                onEnded = {
                    val next = route.queue.split(',').filter(String::isNotBlank)
                    if (route.test && next.isNotEmpty()) {
                        // A sample test: on to the next kind of file.
                        nav.popBackStack()
                        nav.navigate(PlayerRoute(next.first(), queue = next.drop(1).joinToString(","), test = true))
                    } else if (next.isEmpty()) {
                        nav.popBackStack()
                    } else {
                        nav.popBackStack()
                        nav.playQueue(next)
                    }
                },
            )
        }
    }
    }
    if (quickSwitch) dev.nitrate.tv.ui.profiles.QuickSwitchDialog(
        container,
        onOpened = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } },
        onSignInAgain = { a -> nav.navigate(LoginRoute(a.serverUrl, a.serverName)) },
        onDismiss = { quickSwitch = false },
    )
    PlaybackGateDialog(gate)
    dev.nitrate.tv.ui.components.UpdatePrompt(container.updater)
    dev.nitrate.tv.ui.settings.BackupPrompt(container.backups)
    dev.nitrate.tv.ui.player.SpeakersSetupPrompt(container)
    if (companion?.features?.contains("together") == true) dev.nitrate.tv.ui.together.WatchInvitePrompt(container)
    LaunchedEffect(Unit) { container.updater.checkIfDue() }
    // What other apps ask of this TV: play something, show a message, change volume.
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The home screen and voice search: carried out once signed in (not over sign-in or the profile picker).
    val pendingRequest by requests.collectAsState()
    val currentEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(pendingRequest, currentEntry) {
        val request = pendingRequest ?: return@LaunchedEffect
        val signedIn = nav.currentBackStack.value.any { it.destination.hasRoute(HomeRoute::class) }
        // Not over the profile picker or a sign-in either: wait until one is chosen.
        val picking = currentEntry?.destination?.let { d -> d.hasRoute(ProfilesRoute::class) || d.hasRoute(ConnectRoute::class) || d.hasRoute(LoginRoute::class) } == true
        if (!signedIn || picking) return@LaunchedEffect
        requests.value = null
        when (request) {
            is TvRequest.Search -> nav.navigate(SearchRoute(request.query, request.voice))
            is TvRequest.Open -> nav.navigate(DetailsRoute(request.itemId))
            is TvRequest.Play -> runCatching { container.server.getItem(request.itemId) }.getOrNull()?.let { gate.play(it) }
        }
    }
    // The ambient screensaver: after a while with nothing pressed, outside the player.
    val ambientMinutes by remember(userId) {
        userId?.let { id -> container.displaySettings.prefs(id).map { it.tvAmbientMinutes } } ?: kotlinx.coroutines.flow.flowOf(0)
    }.collectAsState(0)
    var ambient by remember { mutableStateOf<List<dev.nitrate.core.ambient.AmbientSlide>?>(null) }
    val watching = currentEntry?.destination?.let { it.hasRoute(PlayerRoute::class) || it.hasRoute(RecapRoute::class) } == true
    val signedInNow = currentEntry != null && nav.currentBackStack.value.any { it.destination.hasRoute(HomeRoute::class) }
    // Leaving the app closes the screensaver, so coming back never finds it on (it used to come on
    // while the app was in the background and still be showing on return).
    val appVisible by dev.nitrate.tv.ui.ambient.TvIdle.visible.collectAsState()
    LaunchedEffect(appVisible) { if (!appVisible) ambient = null }
    LaunchedEffect(ambientMinutes, watching, signedInNow, appVisible) {
        if (!signedInNow || ambientMinutes == 0 || watching || !appVisible) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10_000)
            if (ambient != null || !dev.nitrate.core.ambient.Ambient.due(dev.nitrate.tv.ui.ambient.TvIdle.idleMs(), ambientMinutes, watching)) continue
            val rows = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin("Screensaver") { container.server.watchingRows(latest = true) } }.getOrNull() ?: continue
            val slides = dev.nitrate.core.ambient.Ambient.slides(rows) { container.server.backdropUrl(it) != null }
            // Still nothing pressed while Home's rows were fetched.
            if (slides.isNotEmpty() && dev.nitrate.tv.ui.ambient.TvIdle.visible.value && dev.nitrate.core.ambient.Ambient.due(dev.nitrate.tv.ui.ambient.TvIdle.idleMs(), ambientMinutes, watching)) {
                container.themeMusic.onAppHidden()
                ambient = slides
            }
        }
    }
    ambient?.let { slides ->
        dev.nitrate.tv.ui.ambient.AmbientScreen(
            slides, container.server,
            onOpen = { item -> dev.nitrate.tv.ui.ambient.TvIdle.press(); ambient = null; nav.navigate(DetailsRoute(item.id)) },
            onDismiss = { dev.nitrate.tv.ui.ambient.TvIdle.press(); ambient = null },
        )
    }
    // The home screen channel and Play Next row follow what's watched.
    val homeChannels = remember { dev.nitrate.tv.channels.HomeChannels(context, container.server) }
    LaunchedEffect(account?.id) {
        homeChannels.schedule()
        homeChannels.refreshSoon()
    }
    val leftPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == false
    LaunchedEffect(leftPlayer) { if (leftPlayer) homeChannels.refreshSoon() }
    // Watch together: the group started something while no player was open.
    LaunchedEffect(Unit) {
        container.syncPlay.openRequests.collect { q ->
            // In place of a player already open, so Back doesn't return to the old one.
            q.currentItemId?.let { nav.navigate(PlayerRoute(it, fromStart = false, startMs = q.startMs)) { popUpTo<PlayerRoute> { inclusive = true } } }
        }
    }
    LaunchedEffect(Unit) {
        container.remote.events.collect { e ->
            when (e) {
                is dev.nitrate.core.events.ServerEvent.Play -> e.itemIds.firstOrNull()?.let { first ->
                    nav.navigate(
                        PlayerRoute(
                            first, fromStart = e.startMs == null, queue = e.itemIds.drop(1).joinToString(","), startMs = e.startMs ?: -1,
                            audioIndex = e.audioIndex ?: NO_TRACK, subtitleIndex = e.subtitleIndex ?: NO_TRACK,
                        ),
                    ) {
                        popUpTo<PlayerRoute> { inclusive = true }
                    }
                }
                is dev.nitrate.core.events.ServerEvent.General -> when (e.name) {
                    "DisplayMessage" -> message = (e.arguments["Header"] ?: "Message") to e.arguments["Text"].orEmpty()
                    "VolumeUp", "VolumeDown", "ToggleMute", "Mute", "Unmute" -> {
                        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        val dir = when (e.name) {
                            "VolumeUp" -> android.media.AudioManager.ADJUST_RAISE
                            "VolumeDown" -> android.media.AudioManager.ADJUST_LOWER
                            "Mute" -> android.media.AudioManager.ADJUST_MUTE
                            "Unmute" -> android.media.AudioManager.ADJUST_UNMUTE
                            else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                        }
                        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI)
                    }
                    // The phone's arrow pad: pressed as if on this TV's own remote.
                    "MoveUp", "MoveDown", "MoveLeft", "MoveRight", "Select", "Back" -> {
                        val key = when (e.name) {
                            "MoveUp" -> android.view.KeyEvent.KEYCODE_DPAD_UP
                            "MoveDown" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            "MoveLeft" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            "MoveRight" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            "Select" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            else -> android.view.KeyEvent.KEYCODE_BACK
                        }
                        (context.findActivityOrNull())?.let { a ->
                            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                        }
                    }
                    "GoHome" -> nav.navigate(HomeRoute) { popUpTo(HomeRoute) { inclusive = true }; launchSingleTop = true }
                    else -> Unit
                }
                else -> Unit
            }
        }
    }
    message?.let { (header, text) ->
        dev.nitrate.tv.ui.lists.TvDialog({ message = null }, width = 560) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp)) {
                Text(header, style = MaterialTheme.typography.headlineSmall)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            }
        }
        LaunchedEffect(header, text) { kotlinx.coroutines.delay(10_000); message = null }
    }
    }
    }
}

/** Where selecting an item goes. Seasons open their series with that season selected. */
fun NavController.openItem(item: MediaItem) {
    val seriesId = item.seriesId
    when {
        item.kind == ItemKind.SEASON && seriesId != null ->
            navigate(DetailsRoute(seriesId, seasonId = item.id))
        item.kind == ItemKind.LIBRARY ->
            navigate(LibraryRoute(item.id))
        item.kind == ItemKind.PLAYLIST -> navigate(PlaylistRoute(item.id))
        else -> navigate(DetailsRoute(item.id))
    }
}

/** Plays the first id, queueing the rest. Queue items always start from the beginning. */
fun NavController.playQueue(ids: List<String>) {
    val first = ids.firstOrNull() ?: return
    navigate(PlayerRoute(first, fromStart = true, queue = ids.drop(1).joinToString(",")))
}


/** The activity behind [this] context, if any. */
private tailrec fun android.content.Context.findActivityOrNull(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
