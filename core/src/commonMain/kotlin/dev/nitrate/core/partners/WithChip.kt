package dev.nitrate.core.partners

import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.syncplay.SyncPlayGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "We're in the same room": the members of group [groupId] watching here with the user ([present], user ids). */
data class SameRoom(val groupId: String, val present: List<String>)

/**
 * What the player's "With…" chip says about a playback: who it counts with ([context], from
 * [WatchPartners.contextFor]), the user's [groups] on the title (what "Counts for" offers), the
 * others in this device's watch party ([party], names; [partyOn] even before anyone joins) and
 * who's in the room ([room]).
 */
data class WithChip(
    val context: PlayContext,
    val groups: List<WatchGroup>,
    val partyOn: Boolean = false,
    val party: List<String> = emptyList(),
    val room: SameRoom? = null,
) {
    /** Shown only for a title shared with a group, or while a watch party is on. */
    val shown: Boolean get() = groups.isNotEmpty() || partyOn

    /** The group "Counts for" has chosen now (null: just the user). */
    val group: WatchGroup? get() = context.groups.firstOrNull()

    /** The names of who's in the room besides the user (by the room's group's members). */
    val roomNames: List<String> get() {
        val r = room ?: return emptyList()
        val members = groups.firstOrNull { it.id == r.groupId }?.others.orEmpty()
        return r.present.mapNotNull { id -> members.firstOrNull { it.id == id }?.name }
    }

    /**
     * "Watch party with Tommy & Suzy", "In the room with Tommy", "With Tommy & Suzy · on your own",
     * "Just me".
     */
    val label: String get() = when {
        partyOn -> if (party.isEmpty()) "Watch party · waiting for others" else "Watch party with " + and(party)
        roomNames.isNotEmpty() -> "In the room with " + and(roomNames)
        context.justMe -> "Just me"
        else -> "With " + and(context.groups.flatMap { g -> g.others.map { it.name } }.distinct()) + " · on your own"
    }

    /**
     * More than one group would do and none was chosen: the chip says which it counts for at the
     * start ("Counting for: Tommy"), so the user can switch.
     */
    val ambiguous: Boolean get() = !partyOn && groups.size > 1 && context.reason in AUTO

    /** "Counting for: Tommy" (or "Counting for: just me"). */
    val counting: String get() = "Counting for: " + (group?.let { g -> and(g.others.map { it.name }) } ?: "just me")

    /** Who's here, for the panel: "You, Tommy and Suzy (watch party)", "You and Tommy, in the room", "Just you". */
    val who: String get() = when {
        partyOn && party.isNotEmpty() -> "You, " + WatchPartners.names(party) + " (watch party)"
        partyOn -> "Just you so far: invite someone to the party"
        roomNames.isNotEmpty() -> "You and " + WatchPartners.names(roomNames) + ", in the room"
        else -> "Just you, on this screen"
    }

    companion object {
        private val AUTO = setOf(PlayContext.Reason.NEXT, PlayContext.Reason.LAST_USED, PlayContext.Reason.NEAREST)

        /** A group's line in "Counts for": "Tommy & Suzy". */
        fun nameOf(g: WatchGroup): String = and(g.others.map { it.name }).ifEmpty { g.seriesName }

        /** "Tommy", "Tommy & Suzy", "Tommy, Suzy & Kim", "Tommy, Suzy, Kim & 2 more". */
        fun and(names: List<String>): String = when {
            names.isEmpty() -> ""
            names.size == 1 -> names[0]
            names.size <= 3 -> names.dropLast(1).joinToString(", ") + " & " + names.last()
            else -> names.take(3).joinToString(", ") + " & ${names.size - 3} more"
        }

        /**
         * The chip for [item]: its context ([WatchPartners.contextFor] with [with], the party's
         * names and [lastUsed]), and this device's watch party [party] (its participants by name,
         * the user's own [selfName] left out). Null for a title the user shares with no group, outside a party.
         */
        fun of(
            item: MediaItem?,
            info: PartnersInfo?,
            standings: Map<String, KeepUp>,
            party: SyncPlayGroup?,
            selfName: String?,
            with: WatchingWith? = null,
            lastUsed: String? = null,
            room: SameRoom? = null,
        ): WithChip? {
            if (item == null) return null
            val others = party?.participants.orEmpty().filter { !it.equals(selfName, ignoreCase = true) }.distinct()
            val context = WatchPartners.contextFor(item, info, standings, others, lastUsed, with)
            val groups = context.titleId?.let { info?.on(it) }.orEmpty()
            val chip = WithChip(context, groups, partyOn = party != null, party = others, room = room?.takeIf { r -> groups.any { it.id == r.groupId } && r.present.isNotEmpty() })
            return chip.takeIf { it.shown }
        }
    }
}

