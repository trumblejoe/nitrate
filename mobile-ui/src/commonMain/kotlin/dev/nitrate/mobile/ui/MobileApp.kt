@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.nitrate.mobile.ui

import kotlinx.coroutines.flow.first
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.runtime.collectAsState
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

/** "Who's watching?": the accounts saved on this device. */
@Serializable object ProfilesRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object OfflineRoute
@Serializable object MainRoute
@Serializable object StatsRoute
@Serializable object DiaryRoute
@Serializable data class LibraryRoute(val libraryId: String)
/** [artKey] names the card it was opened from, so its artwork can morph into the page. */
/** [group]: opened from that watch group's card on Home's Watching together row (plays from the page count as from the card). */
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null, val artKey: String? = null, val group: String? = null)
@Serializable data class PlaylistRoute(val playlistId: String, val group: String? = null)
@Serializable data class PersonRoute(val personId: String, val name: String, val studio: String? = null, val year: Int? = null)

/** Admin: an item's metadata, identity, images and refresh. */
@Serializable data class ManageItemRoute(val itemId: String, val images: Boolean = false, val run: Boolean = false)

/** Admin: the whole server (tasks, activity, devices, users). */
@Serializable object ServerAdminRoute
@Serializable object LibraryHealthRoute
@Serializable object HouseholdDevicesRoute
@Serializable object GuideRoute
@Serializable object PlaybackOverviewRoute
@Serializable object ImagesOverviewRoute
@Serializable object QueryAnalyzerRoute
@Serializable object ChannelsRoute
@Serializable object WatchPartnersRoute
@Serializable data class ChannelEditorRoute(val channelId: String = "")
@Serializable object ServerAddressesRoute
@Serializable object LicensesRoute
@Serializable data class SettingsPageRoute(val page: String)

/** A page of the user's own rows; [editNow] opens the editor straight away (just created). */
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)

/** [queue] is comma-separated item ids to play after [itemId]; [local] plays the downloaded file. */
/** [clipEndMs] >= 0: a recap clip from [startMs] to there, not reported to the server. */
@Serializable data class PlayerRoute(
    val itemId: String, val fromStart: Boolean = false, val queue: String = "", val local: Boolean = false, val startMs: Long = -1, val clipEndMs: Long = -1,
    /** A test play from the playback overview; [queue] then holds the titles still to test. */
    val test: Boolean = false,
)

/** "Last time": the recap in [AppContainer.pendingRecap], before playing on. */
@Serializable object RecapRoute

/** Reading a comic from a Books library. */
@Serializable data class ComicRoute(val itemId: String)

