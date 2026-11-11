package dev.nitrate.mobile.ui

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
    LIBRARIES("Libraries", Icons.AutoMirrored.Filled.List),
    WRITING("Writing", Icons.Default.Edit),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** Bottom bar on phones; a side rail when there's room (tablets, landscape). */
@Composable
fun MainScreen(actions: AppActions, onSignedOut: () -> Unit) {
    val enabled by actions.container.plugins.enabledIds.collectAsState(emptySet())
    val tabs = Tab.entries.filter { it != Tab.WRITING || WritingPlugin.id in enabled }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    if (tab !in tabs) tab = Tab.HOME

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when (tab) {
            Tab.HOME -> HomeTab(actions, modifier)
            Tab.LIBRARIES -> LibrariesTab(actions, modifier)
            Tab.WRITING -> WritingScreen(actions, onBack = null, modifier = modifier)
            Tab.SETTINGS -> SettingsScreen(actions, onSignedOut, modifier)
        }
    }

    BoxWithConstraints {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars).height(8.dp))
                    tabs.forEach { t ->
                        NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                        }
                    }
                },
            ) { padding -> content(Modifier.padding(bottom = padding.calculateBottomPadding())) }
        }
    }
}

@Composable
private fun HomeTab(actions: AppActions, modifier: Modifier) {
    val container = actions.container
    val vm = viewModel(key = "home") { RowsViewModel(container.server::homeRows, container.displaySettings, container.server.userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    // Refresh "Continue Watching" when coming back from the player or details.
    var first by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) vm.refresh()
        first = false
        onPauseOrDispose { }
    }

    when (val s = state) {
        RowsState.Loading -> Loading(modifier)
        is RowsState.Error -> ErrorMessage(s.message, vm::refresh, modifier)
        is RowsState.Loaded -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; vm.refresh(); refreshing = false }, modifier = modifier) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
                items(s.rows, key = { it.key }) { row ->
                    CardRow(row, (overrides[row.key] ?: row.defaultDisplay).style, container.server, actions::open)
                }
            }
        }
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
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            }
            items(result.getOrThrow(), key = { it.id }) { view ->
                MediaCard(view, actions.server.cardImage(view, CardShape.WIDE), CardShape.WIDE, onClick = { actions.open(view) }, title = view.name)
            }
        }
    }
}
