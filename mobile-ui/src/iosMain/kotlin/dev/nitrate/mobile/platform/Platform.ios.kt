package dev.nitrate.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import dev.nitrate.core.playback.SoundOutput
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

private fun date(ms: Long) = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)

private fun styled(ms: Long, date: ULong, time: ULong) = NSDateFormatter().apply {
    dateStyle = date
    timeStyle = time
}.stringFromDate(date(ms))

actual fun formatDate(ms: Long, style: DateStyle): String = styled(
    ms,
    when (style) {
        DateStyle.SHORT -> NSDateFormatterShortStyle
        DateStyle.MEDIUM -> NSDateFormatterMediumStyle
        DateStyle.LONG -> NSDateFormatterLongStyle
    },
    NSDateFormatterNoStyle,
)

actual fun formatTime(ms: Long): String = styled(ms, NSDateFormatterNoStyle, NSDateFormatterShortStyle)

actual fun formatDateTime(ms: Long): String = styled(ms, NSDateFormatterMediumStyle, NSDateFormatterShortStyle)

actual fun formatPattern(ms: Long, pattern: String): String = NSDateFormatter().apply {
    // Like Android's SimpleDateFormat with the device's locale: the pattern's own order and words.
    setLocalizedDateFormatFromTemplate(pattern)
}.stringFromDate(date(ms))

actual fun timeZoneOffsetMs(): Long = NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate()) * 1000L

actual fun formatDecimal(value: Double, decimals: Int): String = NSNumberFormatter().apply {
    numberStyle = NSNumberFormatterDecimalStyle
    minimumFractionDigits = decimals.toULong()
    maximumFractionDigits = decimals.toULong()
}.stringFromNumber(NSNumber(value)) ?: value.toString()

@Composable
actual fun rememberPlatformActions(): PlatformActions = remember {
    PlatformActions(
        openUrl = { url ->
            NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null); true } ?: false
        },
        // iOS shows its own paste confirmation; there's no toast.
        copy = { text, _ -> UIPasteboard.generalPasteboard.string = text },
        toast = { },
        // The phone's own buttons set the volume; apps can't on iOS.
        adjustVolume = { },
    )
}

// iOS has no system Back for apps; screens have their own back buttons (swipe-back comes with navigation).
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

// Notifications aren't on iOS yet: nothing to ask for.
@Composable
actual fun rememberNotificationPermission(onGranted: () -> Unit): (proceed: () -> Unit) -> Unit = remember { { proceed -> proceed() } }

// The photo picker (PHPickerViewController) comes later.
@Composable
actual fun rememberImagePicker(onPicked: (bytes: ByteArray, mime: String) -> Unit): () -> Unit = remember { {} }

// The document picker (UIDocumentPickerViewController) comes later.
@Composable
actual fun rememberFilePickers(onOpened: (ByteArray) -> Unit, onResult: (String) -> Unit): FilePickers =
    remember { FilePickers(save = { _, _, _ -> onResult("Saving files isn't on iPhone yet") }, open = { onResult("Opening files isn't on iPhone yet") }) }

// Speaker layouts are Android's (AVAudioSession routes come later); stereo plays as stereo.
@Composable
actual fun rememberSoundOutput(): State<SoundOutput?> = remember { mutableStateOf(null) }

@Composable
actual fun rememberLicenceText(name: String): String? = null

actual fun fullScreenDialog() = DialogProperties(usePlatformDefaultWidth = false)

// Artwork-picked colours come later on iOS; screens use the app accent meanwhile.
@Composable
actual fun rememberImagePixels(model: Any?, size: Int): State<Pair<IntArray, Int>?> = remember { mutableStateOf(null) }

// A local web server for the transfer page comes later on iOS (Files and the share sheet first).
@Composable
actual fun rememberScriptTransfer(store: dev.nitrate.android.writing.WritingStore): ScriptTransfer? = null
