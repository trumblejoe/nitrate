package dev.nitrate.tv.ui.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import dev.nitrate.core.effects.CardSpark
import dev.nitrate.core.effects.ClickEffect
import dev.nitrate.core.effects.EffectsPrefs
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.tv.ui.rows.RowActions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.unit.dp

/** One row's card effects: which card is mid-click and which ones are sparking. */
class CardFx internal constructor(
    val click: ClickEffect,
    val spark: CardSpark,
    internal val scale: Float,
) {
    internal val clicked = mutableStateOf<String?>(null)
    /** Cards sparking now, each with when its spark began. */
    internal val sparking = mutableStateMapOf<String, Long>()
    /** Holds sparks at this progress instead of playing them (screenshots). */
    internal var holdAt: Float? = null
    /** The cards composed now (on screen or about to be): sparks pick among them. */
    internal val shown = mutableStateListOf<String>()

    /** [actions] with the click effect played before a card opens. */
    @Composable
    fun wrap(actions: RowActions): RowActions {
        if (click == ClickEffect.NONE) return actions
        val scope = rememberCoroutineScope()
        return remember(actions) {
            RowActions(
                onFocus = actions.onFocus,
                onOpen = { item ->
                    if (clicked.value == null) {
                        clicked.value = item.id
                        scope.launch {
                            delay(CLICK_MS.toLong())
                            clicked.value = null
                            actions.onOpen(item)
                        }
                    }
                },
                onPlay = actions.onPlay,
                onEditRow = actions.onEditRow,
            )
        }
    }

    internal companion object {
        const val CLICK_MS = 720
        const val SPARK_MS = 1400
        /** How many cards a spark goes off on at once (fewer when fewer are shown). */
        val AT_ONCE = 3..5
        /** Their starts spread over this, so they go off as a ripple rather than in step. */
        const val STAGGER_MS = 450
    }
}

val LocalCardFx = staticCompositionLocalOf<CardFx?> { null }

/**
 * A row's effects, or null when it has none or effects are off (switched off, easy mode). Reduce
 * motion leaves out clicks and sparks alike: both are movement nobody asked for.
 */
@Composable
fun rememberCardFx(display: RowDisplay, prefs: EffectsPrefs, easyMode: Boolean): CardFx? {
    val off = !prefs.on || prefs.reduceMotion || easyMode
    val click = if (off) ClickEffect.NONE else display.clickEffect
    val spark = if (off) CardSpark.NONE else display.cardSpark
    if (click == ClickEffect.NONE && spark == CardSpark.NONE) return null
    val fx = remember(click, spark, prefs.intensity) { CardFx(click, spark, prefs.intensity.scale) }
    if (spark != CardSpark.NONE) {
        LaunchedEffect(fx, display.sparkRate) {
            val every = display.sparkRate.everyMs
            while (true) {
                // Spread out, so rows on the same setting don't go off together.
                delay((every * (0.6 + Random.nextDouble() * 0.8)).toLong())
                val picks = fx.shown.toList().shuffled().take(Random.nextInt(CardFx.AT_ONCE.first, CardFx.AT_ONCE.last + 1))
                if (picks.isEmpty()) continue
                coroutineScope {
                    picks.forEachIndexed { i, id ->
                        launch {
                            if (i > 0) delay(Random.nextLong(CardFx.STAGGER_MS.toLong()))
                            fx.sparking[id] = System.nanoTime()
                            delay(CardFx.SPARK_MS.toLong())
                            fx.sparking.remove(id)
                        }
                    }
                }
            }
        }
    }
    return fx
}

/**
 * A card's share of its row's effects: the click animation and, now and then, a spark over it.
 * Showcase rows split them: the spark over the picture ([click] off), the click on the title ([spark]
 * off, so it isn't one of the cards sparks pick from).
 */
fun Modifier.cardFx(id: String, click: Boolean = true, spark: Boolean = true): Modifier = composed {
    val fx = LocalCardFx.current ?: return@composed this
    val clickOn = click
    val sparkOn = spark
    if (fx.spark != CardSpark.NONE && sparkOn) {
        DisposableEffect(fx, id) {
            fx.shown += id
            onDispose { fx.shown -= id }
        }
    }
    val click = remember { Animatable(0f) }
    val clicking = clickOn && fx.clicked.value == id
    LaunchedEffect(clicking) {
        if (clicking) {
            click.snapTo(0f)
            click.animateTo(1f, tween(CardFx.CLICK_MS, easing = if (fx.click == ClickEffect.SPIN) FastOutSlowInEasing else LinearEasing))
        } else click.snapTo(0f)
    }
    val spark = remember { Animatable(0f) }
    val sparkingNow = if (sparkOn) fx.sparking[id] else null
    LaunchedEffect(sparkingNow) {
        if (sparkingNow != null) {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(CardFx.SPARK_MS, easing = LinearEasing))
        }
        spark.snapTo(0f)
    }
    this
        .graphicsLayer {
            val p = click.value
            if (p <= 0f || p >= 1f) return@graphicsLayer
            val fade = 1 - p
            when (fx.click) {
                ClickEffect.NONE -> Unit
                ClickEffect.SPIN -> rotationZ = p * 1080f
                ClickEffect.BOUNCE -> translationY = -abs(sin(p * 3 * PI.toFloat())) * fade * 40.dp.toPx()
                ClickEffect.WOBBLE -> rotationZ = sin(p * 6 * PI.toFloat()) * fade * 14f
                ClickEffect.POP -> { val s = 1 + sin(p * PI.toFloat()) * 0.28f; scaleX = s; scaleY = s }
                ClickEffect.FLIP -> { cameraDistance = 14 * density; rotationY = p * 360f }
                ClickEffect.JELLY -> { val j = sin(p * 5 * PI.toFloat()) * fade * 0.2f; scaleX = 1 + j; scaleY = 1 - j }
            }
        }
        .drawWithContent {
            drawContent()
            val p = if (sparkingNow != null) fx.holdAt ?: spark.value else spark.value
            if (p > 0f && p < 1f) drawSparkAnySize(fx.spark, p, fx.scale, id.hashCode() xor (sparkingNow?.toInt() ?: 0))
        }
}

