package dev.nitrate.mobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/** How mpv shows the picture on the desktop. */
enum class VideoOutput(val label: String, val detail: String) {
    /** mpv draws into a native window inside Nitrate's (the GPU end to end: HDR, any size); Windows' default. */
    WINDOW("In its own window", "Full GPU path, HDR on HDR screens"),

    /** mpv draws each frame into memory and Nitrate shows it (works everywhere, costs CPU at 4K, no HDR output). */
    SOFTWARE("Drawn by Nitrate", "Works everywhere; heavier at 4K, SDR only"),
}

/**
 * The desktop player's own choices, kept on this PC only (playback settings are per device and
 * never synced): which codecs go to a receiver untouched (off by default), how the picture is
 * shown, and hardware decoding. A small properties file in the app's data folder.
 */
class DesktopPlayerSettings private constructor(private val file: File) {
    data class Values(
        /** Codecs bitstreamed to a receiver (mpv's audio-spdif): a subset of DesktopPlayback.BITSTREAM_CODECS. */
        val bitstream: Set<String> = emptySet(),
        val output: VideoOutput = defaultOutput(),
        /** GPU decoding where the PC has it (mpv's hwdec=auto-safe); off decodes on the CPU. */
        val hardwareDecoding: Boolean = true,
        /** Volume 0–130 (mpv's scale), kept between plays. */
        val volume: Int = 100,
    )

    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values

    val bitstream: Set<String> get() = _values.value.bitstream

    fun update(change: (Values) -> Values) {
        val v = change(_values.value)
        _values.value = v
        runCatching {
            file.parentFile?.mkdirs()
            Properties().apply {
                setProperty("bitstream", v.bitstream.sorted().joinToString(","))
                setProperty("output", v.output.name)
                setProperty("hwdec", v.hardwareDecoding.toString())
                setProperty("volume", v.volume.toString())
            }.let { p -> file.outputStream().use { p.store(it, "Nitrate desktop player (this PC only)") } }
        }
    }

    private fun load(): Values {
        val p = Properties()
        runCatching { file.inputStream().use { p.load(it) } }
        val d = Values()
        return Values(
            bitstream = p.getProperty("bitstream")?.split(',')?.map { it.trim() }?.filter { it in dev.nitrate.core.playback.DesktopPlayback.BITSTREAM_CODECS }?.toSet() ?: d.bitstream,
            output = p.getProperty("output")?.let { n -> VideoOutput.entries.firstOrNull { it.name == n } } ?: d.output,
            hardwareDecoding = p.getProperty("hwdec")?.toBooleanStrictOrNull() ?: d.hardwareDecoding,
            volume = p.getProperty("volume")?.toIntOrNull()?.coerceIn(0, 130) ?: d.volume,
        )
    }

    companion object {
        private val opened = mutableMapOf<String, DesktopPlayerSettings>()

        /** The settings kept in [dataDir] (one instance per folder per run). */
        @Synchronized
        fun shared(dataDir: String): DesktopPlayerSettings = opened.getOrPut(dataDir) { DesktopPlayerSettings(File(dataDir, "player.properties")) }

        /** Windows embeds mpv's window; elsewhere Nitrate draws the frames (no native window handle to share reliably). */
        fun defaultOutput(): VideoOutput =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) VideoOutput.WINDOW else VideoOutput.SOFTWARE
    }
}
