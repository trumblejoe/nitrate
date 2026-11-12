package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nitrate.core.OpenSource

/** What Nitrate is built with and under which licences; tap one for its notes and full licence text. */
@Composable
fun LicensesScreen() {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Text("Open-source licences", style = MaterialTheme.typography.headlineSmall)
                Text("Nitrate is built with these. Tap one for details and the licence's full text.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(OpenSource.components, key = { it.name }) { c ->
            Column(Modifier.fillMaxWidth().pressable({ open = if (open == c.name) null else c.name }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${c.license}  ·  ${c.url}", style = MaterialTheme.typography.bodySmall, color = accentColor())
                if (open == c.name) {
                    c.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                    val text = remember(c.licenseText) { runCatching { context.assets.open("licenses/${c.licenseText}").bufferedReader().readText() }.getOrDefault("") }
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 48.dp)) }
    }
}
