package dev.nitrate.desktop.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * libmpv's C API as JNA sees it: Nitrate's own declarations, written from mpv's public headers
 * (client.h and render.h, which are ISC-licensed so that any program may use the library).
 * Only what [JnaMpvApi] calls.
 */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_error_string(error: Int): String
    fun mpv_free(data: Pointer?)
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, data: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    /** mpv_render_update_fn: called from mpv's threads when a frame is ready. */
    fun interface UpdateCallback : Callback {
        fun invoke(data: Pointer?)
    }
}

/** Finds and loads libmpv: the copy shipped beside the app, else (Linux/macOS development) the system's. */
object LibMpvLoader {
    /** Major version of the client API Nitrate is written against (mpv 0.35 and later). */
    private const val API_MAJOR = 2L

    /** The library, or why it isn't there. */
    sealed interface Result {
        class Loaded internal constructor(internal val lib: LibMpv, val version: String) : Result
        data class Missing(val reason: String) : Result
    }

    fun load(): Result {
        // Compose's packaging puts the DLLs in the app's resources folder (desktop/resources/windows-x64).
        val dirs = listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getenv("NITRATE_MPV_DIR"))
            .map(::File).filter { it.isDirectory }
        dirs.forEach { NativeLibrary.addSearchPath("mpv-2", it.absolutePath); NativeLibrary.addSearchPath("libmpv-2", it.absolutePath); NativeLibrary.addSearchPath("mpv", it.absolutePath) }
        val errors = mutableListOf<String>()
        for (name in listOf("libmpv-2", "mpv-2", "mpv")) {
            val lib = runCatching {
                Native.load(name, LibMpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            }.onFailure { errors += it.message.orEmpty() }.getOrNull() ?: continue
            val version = runCatching { lib.mpv_client_api_version() }.getOrNull() ?: continue
            if (version shr 16 != API_MAJOR) return Result.Missing("This libmpv speaks client API ${version shr 16}.${version and 0xffff}; Nitrate needs $API_MAJOR.x.")
            return Result.Loaded(lib, "${version shr 16}.${version and 0xffff}")
        }
        return Result.Missing("libmpv wasn't found beside the app (${errors.firstOrNull()?.lineSequence()?.firstOrNull() ?: "no library"}).")
    }
}

/** [MpvApi] over libmpv through JNA: one mpv_handle. */
class JnaMpvApi private constructor(private val lib: LibMpv, private val ctx: Pointer) : MpvApi {
    @Volatile private var destroyed = false

    override fun setOption(name: String, value: String) = lib.mpv_set_option_string(ctx, name, value)
    override fun initialize() = lib.mpv_initialize(ctx)

    override fun command(vararg args: String): Int = if (destroyed) -1 else lib.mpv_command(ctx, arrayOf(*args, null))

    override fun setProperty(name: String, value: String): Int = if (destroyed) -1 else lib.mpv_set_property_string(ctx, name, value)

    override fun getString(name: String): String? {
        if (destroyed) return null
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }

    override fun getDouble(name: String): Double? {
        if (destroyed) return null
        val out = DoubleByReference()
        return if (lib.mpv_get_property(ctx, name, MpvFormat.DOUBLE.code, out.pointer) >= 0) out.value else null
    }

    override fun observe(id: Long, name: String, format: MpvFormat) {
        lib.mpv_observe_property(ctx, id, name, format.code)
    }

    override fun requestLog(level: String) {
        lib.mpv_request_log_messages(ctx, level)
    }

