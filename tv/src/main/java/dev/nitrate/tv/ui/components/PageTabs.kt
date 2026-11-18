package dev.nitrate.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.nitrate.core.pages.TabStyle
import kotlin.math.PI
import kotlin.math.sin

/** One page tab: [key] for focus and choosing, [label] shown. */
data class PageTab(val key: String, val label: String)

private val Amber = Color(0xFFE8A33C)

/**
 * A page's tabs (Home's pinned pages, a library's tabs) in the user's [TabStyle]. Every style keeps
 * the open tab ([selected]) and the focused one apart. [focusFor] gives each tab's requester, so a
 * page can put focus back on the tab just chosen. [preview] draws the tabs without taking focus,
 * with [previewFocused] shown as focused (the style picker).
 */
@Composable
fun PageTabs(
    tabs: List<PageTab>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusFor: ((String) -> FocusRequester)? = null,
    style: TabStyle = LocalDisplayPrefs.current.tvTabStyle,
    preview: Boolean = false,
    previewFocused: String? = null,
) {
    val container = when (style) {
        TabStyle.PILL -> Modifier.background(Color(0x40000000), RoundedCornerShape(50)).padding(4.dp)
        TabStyle.GLASS -> Modifier
            .background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF), Color(0x1FFFFFFF))), RoundedCornerShape(20.dp))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF))), RoundedCornerShape(20.dp))
            .padding(5.dp)
        TabStyle.FILM -> Modifier.drawBehind { filmStrip() }.padding(horizontal = 14.dp, vertical = 16.dp)
        else -> Modifier
    }
    val gap = when (style) {
        TabStyle.UNDERLINE -> 28.dp
        TabStyle.LARGE -> 30.dp
        TabStyle.PILL, TabStyle.GLASS -> 2.dp
        TabStyle.FILM -> 8.dp
        TabStyle.NEON, TabStyle.BRUSH -> 22.dp
        TabStyle.MARQUEE -> 18.dp
        else -> 10.dp
    }
    Row(modifier.then(container), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        for (t in tabs) {
            val interaction = remember { MutableInteractionSource() }
            val focusedNow by interaction.collectIsFocusedAsState()
            val focused = if (preview) t.key == previewFocused else focusedNow
            val open = t.key == selected
            val m = if (preview) Modifier else Modifier
                .then(focusFor?.let { Modifier.focusRequester(it(t.key)) } ?: Modifier)
                .tabKeys { onSelect(t.key) }
                .focusable(interactionSource = interaction)
                .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = open }
            Box(m) { TabFace(style, t.label, open, focused) }
        }
    }
}

