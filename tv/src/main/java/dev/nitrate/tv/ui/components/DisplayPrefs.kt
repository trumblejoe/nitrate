package dev.nitrate.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitrate.android.AppContainer
import dev.nitrate.core.pages.DisplayPrefs
import kotlinx.coroutines.flow.flowOf

/** The signed-in user's display preferences (synced with their other devices). */
val LocalDisplayPrefs = compositionLocalOf { DisplayPrefs() }

@Composable
fun ProvideDisplayPrefs(container: AppContainer, userId: String?, content: @Composable () -> Unit) {
    val flow = remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: flowOf(DisplayPrefs()) }
    val prefs by flow.collectAsState(DisplayPrefs())
    CompositionLocalProvider(LocalDisplayPrefs provides prefs) { content() }
}
