package dev.nitrate.core.partners

import dev.nitrate.core.model.MediaItem
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
 * others in this device's Group Watch Session ([session], names; [inSession] even before anyone
 * joins; [startedByMe] when the user started it) and who's in the room ([room]).
 */
data class WithChip(
    val context: PlayContext,
    val groups: List<WatchGroup>,
    val inSession: Boolean = false,
    val session: List<String> = emptyList(),
    val room: SameRoom? = null,
    val startedByMe: Boolean = false,
) {
    /** Shown only for a title shared with a group, or while in a Group Watch Session. */
    val shown: Boolean get() = groups.isNotEmpty() || inSession

    /** The group "Counts for" has chosen now (null: just the user). */
    val group: WatchGroup? get() = context.groups.firstOrNull()

    /** The names of who's in the room besides the user (by the room's group's members). */
    val roomNames: List<String> get() {
        val r = room ?: return emptyList()
        val members = groups.firstOrNull { it.id == r.groupId }?.others.orEmpty()
        return r.present.mapNotNull { id -> members.firstOrNull { it.id == id }?.name }
    }

    /**
     * The group panel's top line: "In a Group Watch Session with Tommy, Suzy", "In the room with
     * Tommy", "Counting for you and Tommy · Change" (it counts for the group while you watch on your
     * own; "Counting for you, Tommy & Suzy · Change"), "No group · Change".
     */
    val label: String get() = when {
        inSession -> if (session.isEmpty()) "In a Group Watch Session · waiting for others" else "In a Group Watch Session with " + session.joinToString(", ")
        roomNames.isNotEmpty() -> "In the room with " + and(roomNames)
        context.justMe -> "No group · Change"
        else -> "Counting for " + youAnd(context.groups.flatMap { g -> g.others.map { it.name } }.distinct()) + " · Change"
    }

    /**
     * The player's group button (it opens the group panel): the group's name ("Tommy & Suzy"), "No
     * group", "Tommy · in the room", or "Group Watch Session · Tommy, Suzy".
     */
    val button: String get() = when {
        inSession -> if (session.isEmpty()) "Group Watch Session" else "Group Watch Session · " + and(session)
        roomNames.isNotEmpty() -> and(roomNames) + " · in the room"
        else -> group?.let { nameOf(it) } ?: "No group"
    }

    /** Initials for the button's avatars: the people of the group it counts for, or in the session (at most three). */
    val initials: List<String> get() = when {
        inSession -> session
        roomNames.isNotEmpty() -> roomNames
        else -> group?.others?.map { it.name }.orEmpty()
    }.take(3).map { it.trim().take(1).uppercase() }.filter { it.isNotEmpty() }

    /** [label] without "· Change", where nothing can be changed (easy mode's note). */
    val plain: String get() = label.removeSuffix(" · Change")

    /**
     * More than one group would do and none was chosen: the chip says which it counts for at the
     * start ("Counting for: Tommy"), so the user can switch.
     */
    val ambiguous: Boolean get() = !inSession && groups.size > 1 && context.reason in AUTO

    /** "Counting for you and Tommy" (or "Counting for just you"). */
    val counting: String get() = "Counting for " + (group?.let { g -> youAnd(g.others.map { it.name }) } ?: "just you")

    /** Who's here, for the panel: "You, Tommy and Suzy, in a Group Watch Session", "You and Tommy, in the room", "Watching on your own". */
    val who: String get() = when {
        inSession && session.isNotEmpty() -> "You and " + WatchPartners.names(session) + ", in a Group Watch Session"
        inSession -> "Just you so far: the group was asked to join"
        roomNames.isNotEmpty() -> "You and " + WatchPartners.names(roomNames) + ", in the room"
        else -> "Watching on your own"
    }

    companion object {
        private val AUTO = setOf(PlayContext.Reason.NEXT)

        /** A group's line in "Counts for": "Tommy & Suzy". */
        fun nameOf(g: WatchGroup): String = and(g.others.map { it.name }).ifEmpty { g.seriesName }

        /** "you and Tommy", "you, Tommy & Suzy", "you, Tommy, Suzy, Kim & 2 more" (who a playback counts for). */
        fun youAnd(names: List<String>): String = when {
            names.isEmpty() -> "you"
            names.size == 1 -> "you and " + names[0]
            else -> "you, " + and(names)
        }

        /** "Tommy", "Tommy & Suzy", "Tommy, Suzy & Kim", "Tommy, Suzy, Kim & 2 more". */
        fun and(names: List<String>): String = when {
            names.isEmpty() -> ""
            names.size == 1 -> names[0]
            names.size <= 3 -> names.dropLast(1).joinToString(", ") + " & " + names.last()
            else -> names.take(3).joinToString(", ") + " & ${names.size - 3} more"
        }

        /**
         * The chip for [item]: its context ([WatchPartners.contextFor] with [with] and the
         * session's names), and this device's Group Watch Session [session] (who's in it by name,
         * the user left out). Null for a title the user shares with no group, outside a session.
         */
        fun of(
            item: MediaItem?,
            info: PartnersInfo?,
            standings: Map<String, KeepUp>,
            session: GroupWatchSession?,
            selfName: String?,
            with: WatchingWith? = null,
            room: SameRoom? = null,
            /** Where the playback started (a group's card, "for myself"). */
            origin: PlayOrigin = PlayOrigin.Elsewhere,
        ): WithChip? {
            if (item == null) return null
            val inIt = session?.takeIf { it.live && it.itemId == item.id }
            val others = inIt?.others.orEmpty().map { it.name }.filter { !it.equals(selfName, ignoreCase = true) }.distinct()
            val context = WatchPartners.contextFor(item, info, standings, others, with, origin)
            val groups = context.titleId?.let { info?.on(it) }.orEmpty()
            val chip = WithChip(
                context, groups, inSession = inIt != null, session = others,
                room = room?.takeIf { r -> groups.any { it.id == r.groupId } && r.present.isNotEmpty() }, startedByMe = inIt?.startedByMe == true,
            )
            return chip.takeIf { it.shown }
        }
    }
}

