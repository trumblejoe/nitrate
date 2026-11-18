package dev.nitrate.tv.ui.preroll

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.preroll.PrerollPrefs
import dev.nitrate.core.preroll.PrerollScene
import dev.nitrate.core.preroll.PrerollStep
import dev.nitrate.core.preroll.Prerolls
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What's about to play before a title: the steps the next [PrerollScreen] runs, and when pre-rolls last ran. */
object PrerollPlan {
    var steps: List<PrerollStep> = emptyList()
    var lastRunMs: Long? = null
}

/** The scene clips this build ships (`assets/fx/preroll-*.mp4`). */
fun shippedClips(context: android.content.Context): Set<String> =
    runCatching { context.assets.list("fx")?.filter { it.endsWith(".mp4") }?.map { it.removeSuffix(".mp4") }?.toSet() }.getOrNull() ?: emptySet()

/**
 * The steps before [item], or none: the server's intros and the user's own videos are asked for
 * with a short limit, so playing never waits long on them.
 */
suspend fun planPrerolls(container: AppContainer, context: android.content.Context, item: MediaItem, prefs: PrerollPrefs, fromStart: Boolean): List<PrerollStep> {
    val now = System.currentTimeMillis()
    if (!Prerolls.applies(item, prefs, fromStart, now, PrerollPlan.lastRunMs)) return emptyList()
    val server = container.server
    val intros = if (prefs.serverIntros) withTimeoutOrNull(2_500) { runCatching { server.intros(item.id) }.getOrNull() }.orEmpty() else emptyList()
    val own = prefs.ownVideos?.let { src ->
        withTimeoutOrNull(2_500) { runCatching { if (src.playlist) server.playlistItems(src.id) else server.collectionItems(src.id) }.getOrNull() }
    }.orEmpty()
    return Prerolls.plan(prefs, intros, own, shippedClips(context)).also { if (it.isNotEmpty()) PrerollPlan.lastRunMs = now }
}

/**
 * Plays the steps that aren't videos, full screen on black: a scene, then a verse fading in and out.
 * OK or ▶ skips the one showing; Back skips them all. [onDone] gets the videos still to play (they go
 * through the player, before the title).
 */
@Composable
fun PrerollScreen(steps: List<PrerollStep>, prefs: PrerollPrefs, onDone: (List<MediaItem>) -> Unit) {
    val shown = remember(steps) { steps.filter { it !is PrerollStep.Video } }
    val videos = remember(steps) { steps.filterIsInstance<PrerollStep.Video>().map { it.item } }
    var index by remember { mutableIntStateOf(0) }
    var skipToken by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    BackHandler { onDone(videos) }
    LaunchedEffect(index, shown) { if (index >= shown.size) onDone(videos) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onKeyEvent { e ->
                val skip = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.MediaPlay || e.key == Key.MediaPlayPause || e.key == Key.DirectionRight
                if (skip && e.type == KeyEventType.KeyUp) { skipToken++; true } else skip
            },
    ) {
        shown.getOrNull(index)?.let { step ->
            // Each step fades up from black and back down; skipping fades it out quickly.
            androidx.compose.runtime.key(index) {
                StepView(step, prefs, skipToken) { index++ }
            }
        }
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(4_000); hint = false }
        if (hint) Text("OK skips · Back goes straight to it", color = Color(0x88FFFFFF), fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun StepView(step: PrerollStep, prefs: PrerollPrefs, skipToken: Int, onEnd: () -> Unit) {
    val seconds = when (step) { is PrerollStep.Scene -> prefs.sceneSeconds; else -> prefs.verseSeconds }.coerceIn(4, 60)
    val fade = remember { Animatable(0f) }
    val startToken = remember { skipToken }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(1_800, easing = LinearEasing))
        delay(seconds * 1000L - 3_600L)
        fade.animateTo(0f, tween(1_800, easing = LinearEasing))
        onEnd()
    }
    LaunchedEffect(skipToken) {
        if (skipToken != startToken) { fade.animateTo(0f, tween(500)); onEnd() }
    }
    Box(Modifier.fillMaxSize().alpha(fade.value)) {
        when (step) {
            is PrerollStep.Verse -> VerseCard(step.poem.lines, "— ${step.poem.poet}", step.poem.source)
            is PrerollStep.Line -> VerseCard(step.text.lines(), null, null)
            is PrerollStep.Scene -> SceneView(step.scene)
            is PrerollStep.Video -> Unit
        }
    }
}

@Composable
private fun VerseCard(lines: List<String>, poet: String?, source: String?) {
    Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEachIndexed { i, line ->
                // Tanka layout: the second and fourth lines set in.
                Text(line, color = Color(0xFFF0ECE2), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 30.sp, lineHeight = 40.sp,
                    modifier = Modifier.padding(start = if (lines.size == 5 && i % 2 == 1) 36.dp else 0.dp))
            }
            if (poet != null) Text(poet, color = Color(0xB3E6E0D2), fontFamily = FontFamily.Serif, fontSize = 18.sp, modifier = Modifier.padding(top = 18.dp))
            if (source != null) Text(source, color = Color(0x66E6E0D2), fontFamily = FontFamily.Serif, fontSize = 12.sp, textAlign = TextAlign.Start)
        }
    }
}

/** A scene: its film clip when this build has it, else its drawing. */
@Composable
fun SceneView(scene: PrerollScene, at: Float? = null) {
    val context = LocalContext.current
    val clip = remember(scene) { scene.clip.takeIf { it in shippedClips(context) } }
    if (clip != null && at == null) {
        val player = remember(clip) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(ExoItem.fromUri("asset:///fx/$clip.mp4"))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } }, Modifier.fillMaxSize())
        return
    }
    val t by produceState(at ?: 0f, at) {
        if (at != null) return@produceState
        val start = System.nanoTime()
        while (true) { value = (System.nanoTime() - start) / 1e9f; delay(33) }
    }
    Canvas(Modifier.fillMaxSize()) {
        with(dev.nitrate.tv.ui.effects.PrerollDrawing) { draw(scene, t) }
    }
}
