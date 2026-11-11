package dev.nitrate.tv.ui

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
import dev.nitrate.tv.data.AppContainer
import dev.nitrate.tv.data.SessionState
import dev.nitrate.tv.ui.home.OfflineScreen
import dev.nitrate.tv.ui.connect.ConnectScreen
import dev.nitrate.tv.ui.details.DetailsScreen
import androidx.navigation.NavController
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.settings.AudioChannelTestScreen
import dev.nitrate.tv.ui.settings.PluginsScreen
import dev.nitrate.tv.plugins.core.PlaybackGateDialog
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

/** [queue] is comma-separated item ids to play after [itemId]. */
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queue: String = "")

@Composable
fun NitrateNavHost(container: AppContainer) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        start = when (container.repository.restoreSession()) {
            SessionState.VALID -> { container.settingsSync.pull(); HomeRoute }
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
    // Every "play this item" goes through plugin guards first; queues (playlists) don't.
    val gate = rememberPlaybackGate(container.plugins) { item, fromStart -> nav.navigate(PlayerRoute(item.id.toString(), fromStart)) }
    val enabledPlugins by container.plugins.enabled.collectAsState(emptyList())
    NavHost(
        navController = nav,
        startDestination = startRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<ConnectRoute> {
            ConnectScreen(
                repository = container.repository,
                onConnected = { url, name -> nav.navigate(LoginRoute(url, name)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                repository = container.repository,
                serverUrl = route.serverUrl,
                serverName = route.serverName,
                onLoggedIn = {
                    container.settingsSync.pull()
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                repository = container.repository,
                displaySettings = container.displaySettings,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
                onSettings = { nav.navigate(SettingsRoute) },
                onOffline = { nav.navigate(OfflineRoute) { popUpTo(0) { inclusive = true } } },
                extraActions = listOf("Plugins" to { nav.navigate(PluginsRoute) }) +
                    enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.id)) } },
                onSignOut = {
                    nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            DetailsScreen(
                repository = container.repository,
                itemId = route.itemId,
                seasonId = route.seasonId,
                onPlay = { item, fromStart -> gate.play(item, fromStart) },
                onOpen = nav::openItem,
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
                repository = container.repository,
                screens = enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.id)) } },
                onPlugins = { nav.navigate(PluginsRoute) },
                onOnline = {
                    container.settingsSync.pull()
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
                onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<PluginsRoute> { PluginsScreen(container.plugins) }
        composable<PluginScreenRoute> { entry ->
            val id = entry.toRoute<PluginScreenRoute>().pluginId
            container.plugins.all.filterIsInstance<ScreenPlugin>().firstOrNull { it.id == id }?.Screen(container.plugins.context)
        }
        composable<AudioChannelTestRoute> { AudioChannelTestScreen(container.repository) }
        composable<PlaybackTestRoute> {
            PlaybackTestScreen(store = container.playbackSettings, capabilities = container.capabilities)
        }
        composable<LibraryRoute> { entry ->
            LibraryScreen(
                repository = container.repository,
                displaySettings = container.displaySettings,
                libraryId = entry.toRoute<LibraryRoute>().libraryId,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
            )
        }
        composable<PlaylistRoute> { entry ->
            PlaylistScreen(
                repository = container.repository,
                settings = container.displaySettings,
                playlistId = entry.toRoute<PlaylistRoute>().playlistId,
                onPlayQueue = { items -> nav.playQueue(items.map { it.id.toString() }) },
                onOpen = nav::openItem,
            )
        }
        composable<PlayerRoute> { entry ->
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                repository = container.repository,
                itemId = route.itemId,
                fromStart = route.fromStart,
                onExit = { nav.popBackStack() },
                panels = enabledPlugins.filterIsInstance<PlayerPanel>(),
                pluginContext = container.plugins.context,
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

private val LIBRARY_KINDS = setOf(
    BaseItemKind.COLLECTION_FOLDER,
    BaseItemKind.USER_VIEW,
    BaseItemKind.PLAYLISTS_FOLDER,
    BaseItemKind.MANUAL_PLAYLISTS_FOLDER,
)

/** Where selecting an item goes. Seasons open their series with that season selected. */
fun NavController.openItem(item: BaseItemDto) {
    val seriesId = item.seriesId
    when {
        item.type == BaseItemKind.SEASON && seriesId != null ->
            navigate(DetailsRoute(seriesId.toString(), seasonId = item.id.toString()))
        item.type in LIBRARY_KINDS ->
            navigate(LibraryRoute(item.id.toString()))
        item.type == BaseItemKind.PLAYLIST -> navigate(PlaylistRoute(item.id.toString()))
        else -> navigate(DetailsRoute(item.id.toString()))
    }
}

/** Plays the first id, queueing the rest. Queue items always start from the beginning. */
fun NavController.playQueue(ids: List<String>) {
    val first = ids.firstOrNull() ?: return
    navigate(PlayerRoute(first, fromStart = true, queue = ids.drop(1).joinToString(",")))
}
