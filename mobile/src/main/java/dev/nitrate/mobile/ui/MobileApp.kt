@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.nitrate.mobile.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import dev.nitrate.android.AppContainer
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object OfflineRoute
@Serializable object MainRoute
@Serializable data class LibraryRoute(val libraryId: String)
/** [artKey] names the card it was opened from, so its artwork can morph into the page. */
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null, val artKey: String? = null)
@Serializable data class PlaylistRoute(val playlistId: String)
@Serializable data class PersonRoute(val personId: String, val name: String)

/** Admin: an item's metadata, identity, images and refresh. */
@Serializable data class ManageItemRoute(val itemId: String)

/** Admin: the whole server (tasks, activity, devices, users). */
@Serializable object ServerAdminRoute

/** A page of the user's own rows; [editNow] opens the editor straight away (just created). */
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)

/** [queue] is comma-separated item ids to play after [itemId]; [local] plays the downloaded file. */
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queue: String = "", val local: Boolean = false)

/** A destination that provides its animation scope, for shared artwork transitions. */
inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry -> CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) } }
}

/** Shared by every screen: the container, navigation, and play-with-plugin-checks. */
class AppActions(
    val container: AppContainer,
    val nav: NavController,
    /** Outlives screens: for work started from a tap, like finding a show's next episode. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val play: (MediaItem, Boolean) -> Unit,
) {
    val server get() = container.server

    /** Where tapping an item goes. Seasons open their series with that season selected. */
    fun open(item: MediaItem, artKey: String? = null) {
        val series = item.seriesId
        when {
            item.kind == ItemKind.SEASON && series != null -> nav.navigate(DetailsRoute(series, seasonId = item.id))
            item.kind == ItemKind.LIBRARY -> nav.navigate(LibraryRoute(item.id))
            item.kind == ItemKind.PLAYLIST -> nav.navigate(PlaylistRoute(item.id))
            else -> nav.navigate(DetailsRoute(item.id, artKey = artKey))
        }
    }

    /** Plays a movie or episode (resuming), a show's next episode, or opens anything else. */
    fun playOrOpen(item: MediaItem) {
        when {
            item.isPlayable -> play(item, false)
            item.kind == ItemKind.SERIES -> scope.launch {
                val next = runCatching { server.nextUpFor(item.id) }.getOrNull()
                    ?: runCatching { server.allEpisodes(item.id).firstOrNull { !it.user.played } }.getOrNull()
                if (next != null) play(next, false) else open(item)
            }
            else -> open(item)
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
    // Outlives individual screens, for syncing watch state from downloads.
    val appScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        start = when (container.server.restoreSession()) {
            SessionState.VALID -> { container.settingsSync.pull(); appScope.launch { container.refreshAccount(); container.downloads.syncWatchState(); container.downloads.refreshSmart() }; MainRoute }
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

        // A downloaded item plays from the device, online or not.
        fun playerRoute(itemId: String, fromStart: Boolean) =
            PlayerRoute(itemId, fromStart, local = container.downloads.find(itemId)?.state == DownloadState.COMPLETED)

        val actions = remember(nav) {
            AppActions(container, nav, appScope) { item, fromStart ->
                scope.launch {
                    val action = container.plugins.checkBeforePlay(item) { ask ->
                        val answer = CompletableDeferred<GuardAction>()
                        pending = ask to answer
                        answer.await().also { pending = null }
                    }
                    when (action) {
                        GuardAction.PlayAnyway -> nav.navigate(playerRoute(item.id, fromStart))
                        GuardAction.Cancel -> Unit
                        is GuardAction.PlayInstead -> nav.navigate(playerRoute(action.item.id, false))
                        is GuardAction.MarkWatchedThenPlay -> {
                            action.itemIds.forEach { runCatching { container.server.setPlayed(it, true) } }
                            nav.navigate(playerRoute(item.id, fromStart))
                        }
                    }
                }
            }
        }

        // The user is known only after sign-in, so their display choices (accent, true black) apply here.
        val backEntry by nav.currentBackStackEntryAsState()
        val userId = remember(backEntry) { container.server.userId }
        ProvideDisplayPrefs(container, userId) {
        NitrateTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(
            navController = nav,
            startDestination = startRoute,
            enterTransition = { fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(250)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = { fadeOut(tween(250)) + scaleOut(tween(300), targetScale = 0.97f) },
        ) {
            screen<ConnectRoute> { ConnectScreen(actions) { url, name -> nav.navigate(LoginRoute(url, name)) } }
            screen<LoginRoute> { entry ->
                val route = entry.toRoute<LoginRoute>()
                LoginScreen(actions, route.serverUrl, route.serverName) {
                    container.settingsSync.pull()
                    appScope.launch { container.refreshAccount() }
                    nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                }
            }
            screen<OfflineRoute> {
                OfflineScreen(
                    actions,
                    onOnline = {
                        container.settingsSync.pull()
                        appScope.launch { container.refreshAccount(); container.downloads.syncWatchState(); container.downloads.refreshSmart() }
                        nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                    },
                    onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
            screen<MainRoute> {
                MainScreen(actions, onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } })
            }
            screen<LibraryRoute> { entry -> LibraryScreen(actions, entry.toRoute<LibraryRoute>().libraryId) }
            screen<DetailsRoute> { entry ->
                val route = entry.toRoute<DetailsRoute>()
                DetailsScreen(actions, route.itemId, route.seasonId, route.artKey)
            }
            screen<PlaylistRoute> { entry -> PlaylistScreen(actions, entry.toRoute<PlaylistRoute>().playlistId) }
            screen<ManageItemRoute> { entry -> ManageItemScreen(actions, entry.toRoute<ManageItemRoute>().itemId) }
            screen<ServerAdminRoute> { ServerAdminScreen(actions) }
            screen<CustomPageRoute> { entry ->
                val route = entry.toRoute<CustomPageRoute>()
                CustomPageScreen(actions, route.pageId, route.editNow)
            }
            screen<PersonRoute> { entry ->
                val route = entry.toRoute<PersonRoute>()
                PersonScreen(actions, route.personId, route.name)
            }
            screen<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                PlayerScreen(
                    actions = actions,
                    itemId = route.itemId,
                    fromStart = route.fromStart,
                    local = route.local,
                    // Playlists queue their own next item; single episodes get Up Next.
                    onPlayNext = if (route.queue.isBlank()) { next -> nav.popBackStack(); actions.play(next, false) } else null,
                    onExit = { nav.popBackStack() },
                    onEnded = {
                        val next = route.queue.split(',').filter(String::isNotBlank)
                        nav.popBackStack()
                        if (next.isNotEmpty()) actions.playQueue(next)
                    },
                )
            }
        }

        }
        }
        }
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