/**
 * A spark sized for what it's over: a card gets one; an area far larger than a card (a showcase's
 * full-screen picture) gets [BIG_SPOTS] card-sized ones at scattered spots, going off one after another,
 * so it looks the same as on cards rather than spread thin across the screen.
 */
internal fun DrawScope.drawSparkAnySize(spark: CardSpark, p: Float, k: Float, seed: Int) {
    val w = size.width
    val h = size.height
    if (w < BIG_AREA_PX) return drawSpark(spark, p, k, seed)
    val cw = w * 0.26f
    val ch = h * 0.34f
    for (i in 0 until BIG_SPOTS) {
        val local = (p * 1.45f - i * 0.15f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) continue
        // Columns across the picture, each at its own height, so spots never pile up together.
        val left = (w - cw) * ((i + rnd(seed + i, 20) * 0.6f) / (BIG_SPOTS - 0.4f)).coerceIn(0f, 1f)
        val top = (h - ch) * (0.08f + rnd(seed + i, 21) * 0.6f)
        inset(left, top, w - left - cw, h - top - ch) {
            drawSpark(spark, local, k, seed * 7 + i)
        }
    }
}

private const val BIG_AREA_PX = 1200f
private const val BIG_SPOTS = 4

/** One spark over the card at progress [p] (0..1). */
internal fun DrawScope.drawSpark(spark: CardSpark, p: Float, k: Float, seed: Int) {
    val w = size.width
    val h = size.height
    when (spark) {
        CardSpark.NONE -> Unit
        CardSpark.LIGHTNING -> {
            // Two quick flashes, a bolt through the card.
            val flash = if (p < 0.12f || p in 0.2f..0.28f) 1f else 0f
            if (flash > 0f) {
                drawRect(Color.White.copy(alpha = 0.35f))
                val x = w * (0.3f + rnd(seed, 1) * 0.4f)
                bolt(Offset(x, -h * 0.1f), Offset(x + (rnd(seed, 2) - 0.5f) * w * 0.5f, h * 0.95f), seed, Color(0xFFEFF6FF), 3f)
            }
        }
        CardSpark.FIREWORKS -> {
            val colors = listOf(Color(0xFFFF5252), Color(0xFFFFD740), Color(0xFF448AFF), Color(0xFF69F0AE))
            for (b in 0 until (2 * k).toInt().coerceAtLeast(1)) {
                val life = (p * 1.4f - b * 0.3f)
                burst(Offset(w * (0.25f + rnd(seed + b, 3) * 0.5f), h * (0.15f + rnd(seed + b, 4) * 0.35f)), life, w * 0.35f, colors[(seed + b).mod(4)], 18)
            }
        }
        CardSpark.SPARKLES -> {
            for (s in 0 until (8 * k).toInt().coerceAtLeast(2)) {
                val local = ((p - rnd(seed + s, 5) * 0.5f) * 2f).coerceIn(0f, 1f)
                val r = sin(local * PI.toFloat()) * w * 0.07f
                if (r > 0.5f) star(Offset(rnd(seed + s, 6) * w, rnd(seed + s, 7) * h), r, Color(0xEEFFF8E1), points = 4, inner = 0.25f)
            }
        }
        CardSpark.EMBERS -> {
            for (e in 0 until (16 * k).toInt().coerceAtLeast(4)) {
                val rise = (p * 1.3f - rnd(seed + e, 8) * 0.3f).coerceIn(0f, 1f)
                if (rise <= 0f) continue
                val x = rnd(seed + e, 9) * w + sin(rise * 8 + e) * w * 0.04f
                val y = h * (1.05f - rise * (0.6f + rnd(seed + e, 10) * 0.5f))
                drawCircle(Color(0xFFFF8F00).copy(alpha = (1 - rise) * 0.95f), radius = 2f + rnd(seed + e, 11) * 4f, center = Offset(x, y))
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF6D00).copy(alpha = 0.3f * sin(p * PI.toFloat()))), startY = h * 0.6f))
        }
        CardSpark.SHIMMER -> {
            val x = -w * 0.6f + p * w * 2.2f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), start = Offset(x, 0f), end = Offset(x + w * 0.5f, h)))
        }
    }
}
