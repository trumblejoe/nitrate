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
 * A spark sized for what it's over: a card gets the card one; an area far larger than a card (full
 * art, a row with its cards hidden) gets the screen's own version ([drawScreenSpark]), drawn at the
 * screen's scale in the page themes' realistic style rather than card-sized sparks dotted about.
 */
internal fun DrawScope.drawSparkAnySize(spark: CardSpark, p: Float, k: Float, seed: Int) {
    if (size.width < BIG_WIDTH_PX || size.height < BIG_HEIGHT_PX) drawSpark(spark, p, k, seed) else drawScreenSpark(spark, p, k, seed)
}

// Big enough to be the screen (or most of it), not a large card.
private const val BIG_WIDTH_PX = 1200f
private const val BIG_HEIGHT_PX = 800f

/**
 * Each spark at the scale of the whole screen, for progress [p] (0..1): sizes follow the screen's
 * height, so they look the same on any TV. Lightning strikes down the picture and lights it;
 * fireworks burst across the sky with streaks; sparkles glint all over; embers rise from the bottom;
 * shimmer is a sheen of light sweeping across.
 */
internal fun DrawScope.drawScreenSpark(spark: CardSpark, p: Float, k: Float, seed: Int) {
    val w = size.width
    val h = size.height
    val u = h / 1080f
    when (spark) {
        CardSpark.NONE -> Unit
        CardSpark.LIGHTNING -> {
            // A real strike's flicker: the stroke, a dip, return strokes, then a fade.
            val env = when {
                p < 0.04f -> 1f
                p < 0.07f -> 0.25f
                p < 0.11f -> 0.9f
                p < 0.14f -> 0.35f
                p < 0.18f -> 0.75f
                p < 0.6f -> 0.75f * kotlin.math.exp(-(p - 0.18f) * 9f)
                else -> 0f
            }
            if (env <= 0f) return
            val top = Offset(w * (0.2f + rnd(seed, 1) * 0.6f), -h * 0.02f)
            drawRect(Color(0xFFCAD6FF).copy(alpha = 0.16f * env), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
            glow(top, h * 0.9f, Color(0xFF9FB4FF), alpha = 0.4f * env)
            if (p < 0.4f) {
                val ground = Offset(top.x + (rnd(seed, 2) - 0.5f) * w * 0.35f, h * (0.8f + rnd(seed, 3) * 0.2f))
                lightning(boltSegments(top, ground, seed * 97 + 11), env, scale = u)
                // A second, fainter fork now and then.
                if (rnd(seed, 4) > 0.5f) lightning(boltSegments(top, Offset(ground.x + w * 0.18f * (if (rnd(seed, 5) > 0.5f) 1 else -1), h * 0.62f), seed * 53 + 7), env * 0.5f, scale = u)
            }
        }
        CardSpark.FIREWORKS -> {
            val colors = listOf(Color(0xFFFF5A5A), Color(0xFFFFD54F), Color(0xFF64A0FF), Color(0xFF7CF0B0), Color(0xFFE38BFF))
            val shells = (3 * k).toInt().coerceIn(2, 5)
            for (b in 0 until shells) {
                // Staggered bursts across the top half, each streaking out, slowing and falling.
                val tau = (p * 1.6f - b * 0.22f)
                if (tau <= 0f || tau >= 1f) continue
                val at = Offset(w * (0.12f + (b + rnd(seed + b, 3) * 0.8f) / shells * 0.76f), h * (0.12f + rnd(seed + b, 4) * 0.32f))
                val color = colors[(seed + b).mod(colors.size)]
                val reach = h * (0.2f + rnd(seed + b, 5) * 0.08f)
                val fade = kotlin.math.sqrt(1 - tau)
                if (tau < 0.3f) glow(at, h * 0.5f, color, 0.22f * (1 - tau / 0.3f))
                val n = 56
                fun pos(i: Int, t: Float): Offset {
                    val a = 2 * PI.toFloat() * i / n + rnd(i, seed + b) * 0.12f
                    val v = reach * kotlin.math.sqrt(0.3f + 0.7f * rnd(i + 7, seed + b))
                    val e = 1 - kotlin.math.exp(-4f * t)
                    return at + Offset(kotlin.math.cos(a) * v * e, sin(a) * v * e + t * t * reach * 0.45f)
                }
                for (i in 0 until n) {
                    val head = pos(i, tau)
                    var prev = head
                    for (s2 in 1..7) {
                        val back = pos(i, (tau - s2 * 0.025f).coerceAtLeast(0f))
                        drawLine(color.copy(alpha = (fade * (1 - s2 / 8f) * 0.8f).coerceIn(0f, 1f)), prev, back, 2.6f * u, androidx.compose.ui.graphics.StrokeCap.Round, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
                        prev = back
                    }
                    val hot = (1 - tau / 0.25f).coerceIn(0f, 1f)
                    glow(head, (14f + 10f * hot) * u, color, (fade * 0.9f).coerceIn(0f, 1f))
                    drawCircle(androidx.compose.ui.graphics.lerp(color, Color.White, 0.35f + 0.65f * hot).copy(alpha = fade.coerceIn(0f, 1f)), (2.4f + 1.4f * hot) * u, head)
                }
            }
        }
        CardSpark.SPARKLES -> {
            // Glints all over the picture, each a soft glow with a four-pointed flare.
            for (s in 0 until (36 * k).toInt().coerceAtLeast(10)) {
                val local = ((p - rnd(seed + s, 5) * 0.55f) * 2.2f).coerceIn(0f, 1f)
                val bright = sin(local * PI.toFloat())
                if (bright <= 0.02f) continue
                val c = Offset(rnd(seed + s, 6) * w, rnd(seed + s, 7) * h)
                val r = h * (0.018f + rnd(seed + s, 8) * 0.03f) * bright
                glow(c, r * 2.2f, Color(0xFFFFF4D6), 0.5f * bright)
                star(c, r, Color(0xFFFFFBF0).copy(alpha = 0.9f * bright), points = 4, inner = 0.12f, rotation = rnd(seed + s, 9) * 0.6f)
            }
        }
        CardSpark.EMBERS -> {
            // Sparks rising from a glow along the bottom, drifting as they cool.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF6D00).copy(alpha = 0.28f * sin(p * PI.toFloat()))), startY = h * 0.65f))
            for (e in 0 until (70 * k).toInt().coerceAtLeast(20)) {
                val rise = (p * 1.35f - rnd(seed + e, 8) * 0.35f).coerceIn(0f, 1f)
                if (rise <= 0f || rise >= 1f) continue
                val x = rnd(seed + e, 9) * w + sin(rise * 7 + e) * h * 0.04f
                val y = h * (1.02f - rise * (0.45f + rnd(seed + e, 10) * 0.55f))
                val r = h * (0.003f + rnd(seed + e, 11) * 0.005f)
                val heat = 1 - rise
                glow(Offset(x, y), r * 5f, Color(0xFFFF8A1E), 0.55f * heat)
                drawCircle(Color(0xFFFFC46B).copy(alpha = heat), r, Offset(x, y))
            }
        }
        CardSpark.SHIMMER -> {
            // A broad soft sheen sweeping across the picture, brightest along its middle.
            val x = -w * 0.5f + p * w * 2f
            val band = w * 0.28f
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.4f to Color.White.copy(alpha = 0.08f), 0.5f to Color.White.copy(alpha = 0.26f), 0.6f to Color.White.copy(alpha = 0.08f), 1f to Color.Transparent,
                    start = Offset(x - band, 0f), end = Offset(x + band, h * 0.6f),
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
            )
        }
    }
}

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
