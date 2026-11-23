package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Where a settings section opens: set by the two-pane Settings (a tablet, a wide window) to show it
 * beside the list; null, as on a phone, navigates to its own page (`SettingsPageRoute`).
 */
val LocalSettingsPane = staticCompositionLocalOf<((SettingsPage) -> Unit)?> { null }

/** True inside the two-pane Settings' right side: the page has no back arrow (the list is beside it). */
val LocalInSettingsPane = staticCompositionLocalOf { false }

/**
 * The Settings tab: on a phone the list of sections, each opening its page; in a wide window
 * ([wideDetails]) the list on the left and the chosen section's page beside it (Picture & sound first).
 * Links within a page (Advanced, What's shown) open in the same right side.
 */
@Composable
fun SettingsTab(actions: AppActions, onSignedOut: () -> Unit, modifier: Modifier = Modifier) {
    if (!wideDetails()) {
        SettingsScreen(actions, onSignedOut, modifier)
        return
    }
    var page by rememberSaveable { mutableStateOf(SettingsPage.SOUND.name) }
    val chosen = SettingsPage.entries.firstOrNull { it.name == page } ?: SettingsPage.SOUND
    CompositionLocalProvider(LocalSettingsPane provides { p -> page = p.name }) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight()) { SettingsScreen(actions, onSignedOut) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalInSettingsPane provides true) {
                    androidx.compose.runtime.key(chosen) { SettingsPageScreen(actions, chosen) }
                }
            }
        }
    }
}
