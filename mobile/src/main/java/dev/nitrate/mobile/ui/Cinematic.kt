@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---- Artwork tint ----

/** The colour taken from the artwork on screen; screens tint their background and accents with it. */
val LocalArtworkTint = compositionLocalOf<Color?> { null }

/** Fills the screen with a soft wash of [tint] fading into the background, animated as it changes. */
@Composable
fun TintedBackdrop(tint: Color?, modifier: Modifier = Modifier, strength: Float = 0.38f, content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val animated by animateColorAsState(tint ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), tween(900), label = "tint")
    Box(
        modifier.fillMaxSize().background(bg).background(
            Brush.verticalGradient(0f to animated.copy(alpha = strength), 0.55f to animated.copy(alpha = strength * 0.25f), 1f to Color.Transparent),
        ),
    ) {
        CompositionLocalProvider(LocalArtworkTint provides tint) { content() }
    }
}

/** Accent for buttons and highlights on a tinted screen: the artwork colour if there is one. */
@Composable
fun accentColor(): Color {
    val tint = LocalArtworkTint.current ?: MaterialTheme.colorScheme.primary
    return animateColorAsState(tint, tween(700), label = "accent").value
}

/** Black or white, whichever reads on [color]. */
fun contentOn(color: Color): Color = if (color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f > 0.6f) Color(0xFF111111) else Color.White

/**
 * Fades content out towards its bottom edge (from [from] down), so artwork melts into whatever
 * is behind it, tinted or not, with no visible seam.
 */
fun Modifier.fadeBottom(from: Float = 0.55f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(from to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

// ---- Shared element transitions (poster -> details) ----

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Artwork that morphs between screens: a card and the page it opens share [key]. Does nothing
 * outside navigation (previews, screenshot tests).
 */
@Composable
fun Modifier.sharedArtwork(key: String?): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (key == null) return this
    return with(shared) {
        this@sharedArtwork.sharedBounds(rememberSharedContentState(key), anim, boundsTransform = { _, _ -> tween(450) })
    }
}

// ---- Touch feel ----

/** Shrinks slightly while pressed, then springs back. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(source, indication = null, role = role, onClick = onClick)
}

// ---- Floating glass navigation ----

data class NavTab(val label: String, val icon: ImageVector)

/**
 * A floating, translucent pill of tabs. The selected tab grows to show its label on an
 * artwork-coloured highlight.
 */
@Composable
fun GlassNavBar(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = accentColor()
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(32.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val isSelected = i == selected
            val bg by animateColorAsState(if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent, label = "tabBg")
            val fg by animateColorAsState(if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg")
            val pad by animateDpAsState(if (isSelected) 16.dp else 12.dp, label = "tabPad")
            Row(
                Modifier.clip(RoundedCornerShape(26.dp)).background(bg).clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = pad, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(22.dp))
                if (isSelected) {
                    Spacer(Modifier.width(8.dp))
                    Text(tab.label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

// ---- Titles and chips ----

/** A row title with a small artwork-coloured bar. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(accentColor()))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Small translucent label, e.g. "4K" or "TV-14" on the hero. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A round translucent action button with a caption, e.g. Watched or Download on a hero. */
@Composable
fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, content: (@Composable () -> Unit)? = null) {
    val accent = accentColor()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).pressable(onClick)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f))
                .border(1.dp, if (active) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f), CircleShape),
            Alignment.Center,
        ) {
            if (content != null) content() else Icon(icon, label, tint = if (active) accent else Color.White)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}
