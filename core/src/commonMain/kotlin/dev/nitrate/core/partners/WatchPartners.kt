package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.plugins.GuardAction
import dev.nitrate.core.plugins.GuardChoice
import dev.nitrate.core.plugins.GuardDecision
import dev.nitrate.core.plugins.PlaybackGuard
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.flow.update

/**
 * People who watch one show together and see each other's progress in it; [others] are the
 * members besides the user. A pair is a group of two; one show can have several groups.
 */
data class WatchGroup(
    val id: String,
    val seriesId: String,
    val seriesName: String,
    val others: List<UserRef>,
    val sinceMs: Long,
    /** How the title is let out (null: all at once). */
    val schedule: GroupSchedule? = null,
    /** A change to the schedule waiting for votes. */
    val pending: ScheduleChange? = null,
    /** The first locked part of the title (it and everything after it are locked), if any. */
    val lockedFromId: String? = null,
    val opensAtMs: Long? = null,
    /** The day [lockedFromId] opens (0 = Sunday), in the schedule's time zone. */
    val opensDay: Int? = null,
    /** Together only ([GroupSchedule.together]): the first part nobody in the group has seen; it and everything after it are for watching together. */
    val togetherFromId: String? = null,
    /**
     * A together-only group keeps its own watched record (Companion feature `grouprecord`): what
     * the user watched with this group (the parts of its title), which is their place in it
     * instead of Jellyfin's one watched state. Null for a group that watches separately (or a
     * server without it): Jellyfin's watched state is the group's progress.
     */
    val ownRecord: Set<String>? = null,
) {
    /** "Sam", "Frank, Tommy and Jane". */
    val label get() = WatchPartners.names(others.map { it.name })

    /** Whether [itemId] is locked by the schedule, given the title's parts in [order]. */
    fun locks(itemId: String, order: List<String>): Boolean {
        val from = lockedFromId?.let(order::indexOf)?.takeIf { it >= 0 } ?: return false
        val at = order.indexOf(itemId)
        return at >= from
    }

    /**
     * Whether [itemId] may only be played in a watch-together session with someone in the group:
     * the group watches together only and nobody in it has seen [itemId] yet.
     */
    fun needsTogether(itemId: String, order: List<String>): Boolean {
        if (schedule?.together != true) return false
        val from = togetherFromId?.let(order::indexOf)?.takeIf { it >= 0 } ?: return false
        return order.indexOf(itemId) >= from
    }

    /** When the first locked part opens: "Thursday 7pm". */
    val opensLabel: String? get() = schedule?.let { s -> opensDay?.let { "${GroupSchedule.DAYS[it]} ${s.clock}" } }
}

/**
 * How a group lets its title out: all at once ([BINGE]), or one new part every 2 days, every 3
 * days or weekly on [day], at [hour]:[minute] in the server's time zone. Anything someone in the
 * group has seen always stays open; only what nobody's seen yet is rationed. Locked parts can't
 * be played in Nitrate, or ([softLock]) only after a warning. How the group watches: separately,
 * at the group's pace (the default, and what schedules stored before the choice read as), or
 * [together] only: a part nobody in the group has seen yet plays only in a watch-together
 * session with another member.
 */
