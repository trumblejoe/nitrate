package dev.nitrate.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.nitrate.android.AppContainer
import dev.nitrate.core.pages.AccentMode
import dev.nitrate.core.pages.DisplayPrefs
import kotlinx.coroutines.flow.flowOf

/** The signed-in user's display preferences; every screen reads what to show from here. */
val LocalDisplayPrefs = compositionLocalOf { DisplayPrefs() }

/** Provides [LocalDisplayPrefs] for [userId] (re-read when the user changes). */
@Composable
fun ProvideDisplayPrefs(container: AppContainer, userId: String?, content: @Composable () -> Unit) {
    val flow = remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: flowOf(DisplayPrefs()) }
    val prefs by flow.collectAsState(DisplayPrefs())
    // Easy mode: everything a little bigger (touch targets too), text more so.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (prefs.easyMode) androidx.compose.ui.unit.Density(density.density * EASY_SCALE, density.fontScale * EASY_TEXT) else density
    CompositionLocalProvider(LocalDisplayPrefs provides prefs, androidx.compose.ui.platform.LocalDensity provides scaled) { content() }
}

/** The fixed accent the user picked, or null to take it from artwork. */
val DisplayPrefs.fixedAccent: Color? get() = accent.argb?.let { Color(it) }

/** True when [AccentMode] follows artwork. */
val DisplayPrefs.tintFromArtwork: Boolean get() = accent == AccentMode.ARTWORK

/** Easy mode's sizes: everything ×1.1, text ×1.15 on top. */
private const val EASY_SCALE = 1.1f
private const val EASY_TEXT = 1.15f
