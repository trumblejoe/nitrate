package dev.nitrate.tv.ui

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
@Serializable object PlaybackTestRoute
@Serializable object AudioChannelTestRoute
@Serializable object PluginsRoute
@Serializable data class PluginScreenRoute(val pluginId: String)
@Serializable data class LibraryRoute(val libraryId: String)
@Serializable data class PlaylistRoute(val playlistId: String)
@Serializable object PagesRoute
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)
@Serializable object DisplaySettingsRoute
@Serializable data class ManageItemRoute(val itemId: String)
@Serializable object ServerAdminRoute

/** [queue] is comma-separated item ids to play after [itemId]. */
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queue: String = "")

@Composable
fun NitrateNavHost(container: AppContainer) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        start = when (container.server.restoreSession()) {
            SessionState.VALID -> { container.settingsSync.pull(); container.refreshAccount(); HomeRoute }
            SessionState.UNREACHABLE -> OfflineRoute
            SessionState.NONE, SessionState.EXPIRED -> ConnectRoute
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
    ProvideDisplayPrefs(container, userId) {
    NitrateTheme {
    NavHost(
        navController = nav,
        startDestination = startRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
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
                    container.settingsSync.pull()
                    scope.launch { container.refreshAccount() }
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
                onSettings = { nav.navigate(SettingsRoute) },
                onOffline = { nav.navigate(OfflineRoute) { popUpTo(0) { inclusive = true } } },
                pinnedPages = rememberCustomPages(container).filter { it.pinned }.map { p -> p.title to { nav.navigate(CustomPageRoute(p.id)) } },
                extraActions = listOf("Your pages" to { nav.navigate(PagesRoute) }, "Display" to { nav.navigate(DisplaySettingsRoute) }, "Plugins" to { nav.navigate(PluginsRoute) }) +
                    (if (account?.isAdmin == true) listOf("Server" to { nav.navigate(ServerAdminRoute) }) else emptyList()) +
                    enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.pluginId)) } },
                onSignOut = {
                    nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } }
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
            )
        }
        composable<SettingsRoute> {
            PlaybackSettingsScreen(
                store = container.playbackSettings,
                capabilities = container.capabilities,
                onRunTest = { nav.navigate(PlaybackTestRoute) },
                onChannelTest = { nav.navigate(AudioChannelTestRoute) },
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
            CustomPageScreen(container, route.pageId, route.editNow, onOpen = nav::openItem, onPlay = { item -> gate.play(item) }, onDeleted = { nav.popBackStack() })
        }
        composable<DisplaySettingsRoute> { DisplaySettingsScreen(container) }
        composable<ManageItemRoute> { entry -> ManageItemScreen(container.server, entry.toRoute<ManageItemRoute>().itemId) }
        composable<ServerAdminRoute> { ServerAdminScreen(container.server) }
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
            LibraryScreen(
                repository = container.server,
                displaySettings = container.displaySettings,
                libraryId = entry.toRoute<LibraryRoute>().libraryId,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
            )
        }
        composable<PlaylistRoute> { entry ->
            PlaylistScreen(
                repository = container.server,
                settings = container.displaySettings,
                playlistId = entry.toRoute<PlaylistRoute>().playlistId,
                onPlayQueue = { items -> nav.playQueue(items.map { it.id }) },
                onOpen = nav::openItem,
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
    PlaybackGateDialog(gate)
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