data class GroupSchedule(
    val cadence: String = BINGE,
    val day: Int = 4,
    val hour: Int = 19,
    val minute: Int = 0,
    val softLock: Boolean = false,
    val together: Boolean = false,
) {
    val limits get() = cadence == DAYS2 || cadence == DAYS3 || cadence == WEEKLY

    /** Whether it holds anything back: rationed, or together only. */
    val active get() = limits || together

    /** "Together only" / "Separately, at the group's pace". */
    val how: String get() = howLabel(together)

    /** "7pm", "7:30pm". */
    val clock: String get() = clockOf(hour, minute)

    /** The pace in a few words, for "ahead of your weekly schedule": "weekly", "every 2 days"... */
    val pace: String get() = when (cadence) {
        DAYS2 -> "every 2 days"
        DAYS3 -> "every 3 days"
        WEEKLY -> "weekly"
        else -> "binge"
    }

    /** "Binge", "Every 2 days, 7pm", "Thursdays 7pm", with ", together only" and ", ask first" for a soft lock. */
    val label: String get() = when (cadence) {
        DAYS2 -> "Every 2 days, $clock"
        DAYS3 -> "Every 3 days, $clock"
        WEEKLY -> "${DAYS[day.coerceIn(0, 6)]}s $clock"
        else -> "Binge"
    } + (if (together) ", together only" else "") + if (active && softLock) ", ask first" else ""

    /** What accepting an invite agrees to, for the invite's text (empty when the schedule holds nothing back). */
    val terms: String get() {
        if (!active) return ""
        val pace = if (limits) " The group goes at its own pace: $label, ${if (softLock) "with a warning before watching ahead" else "locked until then"}." else ""
        val with = if (together) {
            " You'd watch it together only: anything nobody in the group has seen yet plays in Nitrate only in a watch party with someone from the group" +
                (if (softLock) " (or after a warning)." else ".")
        } else ""
        return "$pace$with Accepting agrees to it."
    }

    /** The schedule picker's note under its choices. */
    val note: String get() =
        "Anything someone in the group has seen stays open, so whoever's behind can catch up." +
            (if (together) " What nobody has seen yet is for watching together: in Nitrate it plays only in a watch party with someone from the group." else "") +
            " Only Nitrate keeps to it: Jellyfin's own apps don't know about it."

    companion object {
        const val BINGE = "binge"
        const val DAYS2 = "days2"
        const val DAYS3 = "days3"
        const val WEEKLY = "weekly"
        val CADENCES = listOf(BINGE to "Binge", DAYS2 to "Every 2 days", DAYS3 to "Every 3 days", WEEKLY to "Weekly")
        val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        /** How a group watches, as offered: separately (the default) or together only. */
        val HOW = listOf(false to "Separately, at the group's pace", true to "Together only")

        fun howLabel(together: Boolean) = HOW.first { it.first == together }.second

        /** The times offered. */
        val HOURS = listOf(12, 17, 18, 19, 20, 21, 22)

        fun clockOf(hour: Int, minute: Int): String {
            val h = if (hour % 12 == 0) 12 else hour % 12
            val m = if (minute == 0) "" else ":" + minute.toString().padStart(2, '0')
            return "$h$m${if (hour < 12) "am" else "pm"}"
        }
    }
}

/** A member's request to change a group's schedule, waiting for votes ([voted]: the user already answered). */
data class ScheduleChange(val id: String, val fromName: String, val proposed: GroupSchedule, val yes: List<String>, val no: List<String>, val voted: Boolean, val atMs: Long)

/**
 * An invite to join someone's watch-together group right now ([groupId], a SyncPlay group), with
 * what's playing when known. Open for a few hours on the server; joining or turning it down
 * dismisses it.
 */
@kotlinx.serialization.Serializable
data class WatchInvite(
    val id: String,
    val fromUserId: String,
    val fromName: String,
    val groupId: String,
    val itemId: String?,
    val itemName: String?,
    val atMs: Long,
) {
    /** "Will invites you to a watch party: Nitrate" (or just "… to a watch party" when nothing's playing yet). */
    val text: String get() = "$fromName invites you to a watch party" + (itemName?.let { ": $it" } ?: "")
}

/**
 * An invite someone sent the user: into [groupId], or a new group with them (null).
 * [willSee] is everyone who learns the user's name and progress by accepting.
 */
data class IncomingRequest(
    val id: String,
    val fromId: String,
    val fromName: String,
    val seriesId: String,
    val seriesName: String,
    val groupId: String?,
    val willSee: List<String>,
    val atMs: Long,
    /** The group's schedule, which accepting agrees to. */
    val schedule: GroupSchedule? = null,
)

/** An invite the user sent: always just "waiting" until accepted or lapsed (nothing says whether the account exists). */
data class OutgoingRequest(val id: String, val to: String, val seriesId: String, val seriesName: String, val groupId: String?, val atMs: Long)

data class PartnersInfo(val groups: List<WatchGroup>, val incoming: List<IncomingRequest>, val outgoing: List<OutgoingRequest>) {
    /** The user's groups for [seriesId]. */
    fun on(seriesId: String) = groups.filter { it.seriesId == seriesId }
}

/** Where another member of one of the user's groups ([groupId]) is in the group's show. */
data class PartnerProgress(val groupId: String, val userId: String, val userName: String, val watched: Set<String>, val last: String?, val nextId: String?)

