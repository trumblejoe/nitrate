package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/**
 * A page's group marks: a page entered from a group (its Watching together card, or the Watch
 * groups page) has a mark of its own for that group beside the user's own Watched, on the title's
 * parts and in its lists. The two never touch: the group's record is the Companion's, the user's
 * own is Jellyfin's. Marking a part watched for the group asks once ("Just this group / All
 * groups where it's next") only when it's also the very next part in other groups of the user's;
 * anything else (unwatched, a part further on) is just this group.
 */
object GroupMarks {
    /** Kinds a group mark goes on: the parts of a title (an episode, a film, a video), never a show, season or list itself. */
    private val PARTS = setOf(ItemKind.EPISODE, ItemKind.MOVIE, ItemKind.VIDEO, ItemKind.MUSIC_VIDEO)

    /**
     * The group whose mark [item] shows on a page entered from group [groupId] (null: entered any
     * other way): that group, when [item] is a part of one of its titles; else null (no group mark).
     */
    fun groupFor(groupId: String?, item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): WatchGroup? {
        if (groupId == null || item.kind !in PARTS) return null
        val g = info?.groups?.firstOrNull { it.id == groupId } ?: return null
        val title = TogetherRow.titleOf(item, standings) ?: return null
        return g.takeIf { it.holds(title) }
    }

    /** Whether [item] is watched in [group]'s own record. */
    fun watched(group: WatchGroup, item: MediaItem): Boolean = group.ownRecord?.contains(item.id) == true

    /** The mark's words: "Watched with Tommy & Suzy" / "Not watched with Tommy & Suzy" (said by screen readers too). */
    fun label(group: WatchGroup, item: MediaItem): String = (if (watched(group, item)) "Watched with " else "Not watched with ") + WithChip.nameOf(group)

    /** Its short caption under the button: "Tommy & Suzy". */
    fun caption(group: WatchGroup): String = WithChip.nameOf(group)

    /**
     * Marking [item] watched for [group]: the other groups it's also the very next part in (then
     * the app asks "Just this group / All groups where it's next"); empty: just this group, no question.
     * Marking unwatched never asks.
     */
    fun alsoNext(item: MediaItem, group: WatchGroup, watched: Boolean, info: PartnersInfo?, standings: Map<String, KeepUp>): List<WatchGroup> =
        if (!watched) emptyList() else WatchPartners.alsoNextIn(item, group.id, info, standings)

    /** "Also mark it watched for Kim, where it's next too?" */
    fun question(others: List<WatchGroup>): String = "Also mark it watched for " + WatchPartners.names(others.map { WithChip.nameOf(it) }) + ", where it's next too?"
}
