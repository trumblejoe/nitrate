package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** One line in the settings list: what it is, a hint, and where it goes. */
data class SettingsLink(val label: String, val detail: String? = null, val onClick: () -> Unit)

/** Everything that used to sit in Home's footer: settings, tools, account. */
@Composable
fun SettingsHubScreen(links: List<SettingsLink>) {
    val first = remember { FocusRequester() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp)) }
        itemsIndexed(links, key = { _, l -> l.label }) { i, link ->
            ListItem(
                selected = false,
                onClick = link.onClick,
                headlineContent = { Text(link.label) },
                supportingContent = link.detail?.let { { Text(it) } },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.widthIn(max = 720.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
