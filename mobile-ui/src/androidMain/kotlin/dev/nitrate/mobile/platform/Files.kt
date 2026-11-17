package dev.nitrate.mobile.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Saving and opening files through the system picker. iOS uses its document picker instead. */
class FilePickers(val save: (fileName: String, mime: String, bytes: ByteArray) -> Unit, val open: () -> Unit)

@Composable
fun rememberFilePickers(onOpened: (ByteArray) -> Unit, onResult: (String) -> Unit): FilePickers {
    val context = LocalContext.current
    val opened by rememberUpdatedState(onOpened)
    val result by rememberUpdatedState(onResult)
    var pendingBytes: ByteArray? = null
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> write(context, uri, pendingBytes, result) }
    val saveBinary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> write(context, uri, pendingBytes, result) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?.let(opened) ?: result("Couldn't read that file")
        }
    }
    return FilePickers(
        save = { name, mime, bytes ->
            pendingBytes = bytes
            if (mime.startsWith("text/")) saveText.launch(name) else saveBinary.launch(name)
        },
        open = { open.launch(arrayOf("text/*", "application/octet-stream", "application/zip")) },
    )
}

private fun write(context: android.content.Context, uri: Uri?, bytes: ByteArray?, onResult: (String) -> Unit) {
    if (uri == null || bytes == null) return
    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        .onSuccess { onResult("Exported") }
        .onFailure { onResult("Export failed: ${it.message}") }
}
