package dev.nitrate.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.tv.ui.components.LocalDisplayPrefs

/**
 * The menu's "More": every stop kept out of the menu itself, as large tiles (big words and icons,
 * easy to read from the sofa). OK opens one; a group's tile opens onto its stops here too.
 * Settings → Display → Menu stops chooses what's here.
 */
@Composable
fun MoreScreen() {
    val catalog = LocalMenuCatalog.current
    val prefs = LocalDisplayPrefs.current
    val entries = remember(catalog, prefs) { catalog?.more(prefs).orEmpty() }
    var group by remember { mutableStateOf<NavEntry?>(null) }
    val shown = group?.children ?: entries
    val first = remember { FocusRequester() }
    MainMenu(MenuStops.MORE, overlay = false) {
        Column(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(group?.label ?: "More", style = MaterialTheme.typography.headlineMedium)
            if (shown.isEmpty()) Text(
                "Nothing here. Settings → Display → Menu stops puts stops here, out of the way.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                GridCells.Adaptive(220.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(shown, key = { _, e -> e.id }) { i, entry ->
                    Surface(
                        onClick = { if (entry.children.isNotEmpty()) group = entry else entry.onClick() },
                        onLongClick = entry.onLongClick,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                        modifier = Modifier.height(140.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            Text(entry.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
    }
    // Back inside a group's tiles goes back to More's own.
    androidx.activity.compose.BackHandler(enabled = group != null) { group = null }
    LaunchedEffect(group, shown.size) { kotlinx.coroutines.delay(50); runCatching { first.requestFocus() } }
}