/** A remote control for another device's playback. */
@Serializable data class RemoteRoute(val sessionId: String)

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
    /** Plays from a moment (a line found by "search what people say"), with plugin checks. */
    private val playAt: ((MediaItem, Long) -> Unit)? = null,
    val play: (MediaItem, Boolean) -> Unit,
    /** Home's "Watching together" row: its cards' own taps and long presses (null elsewhere). */
    val cards: TogetherCards? = null,
    /**
     * Before telling another device to play an item ("Play on…", the remote): the plugins' guards,
     * asked here on the phone; what to send, or null to send nothing.
     */
    private val beforeSending: suspend (MediaItem) -> MediaItem? = { it },
    /** What an item plays and opens as: on a details page opened from a watch group's card, its title's parts as that card's ([fromGroup]). */
    private val tag: (MediaItem) -> MediaItem = { it },
) {
    /** The same, with a row's own handling of its cards. */
    fun withCards(cards: TogetherCards) = AppActions(container, nav, scope, playAt, play, cards, beforeSending, tag)

    /**
     * For a details page opened from watch group [groupId]'s card (null: opened any other way, this
     * as it is): the parts of that group's titles play, are sent ("Play on…") and open from that
     * card ([MediaItem.watchGroupId], so the guards and the player get its `PlayOrigin`), exactly
     * as from the card; anything else on the page (a trailer, something similar) as from anywhere.
     */
    fun fromGroup(groupId: String?): AppActions {
        if (groupId == null) return this
        val tagged: (MediaItem) -> MediaItem = { item -> if (container.detailsOrigin(groupId, item) != null) item.copy(watchGroupId = groupId) else item }
        return AppActions(
            container, nav, scope, playAt?.let { p -> { item, ms -> p(tagged(item), ms) } }, { item, fromStart -> play(tagged(item), fromStart) },
            cards, { item -> beforeSending(tagged(item)) }, tagged,
        )
    }

    /**
     * Runs the guards (`PluginRegistry.checkBeforePlay`) on this phone before [item] is sent to
     * another device to play, showing any question here: what to send, or null when nothing should go.
     */
    suspend fun checkBeforeSending(item: MediaItem): MediaItem? = beforeSending(item)

    /** The watch group whose card [item]'s details page counts plays for (see [fromGroup]); null elsewhere. */
    fun groupOf(item: MediaItem): String? = tag(item).watchGroupId

    /** Plays [item] from [startMs] (falls back to resuming). */
    fun playFrom(item: MediaItem, startMs: Long) = playAt?.invoke(item, startMs) ?: play(item, false)

    val server get() = container.server

    /** Where tapping an item goes. Seasons open their series with that season selected. */
    fun open(item: MediaItem, artKey: String? = null) {
        if (cards?.select(item) == true) return
        val series = item.seriesId
        // A Watching together card's details page remembers its group (plays from there count as from the card).
        val group = tag(item).watchGroupId
        when {
            item.kind == ItemKind.SEASON && series != null -> nav.navigate(DetailsRoute(series, seasonId = item.id, group = group))
            item.kind == ItemKind.LIBRARY -> nav.navigate(LibraryRoute(item.id))
            item.kind == ItemKind.PLAYLIST -> nav.navigate(PlaylistRoute(item.id, group = group))
            else -> nav.navigate(DetailsRoute(item.id, artKey = artKey, group = group))
        }
    }

    /** Plays a movie or episode (resuming), a show's next episode, or opens anything else. */
    fun playOrOpen(item: MediaItem) {
        if (cards?.select(item) == true) return
        when {
            item.isPlayable -> play(item, false)
            item.kind == ItemKind.SERIES -> scope.launch {
                val next = runCatching { server.nextUpFor(item.id) }.getOrNull()
                    ?: runCatching { server.allEpisodes(item.id).firstOrNull { !it.user.played } }.getOrNull()
                // A watch group's card plays from that group's card.
                if (next != null) play(next.copy(watchGroupId = item.watchGroupId), false) else open(item)
            }
            else -> open(item)
        }
    }

    /**
     * Plays [items] from [start] ([shuffle]: in a random order). Songs go to the listening player,
     * which carries on while browsing (the mini player shows it); anything else to the player.
     */
    fun playItems(items: List<MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val listening = container.listening
        if (listening != null && items.isNotEmpty() && items.all { it.kind == ItemKind.AUDIO }) return listening.play(items, start, shuffle)
        val rest = items.drop(start)
        playQueue((if (shuffle) rest.shuffled() else rest).map { it.id })
    }

    /** Plays the first id, queueing the rest; queues start from the beginning and skip plugin checks. */
    fun playQueue(ids: List<String>) {
        val first = ids.firstOrNull() ?: return
        nav.navigate(PlayerRoute(first, fromStart = true, queue = ids.drop(1).joinToString(",")))
    }
}

/** [MobileApp]'s openItem with this in front plays the item instead of opening it (home-screen widgets). */
const val PLAY_ITEM_PREFIX = "play:"

/** [MobileApp]'s openItem with this in front opens that group's watch party (a tapped reminder). */
const val PARTY_PREFIX = "party:"

/** A tapped schedule change request: `schedule:<group id>` opens that group's Yes/No. */
const val SCHEDULE_PREFIX = "schedule:"

/** A tapped invite: opens Watch groups, where invites wait with Accept and Decline. */
const val OPEN_INVITES = "invites:"