@Composable
private fun TabFace(style: TabStyle, label: String, open: Boolean, focused: Boolean) {
    val grow by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "tab")
    when (style) {
        TabStyle.CHIPS -> {
            val bg by animateColorAsState(if (focused) Color(0xFFEDE7DD) else Color(0x22FFFFFF), label = "chip")
            Box(
                Modifier.scale(1f + 0.06f * grow).background(bg, RoundedCornerShape(10.dp))
                    .then(if (open) Modifier.border(1.5.dp, if (focused) Amber else Color(0xFF9AA3C7), RoundedCornerShape(10.dp)) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(label, color = if (focused) Color(0xFF222222) else Color(0xFFE6E0F0), fontSize = 15.sp) }
        }
        TabStyle.UNDERLINE -> Column(Modifier.scale(1f + 0.08f * grow).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = when { focused -> Color.White; open -> Color(0xFFF2EEE6); else -> Color(0x99FFFFFF) }, fontSize = 17.sp,
                fontWeight = if (open || focused) FontWeight.SemiBold else FontWeight.Normal)
            Box(Modifier.padding(top = 5.dp).height(3.dp).width(if (open) 36.dp else 22.dp * grow)
                .background(if (open) Amber else Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
        }
        TabStyle.PILL -> {
            val bg by animateColorAsState(when { focused -> Color.White; open -> Color(0x33FFFFFF); else -> Color.Transparent }, label = "pill")
            Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(label, color = when { focused -> Color(0xFF1A1A1A); open -> Color.White; else -> Color(0xB3FFFFFF) }, fontSize = 15.sp,
                    fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        TabStyle.LARGE -> Row(Modifier.scale(1f + 0.1f * grow), verticalAlignment = Alignment.CenterVertically) {
            if (open) Box(Modifier.padding(end = 8.dp).size(7.dp).background(Amber, CircleShape))
            Text(label, color = when { focused -> Color.White; open -> Color(0xFFF2EEE6); else -> Color(0x66FFFFFF) }, fontSize = 22.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium)
        }
        TabStyle.GLASS -> {
            // Frosted panes: the open one a lighter pane, the focused one catching the light (a bright
            // edge along the top and a soft glow).
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .drawBehind {
                        if (grow > 0f) drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f * grow), Color.Transparent), center = center, radius = size.width * 0.7f),
                            topLeft = Offset(-12f, -12f), size = Size(size.width + 24f, size.height + 24f), cornerRadius = CornerRadius(28f))
                    }
                    .background(
                        when {
                            focused -> Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x40FFFFFF)))
                            open -> Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF)))
                            else -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                        }, shape,
                    )
                    .then(if (focused || open) Modifier.border(1.dp, Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x26FFFFFF))), shape) else Modifier)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(label, color = if (focused) Color(0xFF14161C) else Color.White.copy(alpha = if (open) 1f else 0.75f), fontSize = 15.sp,
                    fontWeight = if (open || focused) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        TabStyle.NEON -> {
            // Glass tubes: unlit ones a dull pink, the open one lit magenta, the focused one flickering
            // on in cyan with a glowing outline.
            val flicker = remember { Animatable(1f) }
            LaunchedEffect(focused) {
                if (focused) {
                    flicker.snapTo(0.2f)
                    flicker.animateTo(1f, keyframes { durationMillis = 420; 0.9f at 60; 0.25f at 110; 1f at 170; 0.5f at 240; 1f at 320 })
                }
            }
            val tube = when { focused -> Color(0xFF7DF9FF); open -> Color(0xFFFF4FD8); else -> Color(0xFF8A4A7A) }
            val lit = focused || open
            val a = if (focused) flicker.value else 1f
            Box(
                Modifier
                    .drawBehind {
                        if (focused) for (k in 3 downTo 0) drawRoundRect(tube.copy(alpha = (0.08f + 0.1f * (3 - k)) * a), cornerRadius = CornerRadius(22f),
                            topLeft = Offset(-k * 3f, -k * 3f), size = Size(size.width + k * 6f, size.height + k * 6f), style = Stroke(2f + k * 3f))
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(label, color = tube.copy(alpha = if (lit) a else 0.55f),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                        shadow = if (lit) Shadow(tube.copy(alpha = 0.9f * a), Offset.Zero, 18f) else null))
            }
        }
        TabStyle.MARQUEE -> {
            // A lit sign: the focused one framed by bulbs chasing round it; the open one on a red plate.
            val chase by rememberInfiniteTransition(label = "bulbs").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "chase")
            Box(
                Modifier
                    .drawBehind {
                        if (open) drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7A1414), Color(0xFF3E0808))), cornerRadius = CornerRadius(10f))
                        if (focused) bulbs(chase)
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(label.uppercase(), color = when { open -> Color(0xFFFFE6A8); focused -> Color.White; else -> Color(0xCCF5E9D0) },
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        shadow = if (open || focused) Shadow(Color(0xFFFFC46B), Offset.Zero, 10f) else null))
            }
        }
        TabStyle.FILM -> {
            // Frames on the strip: dark film, the open one lit warm from behind, the focused one brightest.
            Box(
                Modifier.scale(1f + 0.05f * grow)
                    .background(
                        when {
                            focused -> Brush.verticalGradient(listOf(Color(0xFFFFF4DC), Color(0xFFE9D3A6)))
                            open -> Brush.radialGradient(listOf(Color(0xFFFFC86B), Color(0xFF9A5A1A)))
                            else -> Brush.verticalGradient(listOf(Color(0xFF2A2420), Color(0xFF1C1814)))
                        }, RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(label, color = if (focused || open) Color(0xFF1A120A) else Color(0xCCE8DCC8), fontSize = 15.sp, fontWeight = if (open) FontWeight.Bold else FontWeight.Medium) }
        }
        TabStyle.BRUSH -> Box(
            Modifier
                .drawBehind {
                    if (open) inkStroke(Amber.copy(alpha = 0.85f), label.hashCode(), thick = 1f)
                    if (focused) inkStroke(Color.White.copy(alpha = 0.85f * grow), label.hashCode() + 7, thick = 0.3f, low = true)
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(label, color = when { open -> Color(0xFF1A120A); focused -> Color.White; else -> Color(0xA6FFFFFF) },
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 19.sp, fontWeight = if (open || focused) FontWeight.Bold else FontWeight.Normal))
        }
        TabStyle.TICKET -> {
            val fill = when { focused -> Color(0xFFF6EBD3); open -> Amber; else -> Color.Transparent }
            val edge = if (focused || open) Color.Transparent else Color(0x8CF6EBD3)
            Box(
                Modifier.scale(1f + 0.06f * grow).drawBehind { ticket(fill, edge) }.padding(start = 22.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
            ) {
                Text(label.uppercase(), color = if (focused || open) Color(0xFF2A1A0A) else Color(0xD9F6EBD3),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp))
            }
        }
    }
}

