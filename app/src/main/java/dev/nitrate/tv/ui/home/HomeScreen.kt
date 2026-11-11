package dev.nitrate.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.data.rows.DisplaySettingsStore
import dev.nitrate.tv.ui.rows.RowsPage
import dev.nitrate.tv.ui.rows.RowsState
import dev.nitrate.tv.ui.rows.RowsViewModel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto

@Composable
fun HomeScreen(
    repository: JellyfinRepository,
    displaySettings: DisplaySettingsStore,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    /** More footer buttons, e.g. plugin pages. */
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val vm = viewModel(key = "home") { RowsViewModel(repository::homeRows, displaySettings, repository.userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signOut: () -> Unit = { scope.launch { repository.logout(); onSignOut() } }

    // Refresh "Continue Watching" when returning from the player or details.
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.refresh()
        firstResume = false
        onPauseOrDispose { }
    }

    when (val s = state) {
        RowsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
        is RowsState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::refresh) { Text("Retry") }
                    OutlinedButton(onClick = signOut) { Text("Sign out") }
                }
            }
        }
        is RowsState.Loaded -> RowsPage(
            rows = s.rows,
            overrides = overrides,
            repository = repository,
            onOpen = onOpen,
            onPlay = onPlay,
            onSaveDisplay = vm::save,
            footer = {
                item(key = "settings") {
                    Row(Modifier.padding(start = 58.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((label, action) in extraActions) OutlinedButton(onClick = action) { Text(label) }
                        OutlinedButton(onClick = onSettings) { Text("Playback settings") }
                        OutlinedButton(onClick = signOut) { Text("Sign out") }
                    }
                }
            },
        )
    }
}
