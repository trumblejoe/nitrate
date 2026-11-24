package dev.nitrate.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.window.DialogProperties
import coil3.toBitmap
import dev.nitrate.core.playback.SoundOutput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// The desktop (Windows first; Linux and macOS run the same code): AWT/Swing and java.* stand in
// for what Android and iOS give the shared screens.

actual fun formatDate(ms: Long, style: DateStyle): String = DateFormat.getDateInstance(
    when (style) {
        DateStyle.SHORT -> DateFormat.SHORT
        DateStyle.MEDIUM -> DateFormat.MEDIUM
        DateStyle.LONG -> DateFormat.LONG
    },
).format(Date(ms))

actual fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

actual fun formatDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

actual fun formatPattern(ms: Long, pattern: String): String = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(Date(ms))

actual fun timeZoneOffsetMs(): Long = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

actual fun timeZoneOffsetMsAt(ms: Long): Long = java.util.TimeZone.getDefault().getOffset(ms).toLong()

actual fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

/** Messages for the window's snackbar (the desktop has no toasts); the app's window shows them. */
object DesktopToasts {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun show(message: String) {
        _messages.tryEmit(message)
    }
}

/** Opens [url] in the default browser (or [file] in its app); false when the system can't. */
fun openInBrowser(url: String): Boolean = runCatching {
    val desktop = java.awt.Desktop.getDesktop()
    if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) return false
    desktop.browse(java.net.URI(url))
}.isSuccess

@Composable
actual fun rememberPlatformActions(): PlatformActions = remember {
    PlatformActions(
        openUrl = ::openInBrowser,
        copy = { text, _ ->
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                DesktopToasts.show("Copied")
            }
        },
        toast = DesktopToasts::show,
        // The PC's own volume is the user's; Nitrate's player has its own (mpv's volume).
        adjustVolume = { command -> DesktopVolume.handler?.invoke(command) },
    )
}

/** Volume commands from other apps (the phone as a remote), passed to the player while one plays. */
object DesktopVolume {
    @Volatile var handler: ((String) -> Unit)? = null
}

/**
 * Back on the desktop is Esc (and the mouse's back button, which the window turns into Esc):
 * Compose's own back navigation, so the innermost handler wins and the screens' navigation pops
 * when none takes it.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    androidx.compose.ui.backhandler.BackHandler(enabled, onBack)
}

// The desktop needs no permission to show notifications (the tray's, while Nitrate runs).
@Composable
actual fun rememberNotificationPermission(onGranted: () -> Unit): (proceed: () -> Unit) -> Unit = remember { { proceed -> proceed() } }

/** The window the app runs in (set by :desktop): file dialogs open over it, the player makes it full screen. */
object DesktopWindow {
    @Volatile var frame: java.awt.Frame? = null

    /** Full screen (F11, or F and double-click in the player); the window follows it. */
    val fullscreen = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun toggleFullscreen() {
        fullscreen.value = !fullscreen.value
    }
}

/** Asks for a file with the system's own dialog (AWT's FileDialog: the native one on Windows and macOS). */
internal fun chooseFile(title: String, save: Boolean, suggested: String? = null, filter: ((String) -> Boolean)? = null): java.io.File? {
    val dialog = java.awt.FileDialog(DesktopWindow.frame, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    suggested?.let { dialog.file = it }
    if (filter != null) dialog.setFilenameFilter { _, name -> filter(name) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return java.io.File(dialog.directory, name)
}

private val IMAGE_TYPES = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif")

@Composable
actual fun rememberImagePicker(onPicked: (bytes: ByteArray, mime: String) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    return remember {
        {
            val file = chooseFile("Choose a picture", save = false) { it.substringAfterLast('.').lowercase() in IMAGE_TYPES }
            if (file != null) runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                picked.value(bytes, IMAGE_TYPES[file.extension.lowercase()] ?: "image/jpeg")
            }
        }
    }
}

@Composable
actual fun rememberFilePickers(onOpened: (ByteArray) -> Unit, onResult: (String) -> Unit): FilePickers {
    val opened = rememberUpdatedState(onOpened)
    val result = rememberUpdatedState(onResult)
    return remember {
        FilePickers(
            save = { fileName, _, bytes ->
                val file = chooseFile("Save", save = true, suggested = fileName)
                if (file != null) runCatching { file.writeBytes(bytes) }
                    .onSuccess { result.value("Exported") }
                    .onFailure { result.value("Export failed") }
            },
            open = {
                val file = chooseFile("Open", save = false)
                if (file != null) runCatching { file.readBytes() }.getOrNull()?.let(opened.value) ?: run { if (file != null) result.value("Couldn't read that file") }
            },
        )
    }
}

// The speakers question is Android's (its upmixer); mpv plays what Windows' sound settings ask for.
@Composable
actual fun rememberSoundOutput(): State<SoundOutput?> = remember { mutableStateOf(null) }

@Composable
actual fun rememberLicenceText(name: String): String? = remember(name) {
    // Bundled by :desktop from core-android's licences folder, as resources under licenses/.
    runCatching {
        (Thread.currentThread().contextClassLoader ?: DesktopToasts::class.java.classLoader)
            .getResourceAsStream("licenses/$name")?.use { it.readBytes().decodeToString() }
    }.getOrNull()
}

actual fun fullScreenDialog() = DialogProperties(usePlatformDefaultWidth = false)

/** This PC's IPv4 address on the local network, for the transfer page's link. */
internal fun lanAddress(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/** The transfer page, served from this PC (the same small server Android uses). */
@Composable
actual fun rememberScriptTransfer(store: dev.nitrate.android.writing.WritingStore): ScriptTransfer? = remember(store) {
    val server = dev.nitrate.core.writing.TransferServer(object : dev.nitrate.core.writing.TransferDocs {
        override fun list(): List<dev.nitrate.core.writing.WritingDoc> = store.docs.value

        // Only ids of existing scripts: the id comes from a URL, so it must never reach other files.
        override suspend fun read(id: String): String? = if (store.docs.value.any { it.id == id }) store.read(id) else null

        override suspend fun create(text: String): String = store.create(text)
    })
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    object : ScriptTransfer {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(TransferState()).also { out ->
            scope.launch { server.state.collect { s -> out.value = TransferState(s.running, s.address, s.pin, s.note) } }
        }
        override fun start() = server.start(lanAddress())
        override fun stop(note: String?) = server.stop(note)
    }
}

actual fun toImageBitmap(image: coil3.Image): androidx.compose.ui.graphics.ImageBitmap = image.toBitmap().asComposeImageBitmap()

actual fun softwareImage(builder: coil3.request.ImageRequest.Builder): coil3.request.ImageRequest.Builder = builder

actual val canTestPlay: Boolean = false

// No Chromecasts from the desktop (Google's Cast SDK is Android's and iOS's).
@Composable
actual fun rememberCast(): CastControl? = null

// Windows' media overlay for another device's playback comes later.
@Composable
actual fun rememberRemoteFollower(): (sessionId: String) -> Unit = remember { { _ -> } }
