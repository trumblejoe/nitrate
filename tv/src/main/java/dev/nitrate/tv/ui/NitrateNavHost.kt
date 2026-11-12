package dev.nitrate.tv.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
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
@Serializable object OfflineRoute
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null)
@Serializable object SettingsRoute
@Serializable object SettingsHubRoute
@Serializable object PlaybackTestRoute
@Serializable object AudioChannelTestRoute
@Serializable object PluginsRoute
@Serializable data class PluginScreenRoute(val pluginId: String)
@Serializable data class LibraryRoute(val libraryId: String)
@Serializable data class PlaylistRoute(val playlistId: String)
@Serializable object PagesRoute
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)
@Serializable object DisplaySettingsRoute
@Serializable object MenuStopsRoute
@Serializable object ServerAddressesRoute
@Serializable data class ManageItemRoute(val itemId: String)
@Serializable object ServerAdminRoute
@Serializable object WatchTogetherRoute
@Serializable object ProfilesRoute
@Serializable data class SearchRoute(val query: String = "", val voice: Boolean = false)
@Serializable data class PersonRoute(val personId: String, val name: String)

/** Asked of the app from outside: the home screen, voice search, the remote's search button. */
sealed interface TvRequest {
    data class Search(val query: String, val voice: Boolean) : TvRequest
    data class Open(val itemId: String) : TvRequest
    data class Play(val itemId: String) : TvRequest
}

