package dev.nitrate.tv.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowSize
import dev.nitrate.core.rows.RowStyle
import dev.nitrate.core.rows.ShowcaseOptions

@Composable
private fun DialogPanel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(460.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

/** Lists every row on the page; picking one opens its display settings. */
@Composable
fun RowListDialog(
    rows: List<MediaRow>,
    overrides: Map<String, RowDisplay>,
    onPick: (MediaRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel("Customize rows", "Choose a row to change how it looks") {
            LazyColumn {
                items(rows, key = { it.key }) { row ->
                    val display = overrides[row.key] ?: row.defaultDisplay
                    ListItem(
                        selected = false,
                        onClick = { onPick(row) },
                        headlineContent = { Text(row.title) },
                        supportingContent = { Text(display.style.label) },
                        modifier = if (row == rows.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Display settings for one row. Changes apply live so the row updates behind the dialog. */
@Composable
fun RowSettingsDialog(
    row: MediaRow,
    display: RowDisplay,
    onChange: (RowDisplay) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    fun showcase(update: ShowcaseOptions.() -> ShowcaseOptions) = onChange(display.copy(showcase = display.showcase.update()))

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(row.title) {
            LazyColumn {
                item {
                    ChoiceItem("Style", display.style.label, Modifier.focusRequester(first)) {
                        onChange(display.copy(style = display.style.next()))
                    }
                }
                item {
                    ChoiceItem("Size", display.size.label) { onChange(display.copy(size = display.size.next())) }
                }
                if (display.style == RowStyle.SHOWCASE) {
                    item { ToggleItem("Show logo", display.showcase.showLogo) { showcase { copy(showLogo = it) } } }
                    item { ToggleItem("Show details", display.showcase.showDetails) { showcase { copy(showDetails = it) } } }
                    item { ToggleItem("Show ratings", display.showcase.showRatings) { showcase { copy(showRatings = it) } } }
                    item { ToggleItem("Show overview", display.showcase.showOverview) { showcase { copy(showOverview = it) } } }
                    item { ToggleItem("Infinite scroll", display.showcase.infiniteScroll) { showcase { copy(infiniteScroll = it) } } }
                    item { ToggleItem("Rotate artwork", display.showcase.rotateArtwork) { showcase { copy(rotateArtwork = it) } } }
                } else {
                    item { ToggleItem("Show titles", display.showTitles) { onChange(display.copy(showTitles = it)) } }
                }
                if (display != row.defaultDisplay) {
                    item {
                        ListItem(selected = false, onClick = { onChange(row.defaultDisplay) }, headlineContent = { Text("Reset to default") })
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ChoiceItem(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        trailingContent = { Text("$value  ›", color = MaterialTheme.colorScheme.primary) },
        modifier = modifier,
    )
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onToggle(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

private fun RowStyle.next() = RowStyle.entries[(ordinal + 1) % RowStyle.entries.size]
private fun RowSize.next() = RowSize.entries[(ordinal + 1) % RowSize.entries.size]
