package dev.nitrate.mobile.ui

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

/** Bottom space screens leave for the floating navigation pill. */
val NavBarClearance = 96.dp

/** Every tab full-bleed, with a floating glass navigation pill over it. */
@Composable
fun MainScreen(actions: AppActions, onSignedOut: () -> Unit) {
    val enabled by actions.container.plugins.enabledIds.collectAsState(emptySet())
    val tabs = Tab.entries.filter { it != Tab.WRITING || WritingPlugin.id in enabled }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    if (tab !in tabs) tab = Tab.HOME
    // Back from another tab returns Home rather than leaving the app.
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

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
        GlassNavBar(
            tabs = tabs.map { NavTab(it.label, it.icon) },
            selected = tabs.indexOf(tab),
            onSelect = { tab = tabs[it] },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LibrariesTab(actions: AppActions, modifier: Modifier) {
    val views by produceState<Result<List<MediaItem>>?>(null) { value = runCatching { actions.server.views() } }
    val result = views
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
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
                    CustomPagesSection(actions)
                    Text("Libraries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                }
            }
            items(result.getOrThrow(), key = { it.id }) { view ->
                MediaCard(view, actions.server.cardImage(view, CardShape.WIDE), CardShape.WIDE, onClick = { actions.open(view) }, title = view.name, sharedKey = "lib-${view.id}")
            }
        }
    }
}
