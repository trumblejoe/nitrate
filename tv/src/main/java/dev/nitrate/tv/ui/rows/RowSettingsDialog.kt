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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

/**
 * Ignores OK/Enter until it is pressed afresh. A long press opens these dialogs while the button
 * is still held, and its repeats and release would otherwise click the first option.
 */
@Composable
internal fun Modifier.ignoreHeldSelect(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            !select || armed -> false
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> { armed = true; false }
            else -> true
        }
    }
}

@Composable
private fun DialogPanel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .ignoreHeldSelect()
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
    /** Adds "Edit page" (order, hide, rename, own rows). */
    onEditPage: (() -> Unit)? = null,
    /** On the full-screen Home every row is shown the same way: only the detail toggles apply. */
    fullArt: Boolean = false,
) {
    val first = remember { FocusRequester() }
    fun showcase(update: ShowcaseOptions.() -> ShowcaseOptions) = onChange(display.copy(showcase = display.showcase.update()))

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(row.title) {
            LazyColumn {
                if (fullArt) {
                    item { ToggleItem("Show logo", display.showcase.showLogo, Modifier.focusRequester(first)) { showcase { copy(showLogo = it) } } }
                    item { ToggleItem("Show details", display.showcase.showDetails) { showcase { copy(showDetails = it) } } }
                    item { ToggleItem("Show ratings", display.showcase.showRatings) { showcase { copy(showRatings = it) } } }
                    item { ToggleItem("Show overview", display.showcase.showOverview) { showcase { copy(showOverview = it) } } }
                } else {
                item {
                    ChoiceItem("Style", display.style.label, Modifier.focusRequester(first)) {
                        onChange(display.copy(style = display.style.next()))
                    }
                }
                item {
                    ChoiceItem("Size", display.size.label) { onChange(display.copy(size = display.size.next())) }
                }
                if (display.style == RowStyle.CRESSIDA) {
                    item {
                        ChoiceItem("Ink", display.ink.label) {
                            val inks = dev.nitrate.core.rows.CressidaInk.entries
                            onChange(display.copy(ink = inks[(display.ink.ordinal + 1) % inks.size]))
                        }
                    }
                    item {
                        // Smart, then Accent, then each swatch in turn.
                        val choices = listOf(dev.nitrate.core.rows.InkTint.SMART to 0L, dev.nitrate.core.rows.InkTint.ACCENT to 0L) +
                            dev.nitrate.core.rows.InkPalette.SWATCHES.map { dev.nitrate.core.rows.InkTint.CUSTOM to it.second }
                        val current = choices.indexOfFirst { (t, c) -> t == display.inkTint && (t != dev.nitrate.core.rows.InkTint.CUSTOM || c == display.inkArgb) }
                        val label = when (display.inkTint) {
                            dev.nitrate.core.rows.InkTint.SMART -> "Smart (from each picture)"
                            dev.nitrate.core.rows.InkTint.ACCENT -> "Accent"
                            dev.nitrate.core.rows.InkTint.CUSTOM -> dev.nitrate.core.rows.InkPalette.SWATCHES.firstOrNull { it.second == display.inkArgb }?.first ?: "Custom"
                        }
                        ChoiceItem("Ink colour", label) {
                            val (t, c) = choices[(current + 1) % choices.size]
                            onChange(display.copy(inkTint = t, inkArgb = if (t == dev.nitrate.core.rows.InkTint.CUSTOM) c else display.inkArgb))
                        }
                    }
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
                }
                onEditPage?.let { edit ->
                    item { ListItem(selected = false, onClick = edit, headlineContent = { Text("Edit page") }, supportingContent = { Text("Order, hide, rename and add rows") }) }
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
private fun ToggleItem(label: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    ListItem(
        modifier = modifier,
        selected = false,
        onClick = { onToggle(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

private fun RowStyle.next() = RowStyle.entries[(ordinal + 1) % RowStyle.entries.size]
private fun RowSize.next() = RowSize.entries[(ordinal + 1) % RowSize.entries.size]
