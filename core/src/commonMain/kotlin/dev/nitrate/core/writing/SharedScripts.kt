package dev.nitrate.core.writing

/** Someone a script can be shared with. */
data class UserRef(val id: String, val name: String)

/** A shared script without its text. */
data class SharedScriptInfo(
    val id: String,
    val title: String,
    val owner: UserRef,
    /** The people the owner invited (not the owner). */
    val members: List<UserRef>,
    val version: Long,
    val updatedAtMs: Long,
) {
    fun isOwner(userId: String?) = owner.id == userId
}

/** A shared script's text and story data at a version. */
data class SharedScriptDoc(
    val info: SharedScriptInfo,
    val text: String,
    val version: Long,
    val records: List<dev.nitrate.core.writing.story.StoryRecord> = emptyList(),
)

/** One change someone made, as the server ordered it: to the text, the story data, or both. */
data class ScriptEdit(
    val version: Long,
    val editId: String,
    val userId: String,
    val userName: String,
    val op: TextOperation?,
    val records: List<dev.nitrate.core.writing.story.RecordChange> = emptyList(),
    val atMs: Long = 0,
    /** For a text change: the scene it was in. */
    val sceneId: String? = null,
)

/** Where someone else's cursor is (an offset into the text at [version]). */
data class ScriptPresence(val userId: String, val userName: String, val cursor: Int, val version: Long)

/** Changes after a version, who's here, and the server's version and stamp now. */
data class ScriptEditsPage(val edits: List<ScriptEdit>, val version: Long, val presence: List<ScriptPresence>, val stamp: Long)

/** Whether a change was taken (it made [version]), or was made on an old version and must be rebased. */
data class ScriptEditResult(val accepted: Boolean, val version: Long)

/** The script is gone for this user: deleted by its owner, or they were removed from it. */
class ScriptUnavailable : Exception("This script isn't shared with you any more")

/** The changes asked for are no longer kept on the server: load the script again. */
class ScriptTooFarBehind : Exception("Too far behind the shared script")

/** Only the owner may do that. */
class NotScriptOwner(message: String) : Exception(message)