/** [queue] is comma-separated item ids to play after [itemId]. */
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queue: String = "", val startMs: Long = -1)

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
    val gate = rememberPlaybackGate(container.plugins) { item, fromStart -> nav.navigate(PlayerRoute(item.id, fromStart)) }
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
    val menuCatalog = remember(libraries, customPages, nav) {
        dev.nitrate.tv.ui.home.menuCatalog(
            libraries, customPages,
            dev.nitrate.tv.ui.home.MenuActions(
                onSearch = { nav.navigate(SearchRoute(), fromHome) },
                onHome = { if (!nav.popBackStack(HomeRoute, inclusive = false)) nav.navigate(HomeRoute) },
                onLibrary = { lib -> nav.navigate(LibraryRoute(lib.id), fromHome) },
                onPage = { id -> nav.navigate(CustomPageRoute(id), fromHome) },
                onPages = { nav.navigate(PagesRoute, fromHome) },
                onTogether = { nav.navigate(WatchTogetherRoute, fromHome) },
                onSettings = { nav.navigate(SettingsHubRoute, fromHome) },
                onProfiles = { nav.navigate(ProfilesRoute) },
            ),
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
            HomeScreen(
                repository = container.server,
                displaySettings = container.displaySettings,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
                onSettings = { nav.navigate(SettingsHubRoute) },
                onOffline = { nav.navigate(OfflineRoute) { popUpTo(0) { inclusive = true } } },
                changes = container.serverChanges,
                customPages = customPages,
                onOpenPage = { id -> nav.navigate(CustomPageRoute(id)) },
                onSignOut = {
                    scope.launch {
                        container.signOut()
                        val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                        nav.navigate(next) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            DetailsScreen(
                repository = container.server,
                itemId = route.itemId,
                seasonId = route.seasonId,
                onPlay = { item, fromStart -> gate.play(item, fromStart) },
                onOpen = nav::openItem,
                onManage = if (account?.isAdmin == true) { id -> nav.navigate(ManageItemRoute(id)) } else null,
                canCollect = account?.canManageCollections == true,
                onPlayQueue = { items -> nav.playQueue(items.map { it.id }) },
            )
        }
        composable<SettingsHubRoute> {
            val signOut: () -> Unit = {
                scope.launch {
                    container.signOut()
                    val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                    nav.navigate(next) { popUpTo(0) { inclusive = true } }
                }
            }
            dev.nitrate.tv.ui.settings.SettingsHubScreen(
                listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Playback", "Formats, quality, tests and updates for this TV") { nav.navigate(SettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Display", "What's shown about items; follows your account") { nav.navigate(DisplaySettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Check for updates", "Nitrate ${container.updater.versionName}") { container.updater.check() },
                    dev.nitrate.tv.ui.settings.SettingsLink("Plugins", "Built-in extras, on or off") { nav.navigate(PluginsRoute) },
                ) +
                    (if (account?.isAdmin == true) listOf(dev.nitrate.tv.ui.settings.SettingsLink("Manage server", "Libraries, tasks, activity, devices and users") { nav.navigate(ServerAdminRoute) }) else emptyList()) +
                    enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> dev.nitrate.tv.ui.settings.SettingsLink(p.screenLabel) { nav.navigate(PluginScreenRoute(p.pluginId)) } } +
                    listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Servers", "Local, VPN and remote addresses; add, switch and choose the primary") { nav.navigate(ServerAddressesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Switch user", "Other users and servers, profile PINs") { nav.navigate(ProfilesRoute) },
                        dev.nitrate.tv.ui.settings.SettingsLink("Sign out", container.server.serverUrl, signOut),
                    ),
            )
        }
        composable<SettingsRoute> {
            PlaybackSettingsScreen(
                store = container.playbackSettings,
                capabilities = container.capabilities,
                onRunTest = { nav.navigate(PlaybackTestRoute) },
                onChannelTest = { nav.navigate(AudioChannelTestRoute) },
                updater = container.updater,
            )
        }
        composable<OfflineRoute> {
            OfflineScreen(
                repository = container.server,
                screens = enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.pluginId)) } },
                onPlugins = { nav.navigate(PluginsRoute) },
                onOnline = {
                    container.settingsSync.pull()
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
                onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<PagesRoute> { PagesScreen(container) { id, editNow -> nav.navigate(CustomPageRoute(id, editNow)) } }
        composable<CustomPageRoute> { entry ->
            val route = entry.toRoute<CustomPageRoute>()
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.page(route.pageId), overlay = false) {
                CustomPageScreen(container, route.pageId, route.editNow, onOpen = nav::openItem, onPlay = { item -> gate.play(item) }, onDeleted = { nav.popBackStack() })
            }
        }
        composable<DisplaySettingsRoute> { DisplaySettingsScreen(container) { nav.navigate(MenuStopsRoute) } }
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
        composable<ManageItemRoute> { entry -> ManageItemScreen(container.server, entry.toRoute<ManageItemRoute>().itemId) }
        composable<ServerAdminRoute> { ServerAdminScreen(container.server) }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            dev.nitrate.tv.ui.search.SearchScreen(
                repository = container.server,
                history = container.searchHistory,
                initialQuery = route.query,
                startWithVoice = route.voice,
                onOpen = nav::openItem,
                onPerson = { p -> nav.navigate(PersonRoute(p.id, p.name)) },
            )
        }
        composable<PersonRoute> { entry ->
            val route = entry.toRoute<PersonRoute>()
            dev.nitrate.tv.ui.search.PersonScreen(container.server, dev.nitrate.core.model.Person(route.personId, route.name), onOpen = nav::openItem)
        }
        composable<WatchTogetherRoute> {
            dev.nitrate.tv.ui.together.WatchTogetherScreen(container.syncPlay, account?.name?.let { "$it's group" } ?: "Movie night")
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
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library(libraryId), overlay = false) {
                LibraryScreen(
                    repository = container.server,
                    displaySettings = container.displaySettings,
                    libraryId = libraryId,
                    changes = container.serverChanges,
                    onOpen = nav::openItem,
                    onPlay = { item -> gate.play(item) },
                )
            }
        }
        composable<PlaylistRoute> { entry ->
            PlaylistScreen(
                repository = container.server,
                settings = container.displaySettings,
                playlistId = entry.toRoute<PlaylistRoute>().playlistId,
                onPlayQueue = { items -> nav.playQueue(items.map { it.id }) },
                onOpen = nav::openItem,
                onDeleted = { nav.popBackStack() },
            )
        }
        composable<PlayerRoute> { entry ->
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
                startMs = route.startMs.takeIf { it >= 0 },
                syncPlay = container.syncPlay,
                onEnded = {
                    val next = route.queue.split(',').filter(String::isNotBlank)
                    if (next.isEmpty()) {
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
    PlaybackGateDialog(gate)
    dev.nitrate.tv.ui.components.UpdatePrompt(container.updater)
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
        if (!signedIn) return@LaunchedEffect
        requests.value = null
        when (request) {
            is TvRequest.Search -> nav.navigate(SearchRoute(request.query, request.voice))
            is TvRequest.Open -> nav.navigate(DetailsRoute(request.itemId))
            is TvRequest.Play -> runCatching { container.server.getItem(request.itemId) }.getOrNull()?.let { gate.play(it) }
        }
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
            q.currentItemId?.let { nav.navigate(PlayerRoute(it, fromStart = false, startMs = q.startMs)) }
        }
    }
    LaunchedEffect(Unit) {
        container.remote.events.collect { e ->
            when (e) {
                is dev.nitrate.core.events.ServerEvent.Play -> e.itemIds.firstOrNull()?.let { first ->
                    nav.navigate(PlayerRoute(first, fromStart = e.startMs == null, queue = e.itemIds.drop(1).joinToString(","), startMs = e.startMs ?: -1))
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
                    else -> Unit
                }
                else -> Unit
            }
        }
    }
    message?.let { (header, text) ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { message = null }) {
            Column(Modifier.width(560.dp).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(28.dp)) {
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
