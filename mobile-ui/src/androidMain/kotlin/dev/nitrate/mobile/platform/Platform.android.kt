package dev.nitrate.mobile.platform

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nitrate.core.playback.SoundOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.nitrate.android.writing.asTransferDocs
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

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

actual fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

@Composable
actual fun rememberPlatformActions(): PlatformActions {
    val context = LocalContext.current
    return remember(context) {
        PlatformActions(
            openUrl = { url ->
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.isSuccess
            },
            copy = { text, label ->
                context.getSystemService(android.content.ClipboardManager::class.java)?.let { clipboard ->
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                    // Android 13+ shows its own confirmation.
                    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            toast = { message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() },
            adjustVolume = { command ->
                val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val direction = when (command) {
                    "VolumeUp" -> android.media.AudioManager.ADJUST_RAISE
                    "VolumeDown" -> android.media.AudioManager.ADJUST_LOWER
                    "Mute" -> android.media.AudioManager.ADJUST_MUTE
                    "Unmute" -> android.media.AudioManager.ADJUST_UNMUTE
                    else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                }
                audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI)
            },
            launch = { what -> (what as? android.content.Intent)?.let { runCatching { context.startActivity(it) } } },
        )
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun rememberNotificationPermission(onGranted: () -> Unit): (proceed: () -> Unit) -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted() }
    return { proceed ->
        val needsAsk = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsAsk) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else proceed()
    }
}

@Composable
actual fun rememberImagePicker(onPicked: (bytes: ByteArray, mime: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
        picked(bytes, mime)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberSoundOutput(): State<SoundOutput?> {
    val context = LocalContext.current
    return remember(context) { dev.nitrate.android.playback.SoundOutputs.current(context) }.collectAsState()
}

@Composable
actual fun rememberLicenceText(name: String): String? {
    val context = LocalContext.current
    return remember(name) { runCatching { context.assets.open("licenses/$name").bufferedReader().readText() }.getOrNull() }
}

actual fun fullScreenDialog() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

@Composable
actual fun rememberImagePixels(model: Any?, size: Int): State<Pair<IntArray, Int>?> {
    val context = LocalContext.current
    return produceState<Pair<IntArray, Int>?>(null, model) {
        if (model == null) return@produceState
        val request = ImageRequest.Builder(context).data(model).size(size).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap().let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) } to bitmap.width
        }
    }
}

@Composable
actual fun rememberScriptTransfer(store: dev.nitrate.android.writing.WritingStore): ScriptTransfer? {
    val context = LocalContext.current
    return remember(store) {
        val server = dev.nitrate.core.writing.TransferServer(store.asTransferDocs())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
        object : ScriptTransfer {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(TransferState()).also { out ->
                scope.launch { server.state.collect { s -> out.value = TransferState(s.running, s.address, s.pin, s.note) } }
            }
            override fun start() = server.start(dev.nitrate.android.writing.lanAddress(context))
            override fun stop(note: String?) = server.stop(note)
        }
    }
}

actual fun toImageBitmap(image: coil3.Image): androidx.compose.ui.graphics.ImageBitmap = image.toBitmap().asImageBitmap()

actual fun softwareImage(builder: ImageRequest.Builder): ImageRequest.Builder = builder.allowHardware(false)
