package dev.nitrate.android.storage

import android.content.Context
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The last library list seen per account, so menus and the Libraries tab are whole the moment
 * they open; [refresh] fetches the server's current list and remembers it.
 */
class LibraryCache(context: Context) {
    private val prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MediaItem.serializer())

    private fun key(server: MediaServer) = "${server.serverUrl}|${server.userId}"

    fun cached(server: MediaServer): List<MediaItem> =
        prefs.getString(key(server), null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    suspend fun refresh(server: MediaServer): List<MediaItem> {
        val fresh = server.views()
        withContext(Dispatchers.IO) { prefs.edit().putString(key(server), json.encodeToString(serializer, fresh)).apply() }
        return fresh
    }
}
