package dev.nitrate.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.toBitmap
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
import kotlinx.cinterop.addressOf
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.popoverPresentationController
import kotlinx.cinterop.usePinned

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

/** Asks iOS for permission to show alerts (it asks the person once; later answers come straight back). */
@Composable
actual fun rememberNotificationPermission(onGranted: () -> Unit): (proceed: () -> Unit) -> Unit {
    val granted = androidx.compose.runtime.rememberUpdatedState(onGranted)
    return remember {
        { _ ->
            platform.UserNotifications.UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                platform.UserNotifications.UNAuthorizationOptionAlert or platform.UserNotifications.UNAuthorizationOptionSound,
            ) { ok, _ -> if (ok) platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) { granted.value() } }
        }
    }
}

/** The screen on top, to present the system's pickers from. */
private fun topController(): platform.UIKit.UIViewController? {
    var c = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (c?.presentedViewController != null) c = c.presentedViewController
    return c
}

private fun platform.Foundation.NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): platform.Foundation.NSData = usePinned { pinned ->
    platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

/** The photo library picker (PHPicker, no permission needed); the picture comes as JPEG. */
@Composable
actual fun rememberImagePicker(onPicked: (bytes: ByteArray, mime: String) -> Unit): () -> Unit {
    val picked = androidx.compose.runtime.rememberUpdatedState(onPicked)
    // The picker holds its delegate weakly: kept here for as long as the screen is.
    val delegate = remember {
        object : platform.darwin.NSObject(), platform.PhotosUI.PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: platform.PhotosUI.PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                val result = didFinishPicking.firstOrNull() as? platform.PhotosUI.PHPickerResult ?: return
                result.itemProvider.loadDataRepresentationForTypeIdentifier("public.jpeg") { data, _ ->
                    val bytes = data?.toByteArray() ?: return@loadDataRepresentationForTypeIdentifier
                    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) { picked.value(bytes, "image/jpeg") }
                }
            }
        }
    }
    return remember {
        {
            val config = platform.PhotosUI.PHPickerConfiguration().apply {
                filter = platform.PhotosUI.PHPickerFilter.imagesFilter
                selectionLimit = 1
            }
            val picker = platform.PhotosUI.PHPickerViewController(configuration = config)
            picker.delegate = delegate
            topController()?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/** Saving goes through the share sheet (Save to Files, AirDrop…); opening through the Files picker. */
@Composable
actual fun rememberFilePickers(onOpened: (ByteArray) -> Unit, onResult: (String) -> Unit): FilePickers {
    val opened = androidx.compose.runtime.rememberUpdatedState(onOpened)
    val result = androidx.compose.runtime.rememberUpdatedState(onResult)
    val delegate = remember {
        object : platform.darwin.NSObject(), platform.UIKit.UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: platform.UIKit.UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                val data = platform.Foundation.NSData.create(contentsOfURL = url)
                if (data == null) result.value("Couldn't read that file") else opened.value(data.toByteArray())
            }
        }
    }
    return remember {
        FilePickers(
            save = { fileName, _, bytes ->
                val path = platform.Foundation.NSTemporaryDirectory() + fileName
                if (!bytes.toNSData().writeToFile(path, atomically = true)) {
                    result.value("Export failed")
                } else {
                    val share = platform.UIKit.UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
                    val top = topController()
                    // iPad shows the sheet as a popover, which needs somewhere to point.
                    share.popoverPresentationController?.sourceView = top?.view
                    top?.presentViewController(share, animated = true, completion = null)
                }
            },
            open = {
                val picker = platform.UIKit.UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(platform.UniformTypeIdentifiers.UTTypeData),
                    asCopy = true,
                )
                picker.delegate = delegate
                topController()?.presentViewController(picker, animated = true, completion = null)
            },
        )
    }
}

// Speaker layouts are Android's (AVAudioSession routes come later); stereo plays as stereo.
@Composable
actual fun rememberSoundOutput(): State<SoundOutput?> = remember { mutableStateOf(null) }

@Composable
actual fun rememberLicenceText(name: String): String? = null

actual fun fullScreenDialog() = DialogProperties(usePlatformDefaultWidth = false)


// A local web server for the transfer page comes later on iOS (Files and the share sheet first).
@Composable
actual fun rememberScriptTransfer(store: dev.nitrate.android.writing.WritingStore): ScriptTransfer? = null

actual fun toImageBitmap(image: coil3.Image): androidx.compose.ui.graphics.ImageBitmap = image.toBitmap().asComposeImageBitmap()

actual fun softwareImage(builder: coil3.request.ImageRequest.Builder): coil3.request.ImageRequest.Builder = builder
