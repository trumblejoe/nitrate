package dev.nitrate.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import dev.nitrate.core.playback.SoundOutput

// What the shared screens need from the platform (Android: androidMain, iOS: iosMain).

/** How long a date reads ("12/31/26", "Dec 31, 2026", "December 31, 2026"), in the device's language. */
enum class DateStyle { SHORT, MEDIUM, LONG }

expect fun formatDate(ms: Long, style: DateStyle = DateStyle.MEDIUM): String

/** The time of day, short ("9:41 PM" or "21:41" as the device is set). */
expect fun formatTime(ms: Long): String

/** Date and time, medium date and short time. */
expect fun formatDateTime(ms: Long): String

/** [ms] formatted with a date pattern ("MMMM yyyy", "EEE d") in the device's language and time zone. */
expect fun formatPattern(ms: Long, pattern: String): String

/** The device's time zone's offset from UTC now, in milliseconds. */
expect fun timeZoneOffsetMs(): Long

/** The device's time zone's offset from UTC at the moment [ms] (summer time then, or not), in milliseconds. */
expect fun timeZoneOffsetMsAt(ms: Long): Long

/** [value] with [decimals] digits after the point, in the device's number format. */
expect fun formatDecimal(value: Double, decimals: Int): String

/** Things the screens ask the platform to do. */
class PlatformActions(
    /** Opens [url] in the browser; false if nothing could. */
    val openUrl: (url: String) -> Boolean,
    /** Copies [text] and confirms where the system doesn't. */
    val copy: (text: String, label: String) -> Unit,
    /** A short message at the bottom of the screen. */
    val toast: (message: String) -> Unit,
    /** Volume commands from other apps ("VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute"). */
    val adjustVolume: (command: String) -> Unit,
    /** Opens what the platform handed over to open (an Android Intent, such as a settings page). */
    val launch: (Any) -> Unit = {},
)

@Composable
expect fun rememberPlatformActions(): PlatformActions

/** Back (gesture or button) does [onBack] while [enabled]. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * Before switching notifications on: returns a function that asks for permission where the system
 * needs it (then [onGranted]), and otherwise just runs what it's given.
 */
@Composable
expect fun rememberNotificationPermission(onGranted: () -> Unit): (proceed: () -> Unit) -> Unit

/** Asks whether the system lets the app notify now (Android 13+'s permission; elsewhere always yes). */
@Composable
expect fun rememberNotificationsAllowed(): () -> Boolean

/** Opens the system's notification settings for the app (where a refused permission is turned back on). */
@Composable
expect fun rememberOpenNotificationSettings(): () -> Unit

/** Opens the system's photo picker; [onPicked] gets the image and its type. */
@Composable
expect fun rememberImagePicker(onPicked: (bytes: ByteArray, mime: String) -> Unit): () -> Unit

/** Saving and opening files through the system's picker. */
class FilePickers(val save: (fileName: String, mime: String, bytes: ByteArray) -> Unit, val open: () -> Unit)

@Composable
expect fun rememberFilePickers(onOpened: (ByteArray) -> Unit, onResult: (String) -> Unit): FilePickers

/** The sound output in use (speakers, a soundbar, headphones…), following changes. */
@Composable
expect fun rememberSoundOutput(): State<SoundOutput?>

/** A licence's full text, shipped with the app ([name] as in core OpenSource). */
@Composable
expect fun rememberLicenceText(name: String): String?

/** A dialog that covers the whole screen, edge to edge. */
expect fun fullScreenDialog(): DialogProperties

/** The pixels of [model]'s artwork at about [size] px (ARGB, row by row) and its width, or null. */
@Composable
fun rememberImagePixels(model: Any?, size: Int): State<Pair<IntArray, Int>?> {
    val context = coil3.compose.LocalPlatformContext.current
    return produceState<Pair<IntArray, Int>?>(null, model) {
        if (model == null) return@produceState
        val request = softwareImage(coil3.request.ImageRequest.Builder(context).data(model).size(size)).build()
        val result = coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult ?: return@produceState
        val bitmap = toImageBitmap(result.image)
        value = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) } to bitmap.width
    }
}

/** The accent colour of [model]'s artwork (a URL or file), or null until it's loaded or if it's grey. */
@Composable
fun rememberArtworkColor(model: Any?): State<Color?> {
    val pixels by rememberImagePixels(model, 48)
    return produceState<Color?>(null, pixels) {
        value = pixels?.let { (p, _) -> dev.nitrate.mobile.ui.ArtworkPalette.accent(p)?.let(::Color) }
    }
}

/**
 * The ink colour for a Cressida card: picked from [model]'s artwork (Smart), the app accent, or
 * the row's custom colour. Smart starts as [accent] and fades to its pick once the art is read.
 */
@Composable
fun rememberInkColor(model: Any?, display: dev.nitrate.core.rows.RowDisplay, accent: Color): Color {
    val smartOn = display.inkTint == dev.nitrate.core.rows.InkTint.SMART
    val pixels by rememberImagePixels(if (smartOn) model else null, 64)
    val smart = pixels?.let { (p, w) -> Color(dev.nitrate.core.rows.InkPalette.smart(p, w).toInt()) }
    val target = when (display.inkTint) {
        dev.nitrate.core.rows.InkTint.SMART -> smart ?: accent
        dev.nitrate.core.rows.InkTint.ACCENT -> accent
        dev.nitrate.core.rows.InkTint.CUSTOM -> Color(display.inkArgb.toInt())
    }
    return androidx.compose.animation.animateColorAsState(target, androidx.compose.animation.core.tween(600), label = "ink").value
}

/** The window's size in dp (what Android's LocalConfiguration.screenWidthDp/HeightDp gave). */
@Composable
fun windowSizeDp(): androidx.compose.ui.unit.DpSize {
    val size = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { androidx.compose.ui.unit.DpSize(size.width.toDp(), size.height.toDp()) }
}

/** The screenplay transfer page for a computer on the same Wi-Fi: where it is, and its PIN. */
data class TransferState(val running: Boolean = false, val address: String? = null, val pin: String? = null, val note: String? = null)

/** Serves the transfer page while switched on. */
interface ScriptTransfer {
    val state: kotlinx.coroutines.flow.StateFlow<TransferState>
    fun start()
    fun stop(note: String? = null)
}

/** The transfer page for [store]'s scripts, or null where the platform can't serve one (iOS for now). */
@Composable
expect fun rememberScriptTransfer(store: dev.nitrate.android.writing.WritingStore): ScriptTransfer?

/** A loaded Coil image as a Compose bitmap (for drawing parts of it, like trickplay tiles). */
expect fun toImageBitmap(image: coil3.Image): androidx.compose.ui.graphics.ImageBitmap

/** A request whose image can be read pixel by pixel (Android: not a hardware bitmap). */
expect fun softwareImage(builder: coil3.request.ImageRequest.Builder): coil3.request.ImageRequest.Builder

/** Whether the player here can run a test play for the playback overview (Android's; not Apple's yet). */
expect val canTestPlay: Boolean

/**
 * Nitrate's own EPUB reader, for platforms without Readium yet (iPhone and iPad): chapter by
 * chapter in the system's web view, dark and readable, saving where the reader is as they scroll.
 * On Android books open in Readium instead, so this is never shown there.
 */
@androidx.compose.runtime.Composable
expect fun EpubScreen(book: dev.nitrate.android.comics.OpenedBook.Epub, onExit: () -> Unit)

/** Whether this device can be on mobile data (phones, tablets): Settings shows "Quality on mobile data". */
expect val hasMobileData: Boolean
