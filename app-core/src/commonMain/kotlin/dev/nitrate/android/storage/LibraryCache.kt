package dev.nitrate.android.storage

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The last library list seen per account, so menus and the Libraries tab are whole the moment
 * they open; [refresh] fetches the server's current list and remembers it.
 */
class LibraryCache(private val prefs: KeyValues) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MediaItem.serializer())

    private fun key(server: MediaServer) = "${server.serverUrl}|${server.userId}"

    fun cached(server: MediaServer): List<MediaItem> =
        prefs.get(key(server))?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    suspend fun refresh(server: MediaServer): List<MediaItem> {
        val fresh = server.views()
        withContext(Dispatchers.IO) { prefs.put(key(server), json.encodeToString(serializer, fresh)) }
        return fresh
    }
}
