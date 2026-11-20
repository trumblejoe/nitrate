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
                    val display = (overrides[row.key] ?: row.defaultDisplay).normalized()
                    ListItem(
                        selected = false,
                        onClick = { onPick(row) },
                        headlineContent = { Text(row.title) },
                        supportingContent = { Text(display.styleLabel) },
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
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(row.title) {
            LazyColumn {
                rowLookItems(row, display, onChange, first, fullArt)
                onEditPage?.let { edit ->
                    item { ListItem(selected = false, onClick = edit, headlineContent = { Text("Edit page") }, supportingContent = { Text("Order, hide, rename and add rows") }) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * A row's look as list items: style, size, its style's own choices, effects and Reset. The row's
 * long-press dialog and the Customize panel (pages/CustomizePanel) both show these.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.rowLookItems(
    row: MediaRow,
    display: RowDisplay,
    onChange: (RowDisplay) -> Unit,
    first: FocusRequester? = null,
    fullArt: Boolean = false,
) {
    fun showcase(update: ShowcaseOptions.() -> ShowcaseOptions) = onChange(display.copy(showcase = display.showcase.update()))
    val firstMod = first?.let { Modifier.focusRequester(it) } ?: Modifier

                if (fullArt) {
                    item { ToggleItem("Show logo", display.showcase.showLogo, firstMod) { showcase { copy(showLogo = it) } } }
                    item { ToggleItem("Show details", display.showcase.showDetails) { showcase { copy(showDetails = it) } } }
                    item { ToggleItem("Show ratings", display.showcase.showRatings) { showcase { copy(showRatings = it) } } }
                    item { ToggleItem("Show overview", display.showcase.showOverview) { showcase { copy(showOverview = it) } } }
                    item { captionChoice(display, onChange) }
                } else {
                item {
                    ChoiceItem("Style", display.style.label, firstMod) {
                        onChange(display.copy(style = display.style.next()))
                    }
                }
                item {
                    ChoiceItem("Size", display.size.label) { onChange(display.copy(size = display.size.next())) }
                }
                item { captionChoice(display, onChange) }
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
                if (display.style == RowStyle.WIDE) {
                    item {
                        ChoiceItem("Cards", if (display.cards) "Shown" else "Hidden") { onChange(display.copy(cards = !display.cards)) }
                    }
                }
                if (display.cardsHidden) {
                    // One title at a time over the page's background: ◀▶ step through the row.
                    item { ToggleItem("Wrap round", display.showcase.infiniteScroll) { showcase { copy(infiniteScroll = it) } } }
                    item { ToggleItem("Rotate artwork", display.showcase.rotateArtwork) { showcase { copy(rotateArtwork = it) } } }
                } else {
                    item { ToggleItem("Show titles", display.showTitles) { onChange(display.copy(showTitles = it)) } }
                }
                }
                // Fun on the cards: what one does when opened, and now and then while browsing.
                item { ChoiceItem("Click effect", display.clickEffect.label) { onChange(display.copy(clickEffect = display.clickEffect.cycle())) } }
                item { ChoiceItem("Browsing effect", display.cardSpark.label) { onChange(display.copy(cardSpark = display.cardSpark.cycle())) } }
                if (display.cardSpark != dev.nitrate.core.effects.CardSpark.NONE) {
                    item { ChoiceItem("How often", display.sparkRate.label) { onChange(display.copy(sparkRate = display.sparkRate.cycle())) } }
                }
                if (display != row.defaultDisplay.normalized()) {
                    item {
                        ListItem(selected = false, onClick = { onChange(row.defaultDisplay.normalized()) }, headlineContent = { Text("Reset to default") })
                    }
                }
}

/**
 * The row's caption (the title in focus, wherever a caption is drawn: rows one at a time, cards
 * hidden, full art): the page's, or a size of its own. Page's → Normal → Compact → Minimal.
 */
@Composable
private fun captionChoice(display: RowDisplay, onChange: (RowDisplay) -> Unit) {
    val page = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.tvCaptionSize
    val order = listOf<dev.nitrate.core.pages.CaptionSize?>(null) + dev.nitrate.core.pages.CaptionSize.entries
    ChoiceItem("Caption", display.captionSize?.label ?: "Page's (${page.label})") {
        onChange(display.copy(captionSize = order[(order.indexOf(display.captionSize) + 1) % order.size]))
    }
}

@Composable
internal fun ChoiceItem(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        trailingContent = { Text("$value  ›", color = MaterialTheme.colorScheme.primary) },
        modifier = modifier,
    )
}

@Composable
internal fun ToggleItem(label: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    ListItem(
        modifier = modifier,
        selected = false,
        onClick = { onToggle(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

private fun RowStyle.next() = step(1)
private fun RowSize.next() = RowSize.entries[(ordinal + 1) % RowSize.entries.size]

internal inline fun <reified E : Enum<E>> E.cycle(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }
