package dev.nitrate.android.playlists

import dev.nitrate.core.playlists.PlaylistRecord

/**
 * Where each playlist's own record is kept: the Companion when the server has it (it moves records on
 * as sittings stop, whichever app played them), else the user's synced settings; in memory for tests.
 */
interface PlaylistRecordStore {
    suspend fun get(playlistId: String): PlaylistRecord?

    /** Keeps [record] and gives back what's kept; [onlyIfNew]: only when there's none yet (a seed never replaces one kept). */
    suspend fun put(record: PlaylistRecord, onlyIfNew: Boolean = false): PlaylistRecord

    suspend fun all(): List<PlaylistRecord>

    /** Kept only while the app runs. */
    class InMemory : PlaylistRecordStore {
        private val records = HashMap<String, PlaylistRecord>()
        override suspend fun get(playlistId: String) = records[playlistId]
        override suspend fun put(record: PlaylistRecord, onlyIfNew: Boolean): PlaylistRecord {
            if (onlyIfNew) records[record.playlistId]?.let { return it }
            records[record.playlistId] = record
            return record
        }
        override suspend fun all() = records.values.toList()
    }
}
