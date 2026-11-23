package dev.nitrate.desktop.mpv

/**
 * One mpv context, as much of libmpv's client API (client.h, render.h) as Nitrate uses, without
 * pointers: [JnaMpvApi] over the real library, a fake in tests (so they need no DLL). Every call
 * is thread-safe, as libmpv's are.
 */
interface MpvApi {
    /** Before [initialize]: an option (mpv_set_option_string). Returns mpv's error code (0 = fine). */
    fun setOption(name: String, value: String): Int
    fun initialize(): Int

    /** A command and its arguments (mpv_command), waiting for it. */
    fun command(vararg args: String): Int
    fun setProperty(name: String, value: String): Int
    fun getString(name: String): String?
    fun getDouble(name: String): Double?

    /** Property changes arrive as [MpvEvent.PropertyChange] with [id]. */
    fun observe(id: Long, name: String, format: MpvFormat)
    fun requestLog(level: String)

    /** The next event, waiting up to [timeoutSeconds]; null when none came. */
    fun waitEvent(timeoutSeconds: Double): MpvEvent?

    /** Makes a waiting [waitEvent] return. */
    fun wakeup()

    /** mpv draws into memory Nitrate gives it (render API, "sw"): only with `vo=libmpv`. Null if it can't. */
    fun softwareRenderer(onUpdate: () -> Unit): SoftwareRenderer?

    fun errorString(code: Int): String

    /** Ends playback and frees everything (mpv_terminate_destroy). */
    fun destroy()
}

/** How an observed property's value comes back. */
enum class MpvFormat(val code: Int) { STRING(1), FLAG(3), INT64(4), DOUBLE(5) }

/** What mpv says happened. */
sealed interface MpvEvent {
    data object Shutdown : MpvEvent
    data object FileLoaded : MpvEvent
    data object PlaybackRestart : MpvEvent

    /** A file stopped: [reason] as mpv's (0 end, 2 stopped, 3 quit, 4 error, 5 redirect), [error] its code. */
    data class EndFile(val reason: Int, val error: Int) : MpvEvent {
        val failed: Boolean get() = reason == ERROR

        companion object {
            const val EOF = 0
            const val ERROR = 4
        }
    }

    data class PropertyChange(val id: Long, val name: String, val value: Any?) : MpvEvent
    data class Log(val prefix: String, val level: String, val text: String) : MpvEvent
    data class Other(val id: Int) : MpvEvent
}

/** mpv's software renderer: frames into memory at an address (RGB0 rows of [stride] bytes: red, green, blue and a padding byte Skia ignores). */
interface SoftwareRenderer {
    /** Whether a new frame is due (mpv_render_context_update says MPV_RENDER_UPDATE_FRAME). */
    fun frameDue(): Boolean
    fun render(width: Int, height: Int, stride: Long, address: Long): Int
    fun free()
}
