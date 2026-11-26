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
 * starts ([choosable]: in one of the user's watch groups, with the Companion's group chat), the
 * groups' threads on it (older ones to replay, hearts, the creator's Start a new thread), asked for
 * only when the choice is opened ([threads]), and what it starts on ([chatOn]: the user's setting).
 */
class DetailsChat(
    val choosable: (dev.nitrate.core.model.MediaItem) -> Boolean,
    val threads: suspend (dev.nitrate.core.model.MediaItem) -> List<dev.nitrate.core.companion.ThreadGroup>,
    val chatOn: (dev.nitrate.core.pages.DisplayPrefs) -> Boolean,
    /** Makes a thread a favourite of the user's, or not (never deleted by the server's time rules). */
    val favourite: suspend (dev.nitrate.core.model.MediaItem, String, Boolean) -> Unit = { _, _, _ -> },
    /** The group's creator starts a new thread on the title (a rewatch). */
    val startThread: suspend (dev.nitrate.core.model.MediaItem, String) -> Unit = { _, _ -> },
)
