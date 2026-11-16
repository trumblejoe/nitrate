package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.writing.WritingPlugin

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    LIBRARIES("Library", Icons.AutoMirrored.Filled.List),
    DOWNLOADS("Downloads", NitrateIcons.Download),
    WRITING("Writing", Icons.Default.Edit),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** Bottom space tab screens leave for the floating navigation pill, which sits above the system navigation bar. */
val NavBarClearance: Dp
    @Composable get() = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalPlayingElsewhere.current

/** Extra room above the navigation pill while the "Playing on…" bar shows there. */
val LocalPlayingElsewhere = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** [extra] below the last item of a full-screen list, plus room for the system navigation bar. */
@Composable
fun bottomInset(extra: Dp): Dp = extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** [ScreenPadding] all round a full-screen list, clear of the system navigation bar at the bottom. */
@Composable
fun screenListPadding() = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding, bottom = bottomInset(ScreenPadding))

/** Every tab full-bleed, with a floating glass navigation pill over it. */
@Composable
fun MainScreen(actions: AppActions, startOnLibrary: Boolean = false, onSignedOut: () -> Unit) {
    // Null until read: until then the tab you were on stays (Writing would otherwise flick to Home).
    val enabled by actions.container.plugins.enabledIds.collectAsState<Set<String>, Set<String>?>(null)
    var tab by rememberSaveable { mutableStateOf(if (startOnLibrary) Tab.LIBRARIES else Tab.HOME) }
    val easy = LocalDisplayPrefs.current.easyMode
    val hasDownloads by actions.container.downloads.downloads.collectAsState(emptyList())
    val tabs = Tab.entries.filter { it != Tab.WRITING || enabled?.contains(WritingPlugin.id) ?: (tab == Tab.WRITING) }
        // Easy mode: Home, Search, Library and Settings (Downloads only once there are some).
        .filter { !easy || it in setOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARIES, Tab.SETTINGS) || (it == Tab.DOWNLOADS && hasDownloads.isNotEmpty()) }
    if (tab !in tabs) tab = Tab.HOME
    // Back from another tab returns Home rather than leaving the app.
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    var playingElsewhere by remember { mutableStateOf(false) }
    val base = NavBarClearance
    androidx.compose.runtime.CompositionLocalProvider(LocalPlayingElsewhere provides if (playingElsewhere) 72.dp else 0.dp) {
    Box(Modifier.fillMaxSize()) {
        val padded = Modifier.fillMaxSize().padding(bottom = NavBarClearance)
        when (tab) {
            Tab.HOME -> HomeTab(actions, Modifier.fillMaxSize(), onSearch = { tab = Tab.SEARCH })
            Tab.SEARCH -> SearchScreen(actions, Modifier.fillMaxSize())
            Tab.LIBRARIES -> LibrariesTab(actions, padded)
            Tab.DOWNLOADS -> DownloadsScreen(actions, padded)
            Tab.WRITING -> WritingScreen(actions, onBack = null, modifier = padded)
            Tab.SETTINGS -> SettingsScreen(actions, onSignedOut, padded)
        }
        // Something playing on your TV (or another device): what, with a tap to its remote.
        PlayingElsewhereBar(actions, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = base - 8.dp), onShown = { playingElsewhere = it })
        GlassNavBar(
            tabs = tabs.map { NavTab(it.label, it.icon) },
            selected = tabs.indexOf(tab),
            onSelect = { tab = tabs[it] },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

@Composable
private fun ChannelsEntry(actions: AppActions) {
    val companion by actions.container.companion.collectAsState()
    val enabled by actions.container.plugins.enabledIds.collectAsState(emptySet())
    if (companion?.features?.contains(dev.nitrate.core.channels.ChannelsPlugin.FEATURE) != true || dev.nitrate.core.channels.ChannelsPlugin.id !in enabled) return
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pressable({ actions.nav.navigate(ChannelsRoute) }).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Channels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Your library as TV channels, watched on demand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = accentColor())
    }
}

@Composable
private fun LibrariesTab(actions: AppActions, modifier: Modifier) {
    // Last seen libraries at once (if any), then the server's current list.
    val cache = actions.container.libraryCache
    val views by produceState<Result<List<MediaItem>>?>(cache.cached(actions.server).takeIf { it.isNotEmpty() }?.let { Result.success(it) }) {
        val fresh = runCatching { cache.refresh(actions.server) }
        if (fresh.isSuccess || value == null) value = fresh
    }
    val result = views
    val easy = LocalDisplayPrefs.current.easyMode
    when {
        result == null -> Loading(modifier)
        result.isFailure -> ErrorMessage(result.exceptionOrNull()?.message ?: "Couldn't load libraries", modifier = modifier)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                }
            }
            // The household's channels, when the server has them and the plugin is on.
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ChannelsEntry(actions) }
            items(result.getOrThrow(), key = { it.id }) { view ->
                MediaCard(view, actions.server.cardImage(view, CardShape.WIDE), CardShape.WIDE, onClick = { actions.open(view) }, title = view.name, sharedKey = "lib-${view.id}")
            }
            // Your own pages come after the libraries, out of the way.
            if (!easy) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(top = 12.dp).layout { measurable, constraints ->
                    // Run edge to edge like rows elsewhere (the grid adds its own side padding).
                    val extra = (ScreenPadding * 2).roundToPx()
                    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.maxWidth + extra))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
                }) { CustomPagesSection(actions) }
            }
        }
    }
}
