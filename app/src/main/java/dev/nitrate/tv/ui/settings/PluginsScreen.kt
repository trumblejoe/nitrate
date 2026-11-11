package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.tv.plugins.core.PluginRegistry
import kotlinx.coroutines.launch

/** Switch plugins on and off; an enabled plugin's own settings appear beneath it. */
@Composable
fun PluginsScreen(registry: PluginRegistry) {
    val enabled by registry.enabledIds.collectAsState(emptySet())
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("Plugins", style = MaterialTheme.typography.headlineMedium)
                Text("Optional features. They're built into Nitrate and stay on this TV.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(registry.all, key = { it.id }) { plugin ->
            val on = plugin.id in enabled
            Column {
                ListItem(
                    selected = false,
                    onClick = { scope.launch { registry.setEnabled(plugin.id, !on) } },
                    headlineContent = { Text(plugin.name) },
                    supportingContent = { Text(plugin.description) },
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                    modifier = if (plugin == registry.all.first()) Modifier.focusRequester(first) else Modifier,
                )
                val settings = plugin.settings
                if (on && settings != null) {
                    Column(Modifier.padding(start = 32.dp, bottom = 16.dp)) { settings(registry.context) }
                }
            }
        }
    }
}