/**
 * Where the user stands in one shared show: the episodes in order (specials left out), the ones
 * the user has finished, and every group member's progress.
 */
data class KeepUp(
    val seriesId: String,
    val order: List<String>,
    val mine: Set<String>,
    val progress: List<PartnerProgress>,
    /** What the title's parts are called: "episode" for a show, else "title". */
    val noun: String = "episode",
    /**
     * The user's place in each together-only group on the title that keeps its own record (group
     * id → the parts watched with that group, [WatchGroup.ownRecord]). Other groups go by [mine].
     */
    val own: Map<String, Set<String>> = emptyMap(),
) {
    /** One film on its own (nothing to keep pace through, only who's seen it). */
    val single get() = order.size == 1

    /** What the user has watched as far as group [groupId] goes: with it, for a group keeping its own record; else Jellyfin's watched state ([mine]). */
    fun mineIn(groupId: String?): Set<String> = groupId?.let(own::get) ?: mine

    /** The first part (index in [order]) the user hasn't watched as far as group [groupId] goes; [order]'s size when all are. */
    fun nextIndexIn(groupId: String?): Int {
        val seen = mineIn(groupId)
        return order.indexOfFirst { it !in seen }.let { if (it < 0) order.size else it }
    }

    companion object {
        /**
         * [seriesId] is the group's title (a show, film, playlist or collection); [items] what it
         * holds, in order; [groups] the user's groups (those on this title keeping their own record
         * give [own]).
         */
        fun of(seriesId: String, items: List<MediaItem>, progress: List<PartnerProgress>, groups: List<WatchGroup> = emptyList()): KeepUp {
            val real = WatchPartners.countable(items)
            val noun = if (real.all { it.kind == ItemKind.EPISODE }) "episode" else "title"
            val ids = real.map { it.id }
            val own = groups.filter { it.seriesId == seriesId && it.ownRecord != null }.associate { g -> g.id to g.ownRecord!!.filter { it in ids }.toSet() }
            return KeepUp(seriesId, ids, real.filter { it.user.played }.map { it.id }.toSet(), progress, noun, own)
        }
    }
}

/**
 * Where the user stands in a shared title: one word for them ([word]: Ahead, Behind, Between,
 * Level), each partner with their own word ([partners]: "Kevin 2 ahead · Jane level · Kim 3
 * behind"), both together on one line ([short]), the summary's sentence ([long]); [catchUp]:
 * someone is ahead of the user. Words rather than signs, which are too small to read.
 */
data class Standing(val short: String, val long: String, val catchUp: Boolean, val word: String = short, val partners: String = "")

/** Whether the user is private (never listed, not findable by name), and whether an admin made it so. */
data class Privacy(val isPrivate: Boolean, val byAdmin: Boolean)

/** Watch partners logic: who's behind, what to warn about, what to mark. */
object WatchPartners {
    /** The Companion feature. */
    const val FEATURE = "partners"

    /** Partners who haven't seen [episode]. */
    fun behind(episode: MediaItem, progress: List<PartnerProgress>): List<PartnerProgress> = progress.filter { episode.id !in it.watched }

    /** What of a title counts: everything but missing episodes and specials. */
    fun countable(items: List<MediaItem>): List<MediaItem> =
        items.filter { !it.isMissing && (it.kind != ItemKind.EPISODE || (it.seasonNumber ?: 0) != 0) }

