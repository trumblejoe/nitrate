package dev.nitrate.desktop.mpv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nitrate.mobile.ui.MpvConfig
import dev.nitrate.mobile.ui.MpvMedia
import dev.nitrate.mobile.ui.MpvPlayer
import dev.nitrate.mobile.ui.MpvState
import dev.nitrate.mobile.ui.VideoOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * [MpvPlayer] over one mpv context ([MpvApi]): the options Nitrate plays with, loading with the
 * account's header and the picked tracks, observed properties and events on a thread of its own,
 * and the picture either in mpv's native window inside a heavyweight AWT canvas ([VideoOutput.WINDOW]:
 * `wid`, gpu-next, HDR passed to HDR screens; the canvas's window handle comes from [windowId]) or
 * drawn by mpv's software renderer into Skia bitmaps that Compose shows ([VideoOutput.SOFTWARE]).
 */
class LibMpvPlayer(
    private val api: MpvApi,
    private val config: MpvConfig,
    /** The native window handle of a displayable AWT component (JNA's Native.getComponentID). */
    private val windowId: (java.awt.Component) -> Long,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
) : MpvPlayer {
    private val _state = MutableStateFlow(MpvState())
    override val state: StateFlow<MpvState> = _state
    override val nativeWindow: Boolean = config.output == VideoOutput.WINDOW

    @Volatile private var initialized = false
    @Volatile private var released = false
    private var pending: MpvMedia? = null
    private var current: MpvMedia? = null
    private var events: Thread? = null
    private val log = ArrayDeque<String>()

    /** The options for this player, before mpv starts. */
    internal fun options(): List<Pair<String, String>> = buildList {
        add("config" to "no")
        add("terminal" to "no")
        add("load-scripts" to "no")
        add("ytdl" to "no")
        add("osc" to "no")
        add("osd-level" to "0")
        add("input-default-bindings" to "no")
        add("input-vo-keyboard" to "no")
        // The mouse belongs to Nitrate's controls above the picture.
        add("input-cursor" to "no")
        add("cursor-autohide" to "no")
        add("idle" to "yes")
        // Hold the last frame at the end rather than unloading: Up Next and credits act on it.
        add("keep-open" to "yes")
        add("sub-auto" to "no")
        add("embeddedfonts" to "yes")
        add("cache" to "yes")
        add("demuxer-max-bytes" to "200MiB")
        add("demuxer-max-back-bytes" to "50MiB")
        add("user-agent" to config.userAgent)
        add("volume-max" to "130")
        add("volume" to config.volume.coerceIn(0, 130).toString())
        add("msg-level" to "all=warn")
        if (windows) add("ao" to "wasapi")
        // Bitstreaming to a receiver: off unless chosen; TrueHD and DTS-HD need WASAPI to itself.
        if (config.spdif.isNotEmpty()) {
            add("audio-spdif" to config.spdif)
            if ("truehd" in config.spdif || "dts-hd" in config.spdif) add("audio-exclusive" to "yes")
        }
        when (config.output) {
            VideoOutput.WINDOW -> {
                add("vo" to "gpu-next")
                if (windows) add("gpu-api" to "d3d11")
                add("force-window" to "yes")
                // HDR goes to an HDR screen as HDR (Windows' HDR on); SDR screens get mpv's tone mapping.
                add("target-colorspace-hint" to "yes")
                add("hwdec" to if (config.hardwareDecoding) "auto-safe" else "no")
            }
            VideoOutput.SOFTWARE -> {
                add("vo" to "libmpv")
                // Frames come back to memory for the software renderer.
                add("hwdec" to if (config.hardwareDecoding) "auto-copy-safe" else "no")
            }
        }
        add("tone-mapping" to "auto")
        add("hdr-compute-peak" to "auto")
    }

    @Synchronized
    private fun start(wid: Long? = null) {
        if (initialized || released) return
        wid?.let { api.setOption("wid", it.toString()) }
        val result = api.initialize()
        if (result < 0) {
            _state.update { it.copy(error = "mpv didn't start: ${api.errorString(result)}") }
            return
        }
        initialized = true
        api.requestLog("warn")
        PROPERTIES.forEach { (id, p) -> api.observe(id, p.first, p.second) }
        if (!nativeWindow) renderer = api.softwareRenderer { frames.release() }
        events = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
        if (!nativeWindow) drawing = Thread(::drawLoop, "mpv-draw").apply { isDaemon = true; start() }
        pending?.let { pending = null; open(it) }
    }

    override fun load(media: MpvMedia) {
        synchronized(this) {
            if (!initialized) {
                pending = media
                return
            }
        }
        open(media)
    }

    private fun open(media: MpvMedia) {
        current = media
        _state.value = MpvState(paused = false)
        // The account's Authorization header (commas and all) as one list entry, for the stream and subtitle files.
        api.command("change-list", "http-header-fields", "clr", "")
        media.headers.forEach { (k, v) -> api.command("change-list", "http-header-fields", "append", "$k: $v") }
        api.setProperty("start", formatSeconds(media.startSeconds))
        api.setProperty("aid", media.audioId?.toString() ?: "auto")
        // Subtitle files are added once the file is open; until then, the file's own or none.
        api.setProperty("sid", if (media.subtitleUrls.isEmpty()) sid(media.subtitleId) else "no")
        media.fontsDir?.let { api.setProperty("sub-fonts-dir", it) }
        api.setProperty("pause", "no")
        val result = api.command("loadfile", media.url, "replace")
        if (result < 0) _state.update { it.copy(error = api.errorString(result)) }
    }

    private fun sid(id: Int) = if (id <= 0) "no" else id.toString()

    private fun eventLoop() {
        while (!released) {
            when (val e = api.waitEvent(1.0) ?: continue) {
                MpvEvent.Shutdown -> return
                MpvEvent.FileLoaded -> {
                    current?.let { m ->
                        m.subtitleUrls.forEach { api.command("sub-add", it, "auto") }
                        if (m.subtitleUrls.isNotEmpty()) api.setProperty("sid", sid(m.subtitleId))
                    }
                    _state.update { it.copy(loaded = true, ended = false, error = null) }
                }
                is MpvEvent.EndFile -> if (e.failed) {
                    _state.update { it.copy(error = "${api.errorString(e.error)}${lastLog()?.let { l -> " ($l)" } ?: ""}") }
                }
                is MpvEvent.PropertyChange -> onProperty(e)
                is MpvEvent.Log -> synchronized(log) {
                    log.addLast("[${e.prefix}] ${e.text}")
                    while (log.size > 40) log.removeFirst()
                }
                else -> Unit
            }
        }
    }

    internal fun onProperty(e: MpvEvent.PropertyChange) {
        when (e.name) {
            "time-pos" -> (e.value as? Double)?.let { v -> _state.update { it.copy(positionSeconds = v) } }
            "duration" -> (e.value as? Double)?.let { v -> _state.update { it.copy(durationSeconds = v) } }
            "pause" -> (e.value as? Boolean)?.let { v -> _state.update { it.copy(paused = v) } }
            "eof-reached" -> (e.value as? Boolean)?.let { v -> _state.update { it.copy(ended = v) } }
            "paused-for-cache" -> (e.value as? Boolean)?.let { v -> _state.update { it.copy(buffering = v) } }
        }
    }

    private fun lastLog(): String? = synchronized(log) { log.lastOrNull() }

    override fun setAudio(id: Int) { api.setProperty("aid", id.toString()) }
    override fun setSubtitle(id: Int) { api.setProperty("sid", sid(id)) }
    override fun play() { api.setProperty("pause", "no") }
    override fun pause() { api.setProperty("pause", "yes") }

    override fun seek(seconds: Double) {
        api.command("seek", formatSeconds(seconds), "absolute")
        _state.update { it.copy(positionSeconds = seconds, ended = false) }
    }

    override fun setSpeed(speed: Double) { api.setProperty("speed", formatSeconds(speed)) }
    override fun setVolume(volume: Int) { api.setProperty("volume", volume.coerceIn(0, 130).toString()) }
    override fun setMuted(muted: Boolean) { api.setProperty("mute", if (muted) "yes" else "no") }
    override fun setAudioDelay(ms: Int) { api.setProperty("audio-delay", formatSeconds(ms / 1000.0)) }
    override fun setSubtitleDelay(ms: Int) { api.setProperty("sub-delay", formatSeconds(ms / 1000.0)) }

    override fun decoding() = dev.nitrate.core.playback.MpvDecoding.read { api.getString(it) }

    override fun describe(): List<Pair<String, String>> {
        fun s(name: String) = api.getString(name)?.takeIf { it.isNotBlank() }
        return listOfNotNull(
            s("video-codec")?.let { "Video decoder" to it },
            "Hardware decoding" to (s("hwdec-current")?.takeIf { it != "no" } ?: "off (CPU)"),
            s("current-vo")?.let { "Video output" to it },
            listOfNotNull(s("video-params/w"), s("video-params/h")).takeIf { it.size == 2 }?.let { "Picture" to it.joinToString("×") + (s("video-params/pixelformat")?.let { f -> " · $f" } ?: "") },
            s("video-params/gamma")?.let { g -> "Colour" to listOfNotNull(s("video-params/primaries"), g).joinToString(" · ") },
            s("estimated-vf-fps")?.let { "Frame rate" to it.take(6) },
            s("frame-drop-count")?.let { "Dropped frames" to it },
            s("audio-codec")?.let { "Audio decoder" to it },
            s("audio-params/channels")?.let { "Sound from the file" to it },
            s("audio-out-params/format")?.let { f -> "Sound out" to listOfNotNull(f, s("audio-out-params/channels"), s("current-ao")).joinToString(" · ") },
            s("demuxer-cache-duration")?.let { "Buffered" to (it.toDoubleOrNull()?.let { d -> "${d.toInt()} s" } ?: it) },
            lastLog()?.let { "Last message" to it },
        )
    }

    // --- The native window: mpv draws into a heavyweight canvas (wid). ---

    private val canvas: java.awt.Canvas by lazy {
        object : java.awt.Canvas() {
            override fun addNotify() {
                super.addNotify()
                background = java.awt.Color.BLACK
                // Now it has a native window: mpv can draw in it.
                start(windowId(this))
            }

            override fun removeNotify() {
                // mpv's window goes before its parent's does.
                release()
                super.removeNotify()
            }
        }
    }

    // --- The software picture: frames rendered by mpv straight into Skia bitmaps. ---

    @Volatile private var renderer: SoftwareRenderer? = null
    private val renderLock = Any()

    /** Frames drawn and the last render's result (for tests and Info). */
    @Volatile internal var framesDrawn = 0L
    @Volatile internal var lastRender = 0
    private var drawing: Thread? = null
    private val frames = Semaphore(0)
    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0
    private val shown = mutableStateOf<ImageBitmap?>(null)
    private val buffers = arrayOfNulls<org.jetbrains.skia.Bitmap>(3)
    private var nextBuffer = 0

    private fun drawLoop() {
        while (!released) {
            frames.tryAcquire(250, TimeUnit.MILLISECONDS)
            frames.drainPermits()
            val r = renderer ?: continue
            val w = targetWidth
            val h = targetHeight
            if (w <= 0 || h <= 0) continue
            if (!r.frameDue() && shown.value?.let { it.width == w && it.height == h } == true) continue
            val bitmap = bitmap(w, h)
            val pixels = bitmap.peekPixels() ?: continue
            val result = synchronized(renderLock) { if (released) -1 else r.render(w, h, pixels.rowBytes.toLong(), pixels.addr) }
            lastRender = result
            if (result >= 0) {
                framesDrawn++
                bitmap.notifyPixelsChanged()
                shown.value = bitmap.asComposeImageBitmap()
            }
        }
    }

    /** The next of three bitmaps (one shown, one waiting, one being drawn), sized [w]×[h]. */
    private fun bitmap(w: Int, h: Int): org.jetbrains.skia.Bitmap {
        val i = nextBuffer
        nextBuffer = (nextBuffer + 1) % buffers.size
        val existing = buffers[i]
        if (existing != null && existing.width == w && existing.height == h) return existing
        existing?.close()
        return org.jetbrains.skia.Bitmap().also { b ->
            b.allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.RGB_888X, org.jetbrains.skia.ColorAlphaType.OPAQUE))
            buffers[i] = b
        }
    }

    @Composable
    override fun Video(modifier: Modifier) {
        if (nativeWindow) {
            SwingPanel(background = Color.Black, factory = { canvas }, modifier = modifier)
        } else {
            val frame by shown
            Box(modifier.background(Color.Black).onSizeChanged { size ->
                targetWidth = size.width
                targetHeight = size.height
                frames.release()
            }) {
                frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
            }
        }
    }

    override fun release() {
        synchronized(this) {
            if (released) return
            released = true
        }
        synchronized(renderLock) {
            renderer?.free()
            renderer = null
        }
        frames.release()
        // The event thread leaves mpv_wait_event before the context goes.
        api.wakeup()
        events?.let { if (it !== Thread.currentThread()) it.join(2_000) }
        api.destroy()
    }

    // Last, once every field above is set: the software picture starts mpv (and its threads) at once.
    init {
        options().forEach { (k, v) -> api.setOption(k, v) }
        // The software picture can start at once; the native window waits for its canvas (wid is set before init).
        if (!nativeWindow) start()
    }

    internal companion object {
        /** Observed properties: reply id to name and format. */
        val PROPERTIES = mapOf(
            1L to ("time-pos" to MpvFormat.DOUBLE),
            2L to ("duration" to MpvFormat.DOUBLE),
            3L to ("pause" to MpvFormat.FLAG),
            4L to ("eof-reached" to MpvFormat.FLAG),
            5L to ("paused-for-cache" to MpvFormat.FLAG),
        )

        /** Seconds as mpv reads them (a dot, whatever the PC's language). */
        fun formatSeconds(seconds: Double): String = String.format(java.util.Locale.ROOT, "%.3f", seconds)
    }
}
