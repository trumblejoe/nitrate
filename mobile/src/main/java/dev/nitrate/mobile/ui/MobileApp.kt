package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object OfflineRoute
@Serializable object MainRoute
@Serializable data class LibraryRoute(val libraryId: String)
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null)
@Serializable data class PlaylistRoute(val playlistId: String)

/** [queue] is comma-separated item ids to play after [itemId]. */
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queue: String = "")

/** Shared by every screen: the container, navigation, and play-with-plugin-checks. */
class AppActions(
    val container: AppContainer,
    val nav: NavController,
    val play: (MediaItem, Boolean) -> Unit,
) {
    val server get() = container.server

    /** Where tapping an item goes. Seasons open their series with that season selected. */
    fun open(item: MediaItem) {
        val series = item.seriesId
        when {
            item.kind == ItemKind.SEASON && series != null -> nav.navigate(DetailsRoute(series, seasonId = item.id))
            item.kind == ItemKind.LIBRARY -> nav.navigate(LibraryRoute(item.id))
            item.kind == ItemKind.PLAYLIST -> nav.navigate(PlaylistRoute(item.id))
            else -> nav.navigate(DetailsRoute(item.id))
        }
    }

    /** Plays the first id, queueing the rest; queues start from the beginning and skip plugin checks. */
    fun playQueue(ids: List<String>) {
        val first = ids.firstOrNull() ?: return
        nav.navigate(PlayerRoute(first, fromStart = true, queue = ids.drop(1).joinToString(",")))
    }
}

@Composable
fun MobileApp(container: AppContainer) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        start = when (container.server.restoreSession()) {
            SessionState.VALID -> { container.settingsSync.pull(); MainRoute }
            SessionState.UNREACHABLE -> OfflineRoute
            SessionState.NONE, SessionState.EXPIRED -> ConnectRoute
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val startRoute = start
        if (startRoute == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Surface
        }

        val nav = rememberNavController()
        val scope = rememberCoroutineScope()
        // A plugin's question before playing (e.g. an unwatched earlier episode).
        var pending by remember { mutableStateOf<Pair<GuardDecision.Ask, CompletableDeferred<GuardAction>>?>(null) }

        val actions = remember(nav) {
            AppActions(container, nav) { item, fromStart ->
                scope.launch {
                    val action = container.plugins.checkBeforePlay(item) { ask ->
                        val answer = CompletableDeferred<GuardAction>()
                        pending = ask to answer
                        answer.await().also { pending = null }
                    }
                    when (action) {
                        GuardAction.PlayAnyway -> nav.navigate(PlayerRoute(item.id, fromStart))
                        GuardAction.Cancel -> Unit
                        is GuardAction.PlayInstead -> nav.navigate(PlayerRoute(action.item.id))
                        is GuardAction.MarkWatchedThenPlay -> {
                            action.itemIds.forEach { runCatching { container.server.setPlayed(it, true) } }
                            nav.navigate(PlayerRoute(item.id, fromStart))
                        }
                    }
                }
            }
        }

        NavHost(navController = nav, startDestination = startRoute) {
            composable<ConnectRoute> { ConnectScreen(actions) { url, name -> nav.navigate(LoginRoute(url, name)) } }
            composable<LoginRoute> { entry ->
                val route = entry.toRoute<LoginRoute>()
                LoginScreen(actions, route.serverUrl, route.serverName) {
                    container.settingsSync.pull()
                    nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                }
            }
            composable<OfflineRoute> {
                OfflineScreen(
                    actions,
                    onOnline = { container.settingsSync.pull(); nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } } },
                    onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<MainRoute> {
                MainScreen(actions, onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } })
            }
            composable<LibraryRoute> { entry -> LibraryScreen(actions, entry.toRoute<LibraryRoute>().libraryId) }
            composable<DetailsRoute> { entry ->
                val route = entry.toRoute<DetailsRoute>()
                DetailsScreen(actions, route.itemId, route.seasonId)
            }
            composable<PlaylistRoute> { entry -> PlaylistScreen(actions, entry.toRoute<PlaylistRoute>().playlistId) }
            composable<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                PlayerScreen(
                    actions = actions,
                    itemId = route.itemId,
                    fromStart = route.fromStart,
                    onExit = { nav.popBackStack() },
                    onEnded = {
                        val next = route.queue.split(',').filter(String::isNotBlank)
                        nav.popBackStack()
                        if (next.isNotEmpty()) actions.playQueue(next)
                    },
                )
            }
        }

        pending?.let { (ask, answer) ->
            AlertDialog(
                onDismissRequest = { answer.complete(GuardAction.Cancel) },
                title = { Text(ask.title) },
                text = { Text(ask.message) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        ask.choices.forEach { choice ->
                            TextButton(onClick = { answer.complete(choice.action) }) { Text(choice.label) }
                        }
                    }
                },
            )
        }
    }
}