    /** "1 episode", "3 titles". */
    fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** "Sam", "Sam and Kim", "Sam, Kim and Lee". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Spoiler hiding goes by whoever is furthest behind: an episode's description stays hidden
     * while the user or any partner on the show hasn't seen it (unless the user chose their own
     * progress for that show, [DisplayPrefs.ownSpoilerShows]).
     */
    fun hidesDescription(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>): Boolean =
        prefs.hideSpoilers && item.kind == ItemKind.EPISODE &&
            (!item.user.played || (item.seriesId !in prefs.ownSpoilerShows && progress.any { item.id !in it.watched }))

    /** Blurs an episode still by the same rule. */
    fun isSpoiler(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>): Boolean = hidesDescription(prefs, item, progress)

    /**
     * Up next together for a show: the first episode (in order) someone hasn't seen yet, the
     * user or a partner. Null when everyone's seen everything.
     */
    fun nextTogether(episodes: List<MediaItem>, progress: List<PartnerProgress>): MediaItem? =
        countable(episodes).firstOrNull { e -> !e.user.played || progress.any { e.id !in it.watched } }

    /** Partners shown on a card before "and N more". */
    const val SHOWN = 3

    /**
     * Where the user stands with their group(s) for [item]: their word (Ahead: nobody past them;
     * Behind: someone past them and nobody behind; Between: both; Level: all at the same place)
     * and each partner's, by how far through the title each has got. A film on its own reads
     * "seen" / "not yet".
     */
    fun standing(item: MediaItem, k: KeepUp, groupIds: Set<String>? = null): Standing? {
        val members = k.progress.filter { groupIds == null || it.groupId in groupIds }.distinctBy { it.userId }
        if (members.isEmpty()) return null
        val index = k.order.withIndex().associate { it.value to it.index + 1 }
        fun reach(watched: Set<String>) = watched.maxOfOrNull { index[it] ?: 0 } ?: 0
        // Each partner against the user's place in their group: a together-only group's own record, else Jellyfin's.
        val deltas = members.map { it to reach(it.watched) - reach(k.mineIn(it.groupId)) }.sortedByDescending { it.second }
        val myMine = members.map { it.groupId }.distinct().singleOrNull()?.let(k::mineIn) ?: k.mine
        val ahead = deltas.any { it.second > 0 }
        val behind = deltas.any { it.second < 0 }
        val word = when {
            ahead && behind -> "Between"
            ahead -> "Behind"
            behind -> "Ahead"
            else -> "Level"
        }
        fun says(p: PartnerProgress, d: Int) = when {
            k.single -> "${p.userName} ${if (k.order.first() in p.watched) "seen" else "not yet"}"
            d > 0 -> "${p.userName} $d ahead"
            d < 0 -> "${p.userName} ${-d} behind"
            else -> "${p.userName} level"
        }
        val shown = deltas.take(SHOWN).map { (p, d) -> says(p, d) } + listOfNotNull((deltas.size - SHOWN).takeIf { it > 0 }?.let { "and $it more" })
        val partners = shown.joinToString(" · ")
        val myWord = if (k.single) (if (k.order.first() in myMine) "Seen" else if (ahead) "Not seen" else "Not yet") else word
        val long = deltas.joinToString("; ") { (p, d) ->
            val where = p.last?.let { "on $it" } ?: "not started"
            when {
                k.single -> says(p, d)
                d > 0 -> "${p.userName} is $where, ${count(d, k.noun)} ahead of you"
                d < 0 -> "${p.userName} is $where, ${count(-d, k.noun)} behind you"
                else -> "${p.userName} is level with you"
            }
        }
        return Standing("$myWord · $partners", long, catchUp = ahead, word = myWord, partners = partners)
    }

    /**
     * Who playing [item] counts with: the playback's context, whose group locks stop it (any other
     * group's are only a warning). In order: the user's own choice ([with]: a group on the title,
     * or just them); in a live session ([liveParticipants], names), every group on the title with
     * a member there; alone, the group for which [item] is next (a together-only group by its own
     * record, [KeepUp.mineIn]), else, ahead of every group, the furthest-along group it's ahead of,
     * else (a rewatch) the nearest; a tie goes to [lastUsed] (the group used last on this title),
     * then the first. Empty for a title the user shares with nobody.
     */
    fun contextFor(
        item: MediaItem,
        info: PartnersInfo?,
        standings: Map<String, KeepUp>,
        liveParticipants: List<String> = emptyList(),
        lastUsed: String? = null,
        with: WatchingWith? = null,
    ): PlayContext {
        val title = TogetherRow.titleOf(item, standings)
        val groups = title?.let { info?.on(it) }.orEmpty()
        if (title == null || groups.isEmpty()) return PlayContext(title, emptyList(), PlayContext.Reason.NONE)
        when (with) {
            WatchingWith.JustMe -> return PlayContext(title, emptyList(), PlayContext.Reason.CHOSEN)
            is WatchingWith.Group -> groups.firstOrNull { it.id == with.groupId }?.let { return PlayContext(title, listOf(it), PlayContext.Reason.CHOSEN) }
            null -> Unit
        }
        val present = liveParticipants.map { it.lowercase() }.toSet()
        if (present.isNotEmpty()) {
            val live = groups.filter { g -> g.others.any { it.name.lowercase() in present } }
            if (live.isNotEmpty()) return PlayContext(title, live, PlayContext.Reason.LIVE)
        }
        fun pick(candidates: List<WatchGroup>, reason: PlayContext.Reason): PlayContext {
            if (candidates.size == 1) return PlayContext(title, candidates, reason)
            candidates.firstOrNull { it.id == lastUsed }?.let { return PlayContext(title, listOf(it), PlayContext.Reason.LAST_USED) }
            return PlayContext(title, listOf(candidates.first()), reason)
        }
        val k = standings[title]
        val at = k?.order?.indexOf(item.id) ?: -1
        if (k == null || at < 0) return pick(groups, PlayContext.Reason.NEAREST)
        val next = groups.associateWith { k.nextIndexIn(if (it.ownRecord != null) it.id else null) }
        groups.filter { next.getValue(it) == at }.takeIf { it.isNotEmpty() }?.let { return pick(it, PlayContext.Reason.NEXT) }
        // Ahead of where a group is: the one furthest along that it's still ahead of.
        val behindIt = groups.filter { next.getValue(it) < at }
        if (behindIt.isNotEmpty()) {
            val furthest = behindIt.maxOf { next.getValue(it) }
            return pick(behindIt.filter { next.getValue(it) == furthest }, PlayContext.Reason.NEAREST)
        }
        // A rewatch (every group is past it): the group used last, else the nearest.
        groups.firstOrNull { it.id == lastUsed }?.let { return PlayContext(title, listOf(it), PlayContext.Reason.LAST_USED) }
        val nearest = groups.minOf { next.getValue(it) }
        return pick(groups.filter { next.getValue(it) == nearest }, PlayContext.Reason.NEAREST)
    }
}