    override fun waitEvent(timeoutSeconds: Double): MpvEvent? {
        if (destroyed) return MpvEvent.Shutdown
        // struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
        val e = lib.mpv_wait_event(ctx, timeoutSeconds)
        val id = e.getInt(0)
        val data = e.getPointer(16)
        return when (id) {
            0 -> null
            1 -> MpvEvent.Shutdown
            // struct mpv_event_log_message { const char *prefix, *level, *text; int log_level; }
            2 -> data?.let { MpvEvent.Log(it.getPointer(0)?.getString(0, "UTF-8").orEmpty(), it.getPointer(8)?.getString(0, "UTF-8").orEmpty(), it.getPointer(16)?.getString(0, "UTF-8").orEmpty().trimEnd()) }
            // struct mpv_event_end_file { int reason; int error; ... }
            7 -> MpvEvent.EndFile(data?.getInt(0) ?: 0, data?.getInt(4) ?: 0)
            8 -> MpvEvent.FileLoaded
            21 -> MpvEvent.PlaybackRestart
            // struct mpv_event_property { const char *name; mpv_format format; void *data; }
            22 -> data?.let { prop ->
                val name = prop.getPointer(0)?.getString(0, "UTF-8").orEmpty()
                val format = prop.getInt(8)
                val value = prop.getPointer(16)?.let { v ->
                    when (format) {
                        MpvFormat.STRING.code -> v.getPointer(0)?.getString(0, "UTF-8")
                        MpvFormat.FLAG.code -> v.getInt(0) != 0
                        MpvFormat.INT64.code -> v.getLong(0)
                        MpvFormat.DOUBLE.code -> v.getDouble(0)
                        else -> null
                    }
                }
                MpvEvent.PropertyChange(e.getLong(8), name, value)
            }
            else -> MpvEvent.Other(id)
        }
    }

    override fun wakeup() {
        if (!destroyed) lib.mpv_wakeup(ctx)
    }

    override fun softwareRenderer(onUpdate: () -> Unit): SoftwareRenderer? {
        // mpv_render_param { int type; void *data; }, an array ending in type 0.
        val apiType = Memory(3).apply { setString(0, "sw", "US-ASCII") }
        val params = renderParams(listOf(PARAM_API_TYPE to apiType))
        val out = PointerByReference()
        if (lib.mpv_render_context_create(out, ctx, params) < 0) return null
        val rctx = out.value ?: return null
        return object : SoftwareRenderer {
            // Held here: JNA callbacks must stay reachable while mpv may call them.
            private val callback = LibMpv.UpdateCallback { onUpdate() }
            private val size = Memory(8)
            private val format = Memory(5).apply { setString(0, "rgb0", "US-ASCII") }
            private val strideMem = Memory(Native.SIZE_T_SIZE.toLong())
            @Volatile private var freed = false

            init {
                lib.mpv_render_context_set_update_callback(rctx, callback, null)
            }

            override fun frameDue(): Boolean = !freed && (lib.mpv_render_context_update(rctx) and UPDATE_FRAME) != 0L

            override fun render(width: Int, height: Int, stride: Long, address: Long): Int {
                if (freed) return -1
                size.setInt(0, width)
                size.setInt(4, height)
                if (Native.SIZE_T_SIZE == 8) strideMem.setLong(0, stride) else strideMem.setInt(0, stride.toInt())
                val p = renderParams(listOf(PARAM_SW_SIZE to size, PARAM_SW_FORMAT to format, PARAM_SW_STRIDE to strideMem, PARAM_SW_POINTER to Pointer(address)))
                return lib.mpv_render_context_render(rctx, p)
            }

            override fun free() {
                if (freed) return
                freed = true
                lib.mpv_render_context_set_update_callback(rctx, null, null)
                lib.mpv_render_context_free(rctx)
            }
        }
    }

    private fun renderParams(params: List<Pair<Int, Pointer>>): Memory {
        val entry = 16L
        val m = Memory(entry * (params.size + 1))
        m.clear()
        params.forEachIndexed { i, (type, data) ->
            m.setInt(i * entry, type)
            m.setPointer(i * entry + 8, data)
        }
        return m
    }

    override fun errorString(code: Int): String = runCatching { lib.mpv_error_string(code) }.getOrDefault("error $code")

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        lib.mpv_terminate_destroy(ctx)
    }

    companion object {
        // render.h's mpv_render_param_type values.
        private const val PARAM_API_TYPE = 1
        private const val PARAM_SW_SIZE = 17
        private const val PARAM_SW_FORMAT = 18
        private const val PARAM_SW_STRIDE = 19
        private const val PARAM_SW_POINTER = 20
        private const val UPDATE_FRAME = 1L

        /** A new mpv context, or null if libmpv couldn't make one. */
        fun create(loaded: LibMpvLoader.Result.Loaded): JnaMpvApi? = loaded.lib.mpv_create()?.let { JnaMpvApi(loaded.lib, it) }
    }
}