/**
 * A playback that counted for a group from its card finished and is also the user's immediate next
 * part in other groups: "Also count it for Suzy, where it's next too?" ([question]), answered
 * "Just this group" or "All groups where it's next" ([CHOICES]). It already counted for the card's
 * group; "All" adds it to [counting]'s [Counting.alsoNext] ([itemIds]: several parts asked about at once).
 */
data class CountAsk(val itemIds: List<String>, val what: String, val counting: Counting) {
    /** "Also count it for Suzy, where it's next too?" */
    val question: String get() = "Also count it for " + WatchPartners.names(counting.alsoNext.map { WithChip.nameOf(it) }) + ", where it's next too?"

    /** The groups "All groups where it's next" adds. */
    val others: List<String> get() = counting.alsoNext.map { it.id }

    /** The same question about one more part: asked once, for both. */
    fun sameAs(other: CountAsk): Boolean = counting.recordFor(true) == other.counting.recordFor(true)

    companion object {
        const val JUST_THIS = "Just this group"
        const val ALL = "All groups where it's next"
        val CHOICES = listOf(JUST_THIS, ALL)
    }
}

/**
 * Who one playback is watched with, for the player's group button ("Counting for you and Tommy · Change"):
 * where it started ([start]'s origin: a group's card, "for myself", elsewhere), the user's change
 * ("Change": a group or no group), who's in the room, all from data the app has already loaded
 * (the groups, where the user stands, this device's Group Watch Session); nothing is polled. [start] as
 * each item starts (the same title rolling on keeps where it started and the choices; another
 * title starts afresh), [left] as it ends or the player leaves: a part played to the end (90 %)
 * counts once, for those in the room ([record], `watchedWithGroup`), else for the groups it
 * counts for ([mark], `markForGroups`; [WatchPartners.countingFor]), asking "Just this group / All
 * groups where it's next" through [ask] when it's next in others too. A Group Watch Session is
 * counted by the server, not here.
 */
