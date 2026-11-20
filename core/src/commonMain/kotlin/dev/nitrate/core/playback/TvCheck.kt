package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/** A TV and receiver check clip from the Companion plugin (`/Nitrate/TestClips`). */
@Serializable
data class CheckClip(
    val id: String,
    val title: String,
    /** "Picture" (does the TV switch to HDR?) or "Sound" (does the receiver show the format?). */
    val kind: String,
    /** "HDR10", "HLG", "DOVI" for pictures; "ac3", "eac3", "truehd", "dts", "pcm" for sound. */
    val format: String,
    val lookFor: String,
    val contentType: String = "video/mp4",
    val size: Long = 0,
) {
    val isPicture: Boolean get() = kind.equals("Picture", ignoreCase = true)
}

/** What the user saw for one check clip, and what the device measured while it played. */
@Serializable
data class SeenClip(
    val answer: Answer,
    val format: String,
    val title: String,
    val atMs: Long,
    /** Sound: whether it went out untouched (bitstream) rather than decoded here. */
    val passthrough: Boolean? = null,
    /** Picture: the HDR the display reported at the time (Android), to tell "TV shows it though Android says no". */
    val displayHdr: Set<String>? = null,
)

/**
 * The TV and receiver check (Settings → Tools): the plugin's clips played one by one, the user
 * saying what the TV or receiver showed. Kept in [DeviceCheck.seen]; quoted by How it plays and
 * turned into settings to change ([advice]).
 */
object TvCheck {
    /** Settings to change after the check, and advice for what no setting fixes. */
    fun advice(seen: Map<String, SeenClip>, caps: DeviceCapabilities, settings: PlaybackSettings): List<TestAdvice> = buildList {
        val pictures = seen.values.filter { it.format in setOf("HDR10", "HLG", "DOVI") }
        for (s in pictures.distinctBy { it.format }.sortedBy { it.format }) {
            val id = PlaybackSettings.hdrId(s.format)
            val name = if (s.format == "DOVI") "Dolby Vision" else s.format
            when {
                s.answer == Answer.YES && s.format !in caps.hdr && settings.overrides[id] != Override.ALWAYS ->
                    add(TestAdvice("The TV showed $name, but Android doesn't report it on this display: let the server send $name as it is.", SettingsChange.ForceAllow(id), "Set $name to Always"))
                s.answer == Answer.YES && settings.overrides[id] == Override.NEVER ->
                    add(TestAdvice("The TV showed $name, but it's set to Never here.", SettingsChange.ClearOverride(id), "Set $name back to Automatic"))
                s.answer == Answer.NO && settings.overrides[id] == Override.ALWAYS ->
                    add(TestAdvice("The TV didn't show $name, but it's set to Always here: the device has to convert it to SDR itself.", SettingsChange.ClearOverride(id), "Set $name back to Automatic"))
            }
        }
        if (pictures.isNotEmpty() && pictures.none { it.answer == Answer.YES } && pictures.any { it.answer == Answer.NO }) add(TestAdvice(
            "The TV showed no HDR at all. Check the receiver's 4K signal setting (Enhanced on Denon), the TV's enhanced/deep-colour mode for that input, an 18 Gbps cable, and the device's display settings (Dynamic range: Adaptive).",
        ))
        val sounds = seen.values.filter { it.format !in setOf("HDR10", "HLG", "DOVI") }
        sounds.filter { it.answer == Answer.NO && it.passthrough == true }.forEach {
            add(TestAdvice("${it.title} went out untouched, but the receiver didn't show it: check the receiver's input decode mode (Auto) and that it's the receiver, not the TV, playing the sound."))
        }
        sounds.filter { it.passthrough == false && it.format in AudioPath.BITSTREAM_CODECS }.forEach {
            add(TestAdvice("${it.title} was decoded here rather than sent untouched: the output doesn't take it (on a Fire TV: Display & Sounds → Audio → Surround Sound: Best available)."))
        }
    }

    /** What the check says about a file of [hdr] ("HDR10", "DOVI", "HLG") and [audioCodec], for How it plays. */
    /** The user said the TV showed one of the HDR or Dolby Vision clips. */
    fun showedHdr(seen: Map<String, SeenClip>): Boolean =
        seen.values.any { it.format in setOf("HDR10", "HLG", "DOVI") && it.answer == Answer.YES }

    fun quoted(seen: Map<String, SeenClip>, hdr: String?, audioCodec: String?): List<String> = buildList {
        hdr?.let { f -> seen.values.filter { it.format == f }.maxByOrNull { it.atMs }?.let { s ->
            add(when (s.answer) {
                Answer.YES -> "You saw the TV show ${s.title.substringBefore(" ·")} in the TV check"
                Answer.NO -> "The TV didn't show ${s.title.substringBefore(" ·")} in the TV check"
                Answer.UNSURE -> "${s.title.substringBefore(" ·")}: not sure in the TV check"
            })
        } }
        audioCodec?.let { c -> seen.values.filter { it.format == c }.maxByOrNull { it.atMs }?.let { s ->
            add(when (s.answer) {
                Answer.YES -> "The receiver showed ${s.title} in the check"
                Answer.NO -> "The receiver didn't show ${s.title} in the check"
                Answer.UNSURE -> "${s.title}: not sure what the receiver showed"
            })
        } }
    }
}
