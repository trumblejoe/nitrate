package dev.nitrate.core.admin

/**
 * Metadata editors (Companion feature [FEATURE]): accounts that aren't server admins but may edit
 * titles' metadata (details, field locks, external IDs, Identify, refresh, images) in some or all
 * libraries. Their changes go through the Companion, which checks each one and keeps a log admins
 * can read and undo (`docs/features/admin.md`).
 */
object MetadataEditors {
    /** The Companion feature that offers metadata editors. */
    const val FEATURE = "metadata"

    /**
     * A library chip on the Users page: from "All" it narrows to just [libraryId]; otherwise it adds
     * or takes away [libraryId], and taking away the last one goes back to all of them.
     */
    fun toggle(scope: MetadataEditorScope, libraryId: String): MetadataEditorScope = when {
        scope.allLibraries -> scope.copy(allLibraries = false, libraryIds = listOf(libraryId))
        scope.libraryIds.any { it.equals(libraryId, ignoreCase = true) } -> {
            val rest = scope.libraryIds.filterNot { it.equals(libraryId, ignoreCase = true) }
            if (rest.isEmpty()) scope.copy(allLibraries = true, libraryIds = emptyList()) else scope.copy(libraryIds = rest)
        }
        else -> scope.copy(libraryIds = scope.libraryIds + libraryId)
    }
}

/** What an account may edit as a metadata editor: nothing, every library, or [libraryIds]. */
data class MetadataEditorScope(
    val isEditor: Boolean,
    val allLibraries: Boolean = false,
    /** Dashed lowercase ids, as everywhere in Nitrate. */
    val libraryIds: List<String> = emptyList(),
) {
    /** Whether titles in [libraryId] are in scope. */
    fun covers(libraryId: String?): Boolean =
        isEditor && (allLibraries || (libraryId != null && libraryIds.any { it.equals(libraryId, ignoreCase = true) }))

    companion object {
        val NONE = MetadataEditorScope(isEditor = false)
    }
}

/** One account with the metadata editor role, as admins see it. */
data class MetadataEditor(
    val userId: String,
    val userName: String,
    val allLibraries: Boolean,
    val libraryIds: List<String>,
) {
    val scope: MetadataEditorScope get() = MetadataEditorScope(true, allLibraries, libraryIds)
}

/** What a logged change was. */
enum class MetadataEditKind(val wire: String, val label: String) {
    DETAILS("details", "Details"),
    MATCH("match", "Identify"),
    REFRESH("refresh", "Refresh"),
    IMAGE("image", "Image"),
    ;

    companion object {
        fun of(wire: String?): MetadataEditKind = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: DETAILS
    }
}

/** One change in the Companion's log of metadata edits (newest first). */
data class MetadataEdit(
    val id: String,
    val atMs: Long,
    val userId: String,
    val userName: String,
    val itemId: String,
    val itemName: String,
    val kind: MetadataEditKind,
    /** What changed, in the server's words ("Overview, Genres"). */
    val summary: String,
    /** Details edits can be put back as they were. */
    val canUndo: Boolean,
)

/**
 * The Companion turned a metadata change down; [message] is the server's own reason ("You can't
 * edit this title", "Too many changes: wait a minute"), shown as it is.
 */
class MetadataEditRefused(message: String, val status: Int) : Exception(message) {
    companion object {
        /** The words shown when the server gave none. */
        fun defaultMessage(status: Int): String = when (status) {
            401 -> "Sign in again to edit this title"
            403 -> "You can't edit this title"
            404 -> "This title isn't on the server any more"
            413 -> "That image is too big (20 MB at most)"
            415 -> "Only JPEG, PNG or WebP images can be added"
            429 -> "Too many changes: wait a minute"
            else -> "The server didn't take that change"
        }
    }
}