/** Who a playback counts with, as the user chose it (the player's chip): one of their watch groups, or just them. */
sealed interface WatchingWith {
    /** Watching on their own: no group's lock stops it (a group it counts for still warns). */
    data object JustMe : WatchingWith

    /** Watching with group [groupId]. */
    data class Group(val groupId: String) : WatchingWith
}

/**
 * Who a playback of a part of a shared title counts with: [groups] (empty: just the user, or no
 * group on [titleId]), and why ([reason]). Only these groups' locks stop it; any other group's
 * lock is a warning ([WatchPartnersPlugin]).
 */
data class PlayContext(val titleId: String?, val groups: List<WatchGroup>, val reason: Reason) {
    enum class Reason {
        /** The user chose it ([WatchingWith]). */
        CHOSEN,

        /** In a live (SyncPlay) session with members of these groups. */
        LIVE,

        /** The part is next for this group (a together-only group by its own record). */
        NEXT,

        /** Several would do; the group used last on this title. */
        LAST_USED,

        /** No group has it next: the nearest (ahead of every group's place: the furthest along it's ahead of; a rewatch: the nearest). */
        NEAREST,

        /** No group on the title. */
        NONE,
    }

    val justMe get() = groups.isEmpty()

    /** "With Sam", "With Sam and Kim", "Just me". */
    val label: String get() = if (groups.isEmpty()) "Just me" else "With " + WatchPartners.names(groups.flatMap { g -> g.others.map { it.name } }.distinct())
}

/**
 * Why the user can't freely play a part of a shared title now: it isn't out yet for [group]
 * (its schedule rations the title), or ([together]) [group] watches together only and nobody in
 * it has seen the part yet.
 */
data class PartLock(val group: WatchGroup, val together: Boolean) {
    /** When it opens ("Thursday 7pm"), for a part not out yet; null while it waits on someone watching the open one. */
    val opens: String? get() = if (together) null else group.opensLabel

    /** The card's word in place of the user's standing: "Opens Thursday 7pm", "Opens later", "Watch party". */
    val word: String get() = if (together) WATCH_TOGETHER else opens?.let { "Opens $it" } ?: "Opens later"