class WatchCompany(
    private val scope: CoroutineScope,
    private val partners: StateFlow<PartnersInfo?>,
    private val standings: StateFlow<Map<String, KeepUp>>,
    private val session: StateFlow<GroupWatchSession?>,
    private val selfName: () -> String?,
    /** "Counts for" as chosen earlier on a title in this run of the app (the next episode keeps it). */
    private val chosen: (titleId: String) -> WatchingWith? = { null },
    /** The user chose who a title counts with. */
    private val onChosen: (titleId: String, with: WatchingWith) -> Unit = { _, _ -> },
    /** "We're in the same room" for a title, as chosen earlier (the guard's question, or an earlier part). */
    private val room: (titleId: String) -> SameRoom? = { null },
    private val onRoom: (titleId: String, room: SameRoom?) -> Unit = { _, _ -> },
    /** Counts what was played to the end for the group and who was there (`watchedWithGroup`). */
    private val record: suspend (groupId: String, itemIds: List<String>, present: List<String>) -> Unit = { _, _, _ -> },
    /** Where an item's playback started, as the guards handed it over (null: not said). */
    private val originOf: (MediaItem) -> PlayOrigin? = { null },
    /** Where the playback of a title started, so Up Next carries on from there. */
    private val onPlaying: (titleId: String, origin: PlayOrigin) -> Unit = { _, _ -> },
    /** Counts what was played to the end in groups' records for the user (`markForGroups`). */
    private val mark: suspend (groupIds: List<String>, itemIds: List<String>) -> Unit = { _, _ -> },
    /** "Just this group / All groups where it's next", asked once the player has closed. */
    private val ask: (CountAsk) -> Unit = {},
) {
    private val item = MutableStateFlow<MediaItem?>(null)
    private val with = MutableStateFlow<WatchingWith?>(null)
    private val inRoom = MutableStateFlow<SameRoom?>(null)
    private val origin = MutableStateFlow<PlayOrigin>(PlayOrigin.Elsewhere)
    private val counted = mutableSetOf<String>()
    private var titleId: String? = null

    private val inputs = combine(item, with, inRoom, origin) { i, w, r, o -> Inputs(i, w, r, o) }

    private data class Inputs(val item: MediaItem?, val with: WatchingWith?, val room: SameRoom?, val origin: PlayOrigin)

    /** The chip now (null: none to show). */
    val chip: StateFlow<WithChip?> = combine(inputs, partners, standings, session) { x, info, st, p ->
        WithChip.of(x.item ?: return@combine null, info, st, p, selfName(), x.with, x.room, x.origin)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * An item starts, from [from] (null: as the guards handed it over, else the same title carries
     * on from where it started, another title from elsewhere). Another title takes what was chosen
     * on it earlier in this run of the app (a show's next episode keeps it).
     */
    fun start(playing: MediaItem, standings: Map<String, KeepUp>, from: PlayOrigin? = null) {
        val title = TogetherRow.titleOf(playing, standings) ?: playing.id
        val said = from ?: originOf(playing)
        if (title != titleId) {
            titleId = title
            with.value = chosen(title)
            inRoom.value = room(title)
            origin.value = said ?: PlayOrigin.Elsewhere
        } else if (said != null) origin.value = said
        onPlaying(title, origin.value)
        item.value = playing
    }

    /** Where this playback started. */
    val startedFrom: PlayOrigin get() = origin.value

    /** "Change": a group on the title, or no group (the room goes with another choice). */
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
     * What playing [playing] counts for now: nothing in a Group Watch Session (the server counts those)
     * or in the same room (counted for those there); the user's change ("Change": a group, or none);
     * else by where it started ([WatchPartners.countingFor]).
     */
    fun counting(playing: MediaItem? = item.value): Counting? {
        val i = playing ?: return null
        if (session.value?.let { it.live && it.itemId == i.id } == true || inRoom.value != null) return null
        val info = partners.value
        return when (val w = with.value) {
            WatchingWith.JustMe -> Counting(PlayOrigin.ForMyself, emptyList())
            is WatchingWith.Group -> Counting(PlayOrigin.GroupCard(w.groupId), listOfNotNull(info?.groups?.firstOrNull { it.id == w.groupId }))
            null -> WatchPartners.countingFor(origin.value, i, info, standings.value)
        }
    }

    /**
     * [itemId] stopped at [positionMs] of [durationMs] (or [ended]): played to the end (Jellyfin's
     * 90%), it counts once, for everyone in the room, else for the groups it counts for.
     */
    fun left(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean = false) {
        val done = ended || (durationMs > 0 && positionMs >= durationMs * PLAYED_AT)
        if (!done || itemId in counted) return
        val r = inRoom.value
        if (r != null) {
            counted += itemId
            scope.launch { runCatching { record(r.groupId, listOf(itemId), r.present) } }
            return
        }
        val playing = item.value?.takeIf { it.id == itemId } ?: return
        val c = counting(playing) ?: return
        if (c.groups.isEmpty()) return
        counted += itemId
        scope.launch { runCatching { mark(c.recordFor(), listOf(itemId)) } }
        if (c.asksAll) ask(CountAsk(listOf(itemId), playing.episodeLabel ?: playing.name, c))
    }

    companion object {
        /** Jellyfin's default "played" threshold. */
        const val PLAYED_AT = 0.9
    }
}
