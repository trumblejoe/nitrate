package dev.nitrate.android.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.nitrate.ass.AssRenderer
import java.io.File

/** A styled (ASS/SSA) subtitle for libass: the whole script and the fonts it brings. */
class AssTrack(
    /** Changes when the track does (item, stream, offset of the source). */
    val key: String,
    val script: ByteArray,
    val fonts: List<Pair<String, ByteArray>>,
)

/**
 * ASS/SSA subtitles drawn by libass in their own view over the picture. Nothing touches the video
 * frames, so HDR, Dolby Vision and tunneled playback stay as they are. libass works on its own
 * thread; the view shows the last finished picture, swapped in on the UI thread, and draws only
 * when the subtitles change.
 */
class AssSubtitleView(context: Context) : View(context), Choreographer.FrameCallback {
    var player: Player? = null

    /** Subtitle timing: positive shows them later. */
    var offsetMs: Long = 0

    private var track: AssTrack? = null
    // libass works here; there while attached to a window, made again when attached again.
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    // On the worker thread only.
    private var renderer: AssRenderer? = null
    private var sizeKey = ""

    // On the UI thread.
    private var front: Bitmap? = null
    private var spare: Bitmap? = null
    private var busy = false
    private var running = false

    /** Shows [next] (null: none). Loading happens off the UI thread. */
    fun setTrack(next: AssTrack?) {
        if (next?.key == track?.key) return
        track = next
        front = null
        invalidate()
        worker?.post { load(next) }
        if (next != null) start() else stop()
    }

    private fun load(next: AssTrack?) {
        renderer?.release()
        renderer = null
        sizeKey = ""
        next ?: return
        val r = AssRenderer.create() ?: return
        next.fonts.forEach { (name, data) -> r.addFont(name, data) }
        r.setDefaultFont(DEFAULT_FONT, null)
        if (!r.loadTrack(next.script)) { r.release(); return }
        renderer = r
    }

    private fun start() {
        if (running || !isAttachedToWindow || track == null) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val t = HandlerThread("nitrate-ass").apply { start() }
        thread = t
        worker = Handler(t.looper).apply { val current = track; post { load(current) } }
        busy = false
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        worker?.post { renderer?.release(); renderer = null }
        thread?.quitSafely()
        thread = null
        worker = null
        front = null
        spare = null
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val p = player ?: return
        val handler = worker ?: return
        if (busy || width == 0 || height == 0) return
        // Rendered smaller than a big screen (1920 across at most), then scaled up when drawn.
        val scale = minOf(1f, MAX_WIDTH / width)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val video = p.videoSize
        val time = p.currentPosition - offsetMs
        val nothingShown = front == null
        val target = spare?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        spare = null
        busy = true
        handler.post {
            val r = renderer
            val result = if (r == null) AssRenderer.Result.EMPTY else {
                val key = "$w×$h/${video.width}×${video.height}"
                val resized = key != sizeKey
                if (resized) { r.setSize(w, h, video.width, video.height); sizeKey = key }
                // "Unchanged" leaves what's shown; anything else is drawn in full into [target].
                r.render(time, target, force = resized || nothingShown)
            }
            post { finish(target, result) }
        }
    }

    private fun finish(target: Bitmap, result: AssRenderer.Result) {
        busy = false
        when (result) {
            AssRenderer.Result.DRAWN -> {
                spare = front
                front = target
                invalidate()
            }
            AssRenderer.Result.EMPTY -> {
                spare = target
                if (front != null) { front = null; invalidate() }
            }
            AssRenderer.Result.UNCHANGED -> spare = target
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = front ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, paint)
    }

    companion object {
        private const val MAX_WIDTH = 1920f

        /** Android's own font, for anything the script names but doesn't bring. */
        private val DEFAULT_FONT: String? = listOf(
            "/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/DroidSans.ttf",
        ).firstOrNull { File(it).exists() }

        /** Whether this app has libass built in. */
        val isAvailable: Boolean get() = AssRenderer.isAvailable
    }
}

/** This player view's libass layer, laid over the video (inside the frame sized to the picture). */
fun PlayerView.assSubtitles(): AssSubtitleView {
    val frame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) as ViewGroup? ?: this
    (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<AssSubtitleView>().firstOrNull()?.let { return it }
    val view = AssSubtitleView(context)
    frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return view
}