/**
 * OK (or Enter) chooses, on release, like the TV's own buttons; always focusable, in touch mode too
 * (a plain clickable only takes focus outside it).
 */
private fun Modifier.tabKeys(onClick: () -> Unit): Modifier = onKeyEvent { e ->
    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    if (ok && e.type == KeyEventType.KeyUp) onClick()
    ok
}

/** A strip of film behind the tabs: dark base with sprocket holes along both edges. */
private fun DrawScope.filmStrip() {
    drawRoundRect(Color(0xF0100C0A), cornerRadius = CornerRadius(6f))
    val hole = Size(10f, 7f)
    var x = 8f
    while (x < size.width - hole.width) {
        drawRoundRect(Color(0xFF3A322A), Offset(x, 5f), hole, CornerRadius(2f))
        drawRoundRect(Color(0xFF3A322A), Offset(x, size.height - 5f - hole.height), hole, CornerRadius(2f))
        x += 22f
    }
}

/** Bulbs round the tab, a bright one chasing round ([t] 0..1). */
private fun DrawScope.bulbs(t: Float) {
    val inset = -6f
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    val perimeter = 2 * (w + h)
    val n = (perimeter / 22f).toInt().coerceAtLeast(12)
    for (i in 0 until n) {
        var d = perimeter * i / n
        val p = when {
            d < w -> Offset(inset + d, inset)
            d < w + h -> { d -= w; Offset(inset + w, inset + d) }
            d < 2 * w + h -> { d -= w + h; Offset(inset + w - d, inset + h) }
            else -> { d -= 2 * w + h; Offset(inset, inset + h - d) }
        }
        val phase = ((i.toFloat() / n - t) % 1f + 1f) % 1f
        val on = phase < 0.33f
        if (on) drawCircle(Color(0x55FFC46B), 7f, p)
        drawCircle(if (on) Color(0xFFFFE7B0) else Color(0xFF8A6A3A), 3f, p)
    }
}

