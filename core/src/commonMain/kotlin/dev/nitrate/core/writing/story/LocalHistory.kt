package dev.nitrate.core.writing.story

import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.core.writing.SharedScriptSession
import dev.nitrate.core.writing.TextOperation
import kotlinx.serialization.Serializable

/**
 * One change kept on the device, for scripts that aren't synced with a server: the same history
 * a server keeps (who, when, what), so activity, versions and "who wrote this" work everywhere.
 */
@Serializable
data class LocalEdit(
    val version: Long,
    val userId: String,
    val userName: String,
    val atMs: Long,
    /** A text change in its wire form (JSON), or null. */
    val ops: String? = null,
    val records: List<RecordChange> = emptyList(),
    val sceneId: String? = null,
) {
    fun toScriptEdit() = ScriptEdit(
        version = version, editId = "local-$version", userId = userId, userName = userName,
        op = ops?.let(SharedScriptSession::decode), records = records, atMs = atMs, sceneId = sceneId,
    )

    companion object {
        /** A text change from [before] to [after] (null when nothing changed). */
        fun text(version: Long, userId: String, userName: String, atMs: Long, before: String, after: String): LocalEdit? {
            val op = TextOperation.diff(before, after).takeIf { !it.isNoop } ?: return null
            return LocalEdit(version, userId, userName, atMs, SharedScriptSession.encode(op), sceneId = History.sceneAt(after, op))
        }
    }
}

/** Where a device's history of a script starts: the text and story data then. */
@Serializable
data class LocalHistoryStart(val text: String, val records: Map<String, StoryRecord> = emptyMap(), val atMs: Long = 0, val authorId: String = "")