@Composable
fun MobileApp(container: AppContainer, openItem: kotlinx.coroutines.flow.MutableStateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)) {
    var start by remember { mutableStateOf<Any?>(null) }
    // Outlives individual screens, for syncing watch state from downloads.
    val appScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val state = container.server.restoreSession()
        start = when {
            container.shouldPickProfile(state) -> ProfilesRoute
            state == SessionState.VALID -> { container.settingsSync.pull(); container.backups.onSignedIn(); container.remote.start(); appScope.launch { container.refreshAccount(); container.downloads.syncWatchState(); container.downloads.refreshSmart() }; MainRoute }
            state == SessionState.UNREACHABLE -> OfflineRoute
            else -> ConnectRoute
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

        // After signing out: the other saved accounts, or sign in afresh.
        fun signedOut() {
            scope.launch {
                val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                nav.navigate(next) { popUpTo(0) { inclusive = true } }
            }
        }

        // A downloaded item plays from the device, online or not.
        fun playerRoute(itemId: String, fromStart: Boolean) =
            PlayerRoute(itemId, fromStart, local = container.downloads.find(itemId)?.state == DownloadState.COMPLETED)

        val cast = dev.nitrate.mobile.platform.rememberCast()

        // Resuming after a break: the "Last time" recap first, when one is due.
        suspend fun go(target: dev.nitrate.core.model.MediaItem, fromStart: Boolean) {
            // A book opens in the reader, from wherever Play was pressed.
            if (target.kind == dev.nitrate.core.model.ItemKind.BOOK) return nav.navigate(ComicRoute(target.id))
            // Casting: Play sends it to the Chromecast (Jellyfin's receiver plays it from the server).
            if (cast != null && cast.state.value.connected != null && target.kind != dev.nitrate.core.model.ItemKind.TV_CHANNEL) {
                if (castTo(container, cast, null, castPlayFor(container, target, fromStart))) return
            }
            // A song plays in the listening player, its album queued around it.
            val listening = container.listening
            // A book carries on where it was left (from the start when asked, or once it was finished).
            if (target.kind == dev.nitrate.core.model.ItemKind.AUDIOBOOK && listening != null) {
                // A downloaded book read offline has a newer place on the device than the server's.
                val download = container.downloads.find(target.id)?.takeIf { it.state == DownloadState.COMPLETED }
                val from = when {
                    fromStart -> 0L
                    download != null -> container.downloads.resumePositionMs(download)
                    target.user.played -> 0L
                    else -> target.user.positionMs
                }
                listening.play(listOf(target), startMs = from)
                return nav.navigate(NowPlayingRoute)
            }
            if (target.kind == dev.nitrate.core.model.ItemKind.AUDIO && listening != null) {
                val album = target.albumId?.let { runCatching { container.server.albumTracks(it) }.getOrNull() }.orEmpty()
                val at = album.indexOfFirst { it.id == target.id }
                return if (at >= 0) listening.play(album, at) else listening.play(listOf(target))
            }
            val recap = if (fromStart) null else container.recapBefore(target)
            if (recap != null) nav.navigate(RecapRoute) else nav.navigate(playerRoute(target.id, fromStart))
        }

        // Telling another device to play (Play on…, the remote): the same guards, asked on the phone first.
        suspend fun checkedForSending(item: dev.nitrate.core.model.MediaItem): dev.nitrate.core.model.MediaItem? =
            container.plugins.checkBeforeSending(item, { ask ->
                val answer = CompletableDeferred<GuardAction>()
                pending = ask to answer
                answer.await().also { pending = null }
            }, dev.nitrate.core.partners.PlayOrigin.of(item).takeIf { it is dev.nitrate.core.partners.PlayOrigin.GroupCard }) { container.server.setPlayed(it, true) }

        // A plugin's checks, then play: from the start, resuming, or from [startMs]. [origin]: where it's
        // played from (null: a watch group's card by its item's group, else elsewhere), handed on to the player.
        fun guarded(picked: dev.nitrate.core.model.MediaItem, fromStart: Boolean, startMs: Long?, origin: dev.nitrate.core.partners.PlayOrigin? = null) {
                val from = origin ?: dev.nitrate.core.partners.PlayOrigin.of(picked).takeIf { it is dev.nitrate.core.partners.PlayOrigin.GroupCard }
                scope.launch {
                    // A show or season: its next episode (else its first) is what plays.
                    val item = if (dev.nitrate.core.playback.PlayTargets.needsEpisode(picked)) {
                        dev.nitrate.core.playback.PlayTargets.episodeFor(container.server, picked) ?: return@launch
                    } else picked
                    // Choosing for a TV from its remote: it goes there (the guards asked here, as for Play on…).
                    ChooseForTv.target.value?.takeIf { item.kind != dev.nitrate.core.model.ItemKind.BOOK }?.let { t ->
                        // A Group Watch Session to start: the TV hosts it (its own guards and the group's schedule checked there).
                        if (t.groupId != null) {
                            val asked = runCatching { container.remote.askToStartSession(t.sessionId, t.groupId, item.id) }.isSuccess
                            if (asked) {
                                ChooseForTv.done()
                                nav.navigate(RemoteRoute(t.sessionId)) { popUpTo<RemoteRoute> { inclusive = true } }
                                return@launch
                            }
                        }
                        val sent = t.groupId == null && sendToChosenTv(container, { nav.navigate(RemoteRoute(t.sessionId)) { popUpTo<RemoteRoute> { inclusive = true } } }, t, item, fromStart, startMs, ::checkedForSending)
                        if (!sent) {
                            val ok = CompletableDeferred<GuardAction>()
                            pending = dev.nitrate.core.plugins.GuardDecision.Ask(
                                "Couldn't play on ${t.deviceName}", "Check that Nitrate is open on it, then try again.",
                                listOf(dev.nitrate.core.plugins.GuardChoice("OK", GuardAction.Cancel)),
                            ) to ok
                            ok.await().also { pending = null }
                        }
                        return@launch
                    }
                    val action = container.plugins.checkBeforePlay(item, from) { ask ->
                        val answer = CompletableDeferred<GuardAction>()
                        pending = ask to answer
                        answer.await().also { pending = null }
                    }
                    // From a group's card: the group's own resume point (or the start), never the solo one.
                    val groupStart = if (fromStart || startMs != null) null else container.groupStartMs(from, item)
                    @Suppress("NAME_SHADOWING") val fromStart = fromStart || groupStart == 0L
                    @Suppress("NAME_SHADOWING") val startMs = startMs ?: groupStart?.takeIf { it > 0 }
                    when (action) {
                        GuardAction.PlayAnyway -> if (startMs != null) nav.navigate(playerRoute(item.id, false).copy(startMs = startMs)) else go(item, fromStart)
                        GuardAction.Cancel -> container.playTurnedDown(item)
                        is GuardAction.PlayInstead -> nav.navigate(playerRoute(action.item.id, false))
                        is GuardAction.MarkWatchedThenPlay -> {
                            action.itemIds.forEach { runCatching { container.server.setPlayed(it, true) } }
                            nav.navigate(playerRoute(item.id, fromStart))
                        }
                        is GuardAction.RunThenPlay -> {
                            val failed = runCatching { action.run() }.exceptionOrNull()
                            if (failed != null && !action.playIfFailed) {
                                // Said, not just closed: why it couldn't start.
                                container.playTurnedDown(item)
                                val ok = CompletableDeferred<GuardAction>()
                                pending = dev.nitrate.core.plugins.GuardDecision.Ask(
                                    "Couldn't start", failed.message ?: "Something went wrong. Try again in a moment.",
                                    listOf(dev.nitrate.core.plugins.GuardChoice("OK", GuardAction.Cancel)),
                                ) to ok
                                ok.await().also { pending = null }
                                return@launch
                            }
                            if (startMs != null) nav.navigate(playerRoute(item.id, false).copy(startMs = startMs)) else go(item, fromStart)
                        }
                    }
                }
        }

        val actions = remember(nav) {
            AppActions(
                container, nav, appScope, playAt = { item, ms -> guarded(item, false, ms) }, play = { item, fromStart -> guarded(item, fromStart, null) },
                beforeSending = { checkedForSending(it) },
            )
        }

        // The user is known only after sign-in, so their display choices (accent, true black) apply here.
        val backEntry by nav.currentBackStackEntryAsState()
        val userId = remember(backEntry) { container.server.userId }
        CompositionLocalProvider(LocalAppContainer provides container) { ProvideDisplayPrefs(container, userId) {
        NitrateTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
        // The Watching together row's taps (an invite's answer, the start card, a shared title's choices)
        // on every page it can be on (Home, custom pages, library pages), not just Home.
        TogetherHost(actions, togetherRowOn(actions)) {
        NavHost(
            navController = nav,
            startDestination = startRoute,
            enterTransition = { fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(250)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = { fadeOut(tween(250)) + scaleOut(tween(300), targetScale = 0.97f) },
        ) {
            screen<ProfilesRoute> {
                ProfilesScreen(
                    actions,
                    onOpened = {
                        appScope.launch { container.downloads.syncWatchState(); container.downloads.refreshSmart() }
                        nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                    },
                    onSignInAgain = { a -> nav.navigate(LoginRoute(a.serverUrl, a.serverName)) },
                    onAdd = { nav.navigate(ConnectRoute) },
                )
            }
            screen<ConnectRoute> { ConnectScreen(actions) { url, name -> nav.navigate(LoginRoute(url, name)) } }
            screen<LoginRoute> { entry ->
                val route = entry.toRoute<LoginRoute>()
                LoginScreen(actions, route.serverUrl, route.serverName) {
                    appScope.launch { container.onSignedIn() }
                    nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                }
            }
            screen<OfflineRoute> {
                OfflineScreen(
                    actions,
                    onOnline = {
                        container.settingsSync.pull()
                        container.backups.onSignedIn()
                        container.remote.start()
                        appScope.launch { container.refreshAccount(); container.downloads.syncWatchState(); container.downloads.refreshSmart() }
                        nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                    },
                    onSignedOut = { signedOut() },
                    onServers = { nav.navigate(ServerAddressesRoute) },
                )
            }
            screen<MainRoute> {
                MainScreen(actions, onSignedOut = { signedOut() })
            }
            screen<LibraryRoute> { entry -> LibraryScreen(actions, entry.toRoute<LibraryRoute>().libraryId) }
            screen<DetailsRoute> { entry ->
                val route = entry.toRoute<DetailsRoute>()
                // Theme music while on details pages (off by default on phones).
                val themeOn = LocalDisplayPrefs.current.themeMusic ?: false
                androidx.compose.runtime.DisposableEffect(route.itemId, themeOn) {
                    if (themeOn) actions.container.themeMusic.show(route.itemId) else actions.container.themeMusic.stop()
                    onDispose { actions.container.themeMusic.hide(route.itemId) }
                }
                DetailsScreen(actions.fromGroup(route.group), route.itemId, route.seasonId, route.artKey)
            }
            screen<PlaylistRoute> { entry -> entry.toRoute<PlaylistRoute>().let { r -> PlaylistScreen(actions.fromGroup(r.group), r.playlistId) } }
            screen<ManageItemRoute> { entry -> entry.toRoute<ManageItemRoute>().let { ManageItemScreen(actions, it.itemId, it.images, if (it.run) actions.container.imageFixRun else emptyList()) } }
            screen<ServerAdminRoute> { ServerAdminScreen(actions) }
            screen<LibraryHealthRoute> { LibraryHealthScreen(actions) }
            screen<HouseholdDevicesRoute> { HouseholdDevicesScreen(actions) }
            screen<GuideRoute> { GuideScreen(admin = actions.container.account.collectAsState().value?.isAdmin == true) }
            screen<PlaybackOverviewRoute> { PlaybackOverviewScreen(actions) }
            screen<ImagesOverviewRoute> { ImagesOverviewScreen(actions) }
            screen<QueryAnalyzerRoute> { QueryAnalyzerScreen(actions) }
            screen<ChannelsRoute> { ChannelsScreen(actions) }
            screen<CatalogsRoute> { CatalogsScreen(actions) }
            screen<CatalogFeedRoute> { entry -> entry.toRoute<CatalogFeedRoute>().let { CatalogFeedScreen(actions, it.catalogId, it.target) } }
            screen<CatalogBookRoute> { entry ->
                val route = entry.toRoute<CatalogBookRoute>()
                CatalogReaderScreen(actions, route.catalogId, catalogJson.decodeFromString(dev.nitrate.core.catalogs.CatalogBook.serializer(), route.book), onExit = { nav.popBackStack() })
            }
            screen<WatchPartnersRoute> { WatchPartnersScreen(actions) }
            screen<RecapRoute> { RecapScreen(actions) }
            screen<ComicRoute> { entry -> ComicReaderScreen(
                    actions, entry.toRoute<ComicRoute>().itemId, onExit = { nav.popBackStack() },
                    onNext = { id -> nav.navigate(ComicRoute(id)) { popUpTo<ComicRoute> { inclusive = true } } },
                ) }
            screen<ChannelEditorRoute> { entry -> ChannelEditorScreen(actions, entry.toRoute<ChannelEditorRoute>().channelId.ifEmpty { null }) }
            screen<ServerAddressesRoute> { ServersScreen(actions) }
            screen<LicensesRoute> { LicensesScreen() }
            screen<SettingsPageRoute> { entry ->
                val page = runCatching { SettingsPage.valueOf(entry.toRoute<SettingsPageRoute>().page) }.getOrDefault(SettingsPage.SOUND)
                ReadableWidth(androidx.compose.ui.unit.Dp(900f)) { SettingsPageScreen(actions, page) }
            }
            screen<RemoteRoute> { entry -> RemoteScreen(actions, entry.toRoute<RemoteRoute>().sessionId) }
            screen<NowPlayingRoute> { NowPlayingScreen(actions, onBack = { nav.popBackStack() }) }
            screen<StatsRoute> { StatsScreen(actions) }
            screen<DiaryRoute> { DiaryScreen(actions) }
            screen<CustomPageRoute> { entry ->
                val route = entry.toRoute<CustomPageRoute>()
                CustomPageScreen(actions, route.pageId, route.editNow)
            }
            screen<PersonRoute> { entry ->
                val route = entry.toRoute<PersonRoute>()
                PersonScreen(actions, route.personId, route.name, route.studio, route.year)
            }
            screen<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    actions.container.themeMusic.stop()
                    // A video takes over from the music.
                    actions.container.listening?.pause()
                }
                // Leaving the player: what was watched with partners counts for them too.
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { actions.container.afterWatching() } }
                PlayerScreen(
                    actions = actions,
                    itemId = route.itemId,
                    fromStart = route.fromStart,
                    local = route.local,
                    startMs = route.startMs.takeIf { it >= 0 },
                    clipEndMs = route.clipEndMs.takeIf { it >= 0 },
                    // Playlists queue their own next item; single episodes get Up Next (not a recap clip).
                    onPlayNext = if (route.queue.isBlank() && route.clipEndMs < 0 && !route.test) { next -> nav.popBackStack(); guarded(next, false, null, container.upNextOrigin(next)) } else null,
                    onExit = { nav.popBackStack() },
                    onEnded = {
                        val next = route.queue.split(',').filter(String::isNotBlank)
                        nav.popBackStack()
                        // A sample test goes on to the next kind of file; a queue plays on.
                        if (route.test && next.isNotEmpty()) nav.navigate(PlayerRoute(next.first(), queue = next.drop(1).joinToString(","), test = true))
                        else if (next.isNotEmpty()) actions.playQueue(next)
                    },
                    testPlay = if (route.test) { result -> appScope.launch { actions.container.saveTestPlay(result) } } else null,
                )
            }
        }
        }

        }
        }
        }
        }
        }
        }

        // What other apps ask of this one: play something here, show a message, change volume.
        var message by remember { mutableStateOf<Pair<String, String>?>(null) }
        val platformActions = dev.nitrate.mobile.platform.rememberPlatformActions()
        // In a watch group, or invited into one: ask once whether Nitrate may notify (Android 13+ notifies
        // nothing until allowed, and the switches for these are on from the start).
        val partnersNow by container.partners.collectAsState()
        val notifyAllowed = dev.nitrate.mobile.platform.rememberNotificationsAllowed()
        val askNotify = dev.nitrate.mobile.platform.rememberNotificationPermission { }
        LaunchedEffect(partnersNow != null && (partnersNow!!.groups.isNotEmpty() || partnersNow!!.incoming.isNotEmpty())) {
            val p = partnersNow ?: return@LaunchedEffect
            if (p.groups.isEmpty() && p.incoming.isEmpty()) return@LaunchedEffect
            val keys = container.platform.libraryKeyValues
            if (notifyAllowed() || keys.get(ASKED_NOTIFY_KEY) != null) return@LaunchedEffect
            if (!container.newEpisodes.partnerRequestsEnabled.first()) return@LaunchedEffect
            keys.put(ASKED_NOTIFY_KEY, "1")
            askNotify { }
        }

        // A tapped notification: open its item once signed in (not over the profile picker or sign-in).
        val pendingOpen by openItem.collectAsState()
        val currentEntry by nav.currentBackStackEntryAsState()
        LaunchedEffect(pendingOpen, currentEntry) {
            val id = pendingOpen ?: return@LaunchedEffect
            val signedIn = nav.currentBackStack.value.any { it.destination.hasRoute(MainRoute::class) }
            val busy = currentEntry?.destination?.let { d ->
                d.hasRoute(PlayerRoute::class) || d.hasRoute(ProfilesRoute::class) || d.hasRoute(ConnectRoute::class) || d.hasRoute(LoginRoute::class)
            } == true
            if (signedIn && !busy) {
                openItem.value = null
                // A home-screen widget's title: into the player, resuming, through the usual checks.
                if (id.startsWith(PARTY_PREFIX)) {
                    container.openParty(id.removePrefix(PARTY_PREFIX))
                    return@LaunchedEffect
                }
                if (id == OPEN_INVITES) {
                    nav.navigate(WatchPartnersRoute)
                    return@LaunchedEffect
                }
                if (id.startsWith(SCHEDULE_PREFIX)) {
                    container.openScheduleAsk(id.removePrefix(SCHEDULE_PREFIX))
                    return@LaunchedEffect
                }
                if (id.startsWith(PLAY_ITEM_PREFIX)) {
                    runCatching { container.server.getItem(id.removePrefix(PLAY_ITEM_PREFIX)) }.getOrNull()?.let { guarded(it, false, null) }
                    return@LaunchedEffect
                }
                // A new season opens its show with that season selected.
                val item = runCatching { container.server.getItem(id) }.getOrNull()
                val series = item?.seriesId
                if (item?.kind == ItemKind.SEASON && series != null) nav.navigate(DetailsRoute(series, seasonId = item.id)) else nav.navigate(DetailsRoute(id))
            }
        }
        // A Group Watch Session joined: its part plays (through the guards) from where this player starts, waiting for the countdown.
        LaunchedEffect(Unit) {
            container.groupSessions.openRequests.collect { open ->
                val item = runCatching { container.server.getItem(open.itemId) }.getOrNull() ?: return@collect
                // Joined from inside a player: it closes (its place kept) for the session's part.
                if (nav.currentBackStackEntry?.destination?.hasRoute(PlayerRoute::class) == true) nav.popBackStack()
                val groupId = container.groupSessions.current.value?.groupId
                guarded(item.copy(watchGroupId = groupId ?: item.watchGroupId), false, open.startMs, groupId?.let { dev.nitrate.core.partners.PlayOrigin.GroupCard(it) })
            }
        }
        // SyncPlay, kept for compatibility (an older app's group this device joined): it started something while no player was open.
        LaunchedEffect(Unit) {
            container.syncPlay.openRequests.collect { q ->
                q.currentItemId?.let { nav.navigate(PlayerRoute(it, startMs = q.startMs)) { popUpTo<PlayerRoute> { inclusive = true } } }
            }
        }
        LaunchedEffect(Unit) {
            container.remote.events.collect { e ->
                when (e) {
                    is dev.nitrate.core.events.ServerEvent.Play -> e.itemIds.firstOrNull()?.let { first ->
                        // In place of a player already open, so Back doesn't return to the old one.
                        nav.navigate(PlayerRoute(first, fromStart = e.startMs == null, queue = e.itemIds.drop(1).joinToString(","), startMs = e.startMs ?: -1)) {
                            popUpTo<PlayerRoute> { inclusive = true }
                        }
                    }
                    is dev.nitrate.core.events.ServerEvent.General -> when (e.name) {
                        "DisplayMessage" -> message = (e.arguments["Header"] ?: "Message") to e.arguments["Text"].orEmpty()
                        "VolumeUp", "VolumeDown", "ToggleMute", "Mute", "Unmute" -> platformActions.adjustVolume(e.name)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
        message?.let { (header, text) ->
            AlertDialog(onDismissRequest = { message = null }, title = { Text(header) }, text = { Text(text) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
            LaunchedEffect(header, text) { kotlinx.coroutines.delay(10_000); message = null }
        }

        // Choosing for a TV from its remote: whom for, Show on TV on a title's page, Done (not over the remote or a player).
        run {
            val dest = currentEntry?.destination
            if (dest != null && !dest.hasRoute(RemoteRoute::class) && !dest.hasRoute(PlayerRoute::class)) {
                val itemId = if (dest.hasRoute(DetailsRoute::class)) currentEntry?.toRoute<DetailsRoute>()?.itemId else null
                Box(Modifier.fillMaxSize()) { ChoosingBanner(actions, itemId, Modifier.align(Alignment.TopCenter)) }
            }
        }

        // The first-run setup; while it shows, the prompts it replaces wait.
        val setupActive by container.setup.active.collectAsState()
        if (!setupActive) {
            UpdatePrompt(container.updater)
            BackupPrompt(container.backups)
            SpeakersSetupPrompt(container)
            WatchInvitePrompt(actions)
            // Watch parties: what the Companion sends as it happens; waits while the player is open.
            WatchPartyPrompt(actions, inPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == true)
            // Group Watch Sessions someone in a group started ("Tommy started a Group Watch Session · Join"); waits while the player is open.
            SessionJoinPrompt(actions, inPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == true)
            // Asks to watch together: someone's ask ("Start it" hosts it here), a later one's reminder, how one's own went.
            WatchAskPrompt(actions, inPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == true)
            // Schedule changes someone in a group asked for (Yes/No), and how they went; waits while the player is open.
            ScheduleChangePrompt(actions, inPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == true)
            // "Also count it for Suzy, where it's next too?": once the player has closed.
            CountingPrompt(actions, inPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == true)
        }
        FirstRunSetupHost(container)
        LaunchedEffect(Unit) { container.updater.checkIfDue() }

        pending?.let { (ask, answer) -> GuardDialog(ask) { answer.complete(it) } }
    }
}

/** A playback guard's question (or a refusal, whose only choice is OK): its choices in order; dismissing is Cancel. */
@Composable
fun GuardDialog(ask: GuardDecision.Ask, onAnswer: (GuardAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(GuardAction.Cancel) },
        title = { Text(ask.title) },
        text = { Text(ask.message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                ask.choices.forEach { choice ->
                    TextButton(onClick = { onAnswer(choice.action) }) { Text(choice.label) }
                }
            }
        },
    )
}



/** Asked once whether Nitrate may notify about watch groups (kept on the device). */
private const val ASKED_NOTIFY_KEY = "notify.asked.watchgroups"