/** A sweep of ink: a tapered, slightly wavering stroke across the tab ([low]: under the word). */
private fun DrawScope.inkStroke(color: Color, seed: Int, thick: Float, low: Boolean = false) {
    val w = size.width
    val h = size.height
    val r = { k: Int -> (((seed * 31 + k * 977) % 1000 + 1000) % 1000) / 1000f }
    val y0 = if (low) h * 0.92f else h * 0.55f
    val half = h * 0.38f * thick
    val top = Path()
    val bottom = mutableListOf<Offset>()
    val steps = 16
    for (i in 0..steps) {
        val f = i / steps.toFloat()
        val x = -w * 0.06f + f * w * 1.12f
        val taper = sin(f * PI).toFloat().let { 0.25f + 0.75f * it }
        val wave = sin(f * 5f + r(1) * 6f) * h * 0.04f
        val y = y0 + wave + (r(2) - 0.5f) * h * 0.1f * f
        val hw = half * taper * (0.85f + 0.3f * r(i + 3))
        if (i == 0) top.moveTo(x, y - hw) else top.lineTo(x, y - hw)
        bottom += Offset(x, y + hw)
    }
    bottom.asReversed().forEach { top.lineTo(it.x, it.y) }
    top.close()
    drawPath(top, color)
    // Dry-brush streaks at the tail.
    for (k in 0 until 4) {
        val y = y0 + (k - 1.5f) * half * 0.45f
        drawLine(color.copy(alpha = color.alpha * 0.5f), Offset(w * (0.82f + 0.04f * k), y), Offset(w * (1.02f + 0.03f * r(k + 20)), y + h * 0.02f), 1.5f)
    }
}

/** An admit-one ticket: notches at each end and a perforation near the left. */
private fun DrawScope.ticket(fill: Color, edge: Color) {
    val w = size.width
    val h = size.height
    val notch = h * 0.18f
    val p = Path().apply {
        moveTo(0f, 0f); lineTo(w, 0f)
        lineTo(w, h / 2 - notch); arcTo(androidx.compose.ui.geometry.Rect(Offset(w, h / 2), notch), 270f, -180f, false)
        lineTo(w, h); lineTo(0f, h)
        lineTo(0f, h / 2 + notch); arcTo(androidx.compose.ui.geometry.Rect(Offset(0f, h / 2), notch), 90f, -180f, false)
        close()
    }
    if (fill.alpha > 0f) drawPath(p, fill)
    if (edge.alpha > 0f) drawPath(p, edge, style = Stroke(1.5f))
    val perf = if (fill.alpha > 0f) Color(0x592A1A0A) else edge
    drawLine(perf, Offset(12.dp.toPx(), 4f), Offset(12.dp.toPx(), h - 4f), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
}


private val SampleTabs = listOf(PageTab("rec", "Recommended"), PageTab("all", "All"), PageTab("genres", "Genres"), PageTab("unwatched", "Unwatched"))

/**
 * Every tab style, each drawn as it looks ("Recommended" open, "All" focused) on a page-like
 * background; OK picks one. Opens on the style in use, which takes focus once it's built.
 */
@Composable
fun TabStylePicker(current: TabStyle, onPick: (TabStyle) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        TabStyleChoices(current, onPick)
    }
}

@Composable
internal fun TabStyleChoices(current: TabStyle, onPick: (TabStyle) -> Unit) {
    val focus = remember { FocusRequester() }
    var taken by remember { androidx.compose.runtime.mutableStateOf(false) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (current.ordinal - 1).coerceAtLeast(0))
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF120E0B), Color(0xFF3A2414), Color(0xFF6A4220)))),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 58.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Tab style", style = androidx.tv.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 6.dp)) }
        items(TabStyle.entries.size) { i ->
            val s = TabStyle.entries[i]
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Column(
                Modifier
                    .then(if (s == current) Modifier.focusRequester(focus) else Modifier)
                    .tabKeys { onPick(s) }
                    .focusable(interactionSource = interaction)
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .background(if (focused) Color(0x33FFFFFF) else Color(0x1A000000), RoundedCornerShape(14.dp))
                    .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label + if (s == current) "  ✓" else "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("  ·  ${s.detail}", color = Color(0xB3FFFFFF), fontSize = 14.sp)
                }
                PageTabs(SampleTabs, selected = "rec", onSelect = {}, style = s, preview = true, previewFocused = "all", modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            }
            if (s == current) LaunchedEffect(Unit) {
                if (!taken) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() }; taken = true }
            }
        }
    }
}