    companion object {
        const val WATCH_TOGETHER = "Watch party"

        /**
         * The lock on [itemId] (a part of the title in [order]) from any of [groups] (the user's
         * groups on that title): not out yet comes first, then together only.
         */
        fun of(itemId: String, order: List<String>, groups: List<WatchGroup>): PartLock? {
            groups.firstOrNull { it.schedule?.limits == true && it.locks(itemId, order) }?.let { return PartLock(it, together = false) }
            groups.firstOrNull { it.needsTogether(itemId, order) }?.let { return PartLock(it, together = true) }
            return null
        }

        /**
         * The lock on [item], as cards and spoiler hiding see it (the user's groups and where they
         * stand): only the lock of the group(s) it would be watched with ([WatchPartners.contextFor],
         * alone; [lastUsed] the group used last on its title).
         */
        fun of(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>, lastUsed: String? = null): PartLock? {
            if (info == null || info.groups.none { it.schedule?.active == true }) return null
            val title = TogetherRow.titleOf(item, standings) ?: return null
            val order = standings[title]?.order ?: return null
            val context = WatchPartners.contextFor(item, info, standings, lastUsed = lastUsed)
            return of(item.id, order, context.groups)
        }
    }
}

/**
 * The watch-partners plugin. It doesn't stand between the user and Play, except for a group's
 * schedule: a part of a title the group hasn't let out yet can't be played in Nitrate (a hard
 * lock), or only after a warning (a soft lock); and for a group that watches together only, a
 * part nobody in it has seen yet plays only in a watch-together session with another member
 * (with the same hard or soft lock). Home's "Watching together" row ([TogetherRow]) and the
 * cards' lines keep everyone in step. Its switch turns the whole feature on or off.
 */
class WatchPartnersPlugin : PlaybackGuard {
    override val id = ID
    override val name = "Watch groups"
    override val description = "Keeps what you watch with others in step (shows, films, playlists and collections): groups who see each other's progress, " +
        "schedules that let a title out one part at a time or keep it for watching together, and a Home row of where everyone is. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    /** The group used last on each title, per account (`userId|titleId` → group id): in memory for this run of the app only. */
    private val lastUsed = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    private fun key(host: PluginHost, titleId: String) = "${runCatching { host.server.userId }.getOrNull()}|$titleId"

    /** The group the user last watched [titleId] with in this run of the app (for [WatchPartners.contextFor]'s ties), if any. */
    fun lastUsedOn(host: PluginHost, titleId: String): String? = lastUsed.value[key(host, titleId)]

    /** Notes that the user is watching [titleId] with group [groupId] (the player's chip, or a playback that went ahead). */
    fun used(host: PluginHost, titleId: String, groupId: String) {
        lastUsed.update { it + (key(host, titleId) to groupId) }
    }

    /** "Counts for" as chosen on the player's chip, per account and title, for this run of the app: later parts play with it too. */
    private val chosen = kotlinx.coroutines.flow.MutableStateFlow<Map<String, WatchingWith>>(emptyMap())

    /** "We're in the same room", per account and title, for this run of the app. */
    private val rooms = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SameRoom>>(emptyMap())

    /** Who the user chose to watch [titleId] with (the player's chip), if they did. */
    fun chosenOn(host: PluginHost, titleId: String): WatchingWith? = chosen.value[key(host, titleId)]

    /** The user chose who [titleId] counts with ("Counts for": a group, or just them). */
    fun choose(host: PluginHost, titleId: String, with: WatchingWith) {
        chosen.update { it + (key(host, titleId) to with) }
        if (with is WatchingWith.Group) used(host, titleId, with.groupId)
    }

    /** Who's in the room watching [titleId] with the user, if anyone. */
    fun roomOn(host: PluginHost, titleId: String): SameRoom? = rooms.value[key(host, titleId)]

    /** "We're in the same room" for [titleId] ([room]; null: no longer), which also counts it for that group. */
    fun setRoom(host: PluginHost, titleId: String, room: SameRoom?) {
        rooms.update { if (room == null || room.present.isEmpty()) it - key(host, titleId) else it + (key(host, titleId) to room) }
        if (room != null && room.present.isNotEmpty()) choose(host, titleId, WatchingWith.Group(room.groupId))
    }

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision = beforePlay(item, host, null)

