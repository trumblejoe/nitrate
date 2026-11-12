package dev.nitrate.tv.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * The account's libraries for the menu: the last list seen straight away (so the menu is whole
 * the moment it opens), then the server's current one, which is remembered for next time.
 */
@Composable
fun rememberLibraries(container: AppContainer): List<MediaItem> {
    val context = LocalContext.current
    val user = container.server.userId
    val key = "libraries:${container.server.serverUrl}|$user"
    val prefs = context.getSharedPreferences("menu", Context.MODE_PRIVATE)
    val libraries by produceState(initialValue = read(prefs.getString(key, null)), key) {
        val fresh = runCatching { container.server.views() }.getOrNull() ?: return@produceState
        value = fresh
        withContext(Dispatchers.IO) {
            prefs.edit().putString(key, json.encodeToString(ListSerializer(MediaItem.serializer()), fresh)).apply()
        }
    }
    return libraries
}

private fun read(text: String?): List<MediaItem> =
    text?.let { runCatching { json.decodeFromString(ListSerializer(MediaItem.serializer()), it) }.getOrNull() }.orEmpty()
