package dev.nitrate.android.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The version (media source) chosen for a title on its details page, until the app closes; the
 * player plays that one. No choice means the server's best for this device (see resolvePlayback).
 */
object VersionChoices {
    private val chosen = MutableStateFlow(emptyMap<String, String>())

    operator fun get(itemId: String): String? = chosen.value[itemId]

    /** [versionId] null goes back to automatic. */
    fun set(itemId: String, versionId: String?) = chosen.update { if (versionId == null) it - itemId else it + (itemId to versionId) }
}

/** The chat picked on details pages, for the playback each starts (see [dev.nitrate.core.companion.ChatPicks]). */
val ChatChoices = dev.nitrate.core.companion.ChatPicks()

/**
 * A details page's Chat choice ("What it starts with"): whether it's offered for the part Play
 * starts ([choosable]: in one of the user's watch groups, with the Companion's group chat) and the
 * earlier chats to replay, asked for only when the choice is opened ([replayable], newest first).
 */
class DetailsChat(
    val choosable: (dev.nitrate.core.model.MediaItem) -> Boolean,
    val replayable: suspend (dev.nitrate.core.model.MediaItem) -> List<dev.nitrate.core.companion.NoteChat>,
)