    /**
     * Only the playback's context ([WatchPartners.contextFor]: [with], else the live session's
     * groups, else the group this part is next for) can stop it; another group's lock is a
     * warning when watching would count for that group (one that goes by Jellyfin's watched
     * state), and nothing at all for a together-only group (its own record doesn't move).
     */
    override suspend fun beforePlay(item: MediaItem, host: PluginHost, with: WatchingWith?): GuardDecision {
        if (!item.isPlayable) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val info = runCatching { host.server.partners() }.getOrNull() ?: return GuardDecision.Allow
        val held = info.groups.filter {
            (it.schedule?.limits == true && it.lockedFromId != null) || (it.schedule?.together == true && it.togetherFromId != null)
        }
        if (held.isEmpty()) return GuardDecision.Allow
        val present = host.together?.participants().orEmpty()
        val presentLower = present.map { it.lowercase() }.toSet()
        val warnings = mutableListOf<WatchGroup>()
        for (title in held.map { it.seriesId }.distinct()) {
            val items = runCatching { host.server.titleItems(title) }.getOrNull() ?: continue
            val k = KeepUp.of(title, items, emptyList(), info.groups)
            if (item.id !in k.order) continue
            val context = WatchPartners.contextFor(item, info, mapOf(title to k), present, lastUsedOn(host, title), with ?: chosenOn(host, title))
            val locks = context.groups.mapNotNull { PartLock.of(item.id, k.order, listOf(it)) }
            // Not out yet: nothing gets it out early, watching together or not.
            locks.firstOrNull { !it.together }?.let { return notOutYet(item, it.group) }
            // Together only: fine in a watch party with someone from that group, or with them in the room.
            val room = roomOn(host, title)
            locks.firstOrNull { l ->
                l.group.others.none { it.name.lowercase() in presentLower || (room?.groupId == l.group.id && it.id in room.present) }
            }?.let { return togetherOnly(item, it.group, host) }
            // Another group's schedule: watching counts as seen for it when it goes by Jellyfin's watched state.
            warnings += info.on(title).filter { g ->
                g !in context.groups && g.ownRecord == null && g.schedule?.limits == true && g.locks(item.id, k.order)
            }
            context.groups.firstOrNull()?.let { used(host, title, it.id) }
        }
        if (warnings.isEmpty()) return GuardDecision.Allow
        return GuardDecision.Ask(
            title = "Ahead of your schedule",
            message = warnings.distinctBy { it.id }.joinToString(" ") { g ->
                "This is ahead of your ${g.schedule!!.pace} schedule with ${g.label}. It counts as seen for that group."
            },
            choices = listOf(GuardChoice("Watch anyway", GuardAction.PlayAnyway), GuardChoice("Not now", GuardAction.Cancel)),
        )
    }

    private fun notOutYet(item: MediaItem, g: WatchGroup): GuardDecision.Ask {
        val s = g.schedule!!
        val opens = g.opensLabel ?: s.label
        val what = item.episodeLabel ?: item.name
        return GuardDecision.Ask(
            title = "Opens $opens",
            message = "$what isn't out yet for your group with ${g.label}: ${g.seriesName} follows the group's schedule (${s.label}). It opens $opens.",
            choices = if (s.softLock) listOf(GuardChoice("Watch anyway", GuardAction.PlayAnyway), GuardChoice("Not now", GuardAction.Cancel))
            else listOf(GuardChoice("OK", GuardAction.Cancel)),
        )
    }

    private fun togetherOnly(item: MediaItem, g: WatchGroup, host: PluginHost): GuardDecision.Ask {
        val what = item.episodeLabel ?: item.name
        val together = host.together?.let { t ->
            GuardChoice("Start a watch party", GuardAction.RunThenPlay(playIfFailed = false) { t.start(item, g.id, g.others.map { it.id }, g.label) })
        }
        // Watching it side by side: everyone in the group counts as there (the player's chip changes who).
        val room = GuardChoice("We're in the same room", GuardAction.RunThenPlay(playIfFailed = false) { setRoom(host, g.seriesId, SameRoom(g.id, g.others.map { it.id })) })
        return GuardDecision.Ask(
            title = PartLock.WATCH_TOGETHER,
            message = "Nobody in your group with ${g.label} has seen $what yet, and you watch ${g.seriesName} together only: " +
                "play it in a watch party with them, or together in the same room. Catching up and rewatching are always free.",
            choices = listOfNotNull(
                together,
                room,
                GuardChoice("Watch anyway", GuardAction.PlayAnyway).takeIf { g.schedule?.softLock == true },
                GuardChoice("Not now", GuardAction.Cancel),
            ),
        )
    }

    companion object {
        const val ID = "watch-partners"
    }
}