/**
 * Who one playback is watched with, for the player's "With…" chip: the user's choice ("Counts for":
 * a group or just them), who's in the room, and what was played to the end while they were, all from
 * data the app has already loaded (the groups, where the user stands, this device's watch party);
 * nothing is polled. [start] as each item starts (a show rolling on keeps the choices; another title
 * starts afresh), [left] as it ends or the player leaves; then for "We're in the same room" what was
 * played to the end is counted for those there ([record], `MediaServer.watchedWithGroup`).
 */
class WatchCompany(
    private val scope: CoroutineScope,
    partners: StateFlow<PartnersInfo?>,
    standings: StateFlow<Map<String, KeepUp>>,
    party: StateFlow<SyncPlayGroup?>,
    private val selfName: () -> String?,
    /** The group used last on a title (ties in [WatchPartners.contextFor]). */
    private val lastUsed: (titleId: String) -> String? = { null },
    /** "Counts for" as chosen earlier on a title in this run of the app (the next episode keeps it). */
    private val chosen: (titleId: String) -> WatchingWith? = { null },
    /** The user chose who a title counts with. */
    private val onChosen: (titleId: String, with: WatchingWith) -> Unit = { _, _ -> },
    /** "We're in the same room" for a title, as chosen earlier (the guard's question, or an earlier part). */
    private val room: (titleId: String) -> SameRoom? = { null },
    private val onRoom: (titleId: String, room: SameRoom?) -> Unit = { _, _ -> },
    /** Counts what was played to the end for the group and who was there (`watchedWithGroup`). */
    private val record: suspend (groupId: String, itemIds: List<String>, present: List<String>) -> Unit = { _, _, _ -> },
) {
    private val item = MutableStateFlow<MediaItem?>(null)
    private val with = MutableStateFlow<WatchingWith?>(null)
    private val inRoom = MutableStateFlow<SameRoom?>(null)
    private val counted = mutableSetOf<String>()
    private var titleId: String? = null

    private val inputs = combine(item, with, inRoom) { i, w, r -> Triple(i, w, r) }

    /** The chip now (null: none to show). */
    val chip: StateFlow<WithChip?> = combine(inputs, partners, standings, party) { (i, w, r), info, st, p ->
        WithChip.of(i, info, st, p, selfName(), w, TogetherRow.titleOf(i ?: return@combine null, st)?.let(lastUsed), r)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** An item starts: another title takes what was chosen on it earlier in this run of the app (a show's next episode keeps it). */
    fun start(playing: MediaItem, standings: Map<String, KeepUp>) {
        val title = TogetherRow.titleOf(playing, standings) ?: playing.id
        if (title != titleId) {
            titleId = title
            with.value = chosen(title)
            inRoom.value = room(title)
        }
        item.value = playing
    }

    /** "Counts for": a group on the title, or just the user (the room goes with another choice). */
    fun countFor(choice: WatchingWith) {
        with.value = choice
        val title = titleId ?: return
        onChosen(title, choice)
        val r = inRoom.value
        if (r != null && (choice !is WatchingWith.Group || choice.groupId != r.groupId)) {
            inRoom.value = null
            onRoom(title, null)
        }
    }

    /** "We're in the same room" with [present] (user ids) of group [groupId]; empty: nobody else after all. */
    fun sameRoom(groupId: String, present: List<String>) {
        val r = SameRoom(groupId, present).takeIf { present.isNotEmpty() }
        inRoom.value = r
        titleId?.let { onRoom(it, r) }
        if (r != null) countFor(WatchingWith.Group(groupId))
    }

    /** The choice to pass to the guards for what plays next in this player (null: as usual). */
    val choice: WatchingWith? get() = with.value

    /**
     * [itemId] stopped at [positionMs] of [durationMs] (or [ended]): played to the end (Jellyfin's
     * 90%), it counts for everyone in the room, once.
     */
    fun left(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean = false) {
        val r = inRoom.value ?: return
        val done = ended || (durationMs > 0 && positionMs >= durationMs * PLAYED_AT)
        if (!done || !counted.add(itemId)) return
        scope.launch { runCatching { record(r.groupId, listOf(itemId), r.present) } }
    }

    companion object {
        /** Jellyfin's default "played" threshold. */
        const val PLAYED_AT = 0.9
    }
}
