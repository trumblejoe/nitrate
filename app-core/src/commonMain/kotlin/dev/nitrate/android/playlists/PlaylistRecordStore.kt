package dev.nitrate.android.playlists

import dev.nitrate.core.playlists.PlaylistRecord

/** Where each playlist's own record is kept (the user's synced settings; in memory for tests). */
interface PlaylistRecordStore {
    suspend fun get(playlistId: String): PlaylistRecord?
    suspend fun put(record: PlaylistRecord)
    suspend fun all(): List<PlaylistRecord>

    /** Kept only while the app runs. */
    class InMemory : PlaylistRecordStore {
        private val records = HashMap<String, PlaylistRecord>()
        override suspend fun get(playlistId: String) = records[playlistId]
        override suspend fun put(record: PlaylistRecord) { records[record.playlistId] = record }
        override suspend fun all() = records.values.toList()
    }
}
