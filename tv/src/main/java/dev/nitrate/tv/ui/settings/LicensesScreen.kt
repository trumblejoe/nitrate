package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.OpenSource

/**
 * What Nitrate is built with and under which licences: the list on the left, the chosen one's notes
 * and full licence text on the right (move right into it and down to read).
 */
@Composable
fun LicensesScreen() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(OpenSource.components.first()) }
    val text = remember(chosen.licenseText) {
        runCatching { context.assets.open("licenses/${chosen.licenseText}").bufferedReader().readText() }.getOrDefault("")
            .split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Open-source licences", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp)) }
            items(OpenSource.components, key = { it.name }) { c ->
                ListItem(
                    selected = c == chosen,
                    onClick = { chosen = c },
                    headlineContent = { Text(c.name, maxLines = 2) },
                    supportingContent = { Text(c.license) },
                    modifier = if (c == OpenSource.components.first()) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        // Each paragraph is a stop, so the D-pad scrolls through the licence.
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.focusable(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chosen.name, style = MaterialTheme.typography.titleLarge)
                    Text("${chosen.license}  ·  ${chosen.url}", color = MaterialTheme.colorScheme.primary)
                    chosen.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            items(text) { paragraph ->
                Text(paragraph, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.focusable())
            }
        }
    }
}
