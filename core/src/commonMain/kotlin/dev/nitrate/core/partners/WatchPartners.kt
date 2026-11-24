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
 * One of a watch group's titles (a show, a film, a playlist or a collection), with what the
 * group's schedule holds back in it now: [lockedFromId] and everything after it isn't out yet (it
 * opens at [opensAtMs], [opensDay] 0 = Sunday in the schedule's time zone, or once someone watches
 * the part before it); with Together on, [togetherFromId] is the first part nobody in the group
 * has watched with it.
 */
data class GroupTitle(
    val id: String,
    val name: String,
    val lockedFromId: String? = null,
    val opensAtMs: Long? = null,
    val opensDay: Int? = null,
    val togetherFromId: String? = null,
    /** The other members who can't open all of it on this server (empty: everyone can). */
    val reach: List<MemberReach> = emptyList(),
) {
    /**
     * "Sam can't open The Bear on this server…" for each member short of it, or null: what the
     * group sees so a title nobody told them was off-limits doesn't just sit unwatched.
     */
    val reachNote: String? get() = reach.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.note(name) }
}

/** Where the user stopped a part watching it for a group, and when ([atMs]). */
data class GroupResume(val positionMs: Long, val atMs: Long)

/** A member who can open only [parts] of a title's [of] parts on this server (0: none of it). */
data class MemberReach(val name: String, val parts: Int, val of: Int) {
    fun note(title: String): String =
        if (parts <= 0) "$name can't open $title on this server: it's outside their account's libraries or parental rating. Your server's admin can change that."
        else "$name can open $parts of $of parts of $title on this server; the rest are outside their account's libraries or parental rating."
}

/**
 * People and the titles they watch together ([titles]: e.g. Severance and The Bear with Tommy),
 * with one schedule and one Group Watch Session; [others] are the members besides the user. A
 * pair is a group of two; one title can be in several groups. [seriesId]/[seriesName] and the
 * lock fields are the first title's (all there was before groups had several).
 */
data class WatchGroup(
    val id: String,
    val seriesId: String,
    val seriesName: String,
    val others: List<UserRef>,
    val sinceMs: Long,
    /** How the titles are let out, and the Together switch ([GroupSchedule.together]); null: all at once, Together off. */
    val schedule: GroupSchedule? = null,
    /** A change to the schedule waiting for votes. */
    val pending: ScheduleChange? = null,
    /** The first title's first part not out yet (it and everything after it), if any. */
    val lockedFromId: String? = null,
    val opensAtMs: Long? = null,
    /** The day [lockedFromId] opens (0 = Sunday), in the schedule's time zone. */
    val opensDay: Int? = null,
    /** With Together on: the first title's first part nobody in the group has watched with it. */
    val togetherFromId: String? = null,
    /**
     * What the user watched as far as this group goes (the parts of its titles in the group's
     * own record, Companion features `grouprecord`/`groupwatch`: every group keeps one). Null
     * from a server without it: Jellyfin's watched state is the group's progress then.
     */
    val ownRecord: Set<String>? = null,
    /** The group's next (or current) Group Watch Session, when it has a time ([GroupSchedule.party]; Companion feature `party`). */
    val party: WatchParty? = null,
    /** The group's titles as the server sent them (Companion feature `groupwatch`); empty from older servers: [titles] is then the one title. */
    val titleList: List<GroupTitle> = emptyList(),
    /**
     * The user's resume points in this group (Companion feature `groupresume`): parts stopped
     * part-way while watching them for it, by part id. Apart from Jellyfin's resume point, which a
     * later solo play moves: the group's card resumes from here.
     */
    val resume: Map<String, GroupResume> = emptyMap(),
) {
    /** The group's titles, each with what the schedule holds back in it (older servers: the one title, from the fields above). */
    val titles: List<GroupTitle> get() = titleList.ifEmpty { listOf(GroupTitle(seriesId, seriesName, lockedFromId, opensAtMs, opensDay, togetherFromId)) }

    /** "Sam", "Frank, Tommy and Jane". */
    val label get() = WatchPartners.names(others.map { it.name })

    /** "Severance and The Bear". */
    val titleNames: String get() = WatchPartners.names(titles.map { it.name })

    /** The Together switch: spoilers by the group's progress, and playing what it hasn't watched from anywhere but its card asks first. */
    val together: Boolean get() = schedule?.together == true

    /** "If someone plays ahead: Don't allow" ([GroupSchedule.locked]), on a schedule that holds something back. */
    val locked: Boolean get() = schedule?.let { it.locked && it.active } == true

    /** Whether [titleId] is one of the group's titles. */
    fun holds(titleId: String): Boolean = titles.any { it.id == titleId }

    /** Whether [itemId] isn't out yet by the schedule, given its title's parts in [order]. */
    fun locks(itemId: String, order: List<String>): Boolean = titles.any { from(it.lockedFromId, itemId, order) }

    /** Whether nobody in the group has watched [itemId] with it yet (known with Together on; the server marks it then). */
    fun unwatchedByGroup(itemId: String, order: List<String>): Boolean = titles.any { from(it.togetherFromId, itemId, order) }

    /** Together on and the group hasn't watched [itemId] yet: playing it from anywhere but the group's card asks first. */
    fun needsTogether(itemId: String, order: List<String>): Boolean = together && unwatchedByGroup(itemId, order)

    /** When the title holding [itemId] lets its next part out: "Thursday 7pm" (null: once someone watches the open one). */
    fun opensLabelFor(itemId: String, order: List<String>): String? =
        titles.firstOrNull { from(it.lockedFromId, itemId, order) }?.let { t -> opens(t.opensDay) }

    /** When the first held-back part opens: "Thursday 7pm". */
    val opensLabel: String? get() = opens(titles.firstOrNull { it.lockedFromId != null }?.opensDay ?: opensDay)

    private fun opens(day: Int?): String? = schedule?.let { s -> day?.let { "${GroupSchedule.DAYS[it]} ${s.clock}" } }

    private fun from(fromId: String?, itemId: String, order: List<String>): Boolean {
        val from = fromId?.let(order::indexOf)?.takeIf { it >= 0 } ?: return false
        return order.indexOf(itemId) >= from
    }
}

/**
 * How a group lets its titles out: all at once ([BINGE]), or one new part of each title every 2
 * days, every 3 days or weekly on [day], at [hour]:[minute] in the server's time zone. Anything
 * someone in the group has watched with it always stays open; only what nobody has watched yet is
 * paced. [locked] is "If someone plays ahead" (stored as `Locked`; off, "Ask first", by default
 * and for schedules stored before it): off, playing ahead of the schedule says when it opens and
 * offers to watch it for oneself; on ("Don't allow"), Nitrate refuses a part not out yet from
 * anywhere, and with Together on plays what the group hasn't watched yet only together.
 * [together] is the Together switch (stored as the older "together only"; off by default and for
 * schedules stored before it): on, spoilers are hidden by the group's progress and playing a part
 * the group hasn't watched yet from anywhere but its card asks first; off, the group only tracks
 * progress and pace. [softLock] is the old Locked/Ask first choice, kept so older servers and apps
 * read it, and ignored (never reused for [locked]).
 */
data class GroupSchedule(
    val cadence: String = BINGE,
    val day: Int = 4,
    val hour: Int = 19,
    val minute: Int = 0,
    val softLock: Boolean = false,
    val together: Boolean = false,
    /** The group's recurring Group Watch Session (null: none). It holds nothing back. */
    val party: PartyTime? = null,
    /** "If someone plays ahead": false "Ask first" (the default), true "Don't allow". Only matters while [active]. */
    val locked: Boolean = false,
) {
    val limits get() = cadence == DAYS2 || cadence == DAYS3 || cadence == WEEKLY

    /** Whether it holds anything back: paced, or Together on. */
    val active get() = limits || together

    /** "Together" / "Together off". */
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

    /**
     * "Binge", "Every 2 days, 7pm", "Thursdays 7pm", with ", together" when Together is on,
     * ", locked" when playing ahead isn't allowed, and the Group Watch Session's time.
     */
    val label: String get() = when (cadence) {
        DAYS2 -> "Every 2 days, $clock"
        DAYS3 -> "Every 3 days, $clock"
        WEEKLY -> "${DAYS[day.coerceIn(0, 6)]}s $clock"
        else -> "Binge"
    } + (if (together) ", together" else "") + (if (locked && active) ", locked" else "") + (party?.let { ", Group Watch Session ${it.label}" } ?: "")

    /** "Ask first" / "Don't allow". */
    val playAhead: String get() = playAheadLabel(locked)

    /** The play-ahead choice's one line, for the pickers. */
    val lockNote: String get() = when {
        !locked -> LOCK_OFF
        together && limits -> LOCK_ON
        together -> "Don't allow: what the group hasn't watched yet plays only together (in a Group Watch Session, or in the same room)."
        else -> "Don't allow: a part not out yet can't be played until it opens."
    }

    /** "Group Watch Session Thursdays 8pm" (null without a time). */
    val partyLabel: String? get() = party?.let { "Group Watch Session ${it.label}" }

    /** Whether there's anything to keep: it holds something back, or the group has a party time. */
    val kept get() = active || party != null

    /** What accepting an invite agrees to, for the invite's text (empty when the schedule holds nothing back). */
    val terms: String get() {
        val meet = party?.let { " ${partyLabel}: the group meets then to watch its next part together." }.orEmpty()
        if (!active) return if (meet.isEmpty()) "" else "$meet Accepting agrees to it."
        val pace = if (limits) {
            " The group goes at its own pace: $label." +
                if (locked) " Playing ahead isn't allowed: each part opens on the schedule." else " Nothing is locked: playing ahead says when it opens."
        } else ""
        val with = if (together) {
            " Together is on: spoilers are hidden by the group's progress, and " +
                if (locked) "what the group hasn't watched yet plays only when you watch it together." else "playing something the group hasn't watched yet asks first."
        } else ""
        return "$pace$with$meet Accepting agrees to it."
    }

    /** The Together switch's one line, on or off (the setup's last step and the schedule pickers). */
    val togetherNote: String get() = if (together) TOGETHER_ON else TOGETHER_OFF

    /** The schedule picker's note under its choices. */
    val note: String get() =
        "Anything someone in the group has watched with it stays open, so whoever's behind can catch up." +
            (if (together) " Together: spoilers are hidden by the group's progress, and playing what the group hasn't watched yet from anywhere but the group's card asks first." else "") +
            (if (locked && active) " Playing ahead isn't allowed." else "") +
            " Only Nitrate keeps to it: Jellyfin's own apps don't know about it."

    companion object {
        const val BINGE = "binge"
        const val DAYS2 = "days2"
        const val DAYS3 = "days3"
        const val WEEKLY = "weekly"
        val CADENCES = listOf(BINGE to "Binge", DAYS2 to "Every 2 days", DAYS3 to "Every 3 days", WEEKLY to "Weekly")
        val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        /** What Together on means, in one line. */
        const val TOGETHER_ON = "Together on: spoilers are hidden by the group's progress, and playing what the group hasn't watched yet asks first."

        /** What Together off means, in one line. */
        const val TOGETHER_OFF = "Together off: the group keeps track of progress and pace; everyone watches when they like."

        /** The Together switch, as offered: off (the default) or on. */
        val HOW = listOf(false to "Together off", true to "Together")

        fun howLabel(together: Boolean) = HOW.first { it.first == together }.second

        /** The play-ahead choice's heading. */
        const val PLAY_AHEAD = "If someone plays ahead"

        /** The play-ahead choice, as offered: Ask first (the default) or Don't allow. */
        val LOCK_CHOICES = listOf(false to "Ask first", true to "Don't allow")

        fun playAheadLabel(locked: Boolean) = LOCK_CHOICES.first { it.first == locked }.second

        /** What Ask first means, in one line. */
        const val LOCK_OFF = "Ask first: playing ahead says when it opens and offers to watch it for yourself."

        /** What Don't allow means (paced, Together on), in one line. */
        const val LOCK_ON = "Don't allow: a part not out yet can't be played, and what the group hasn't watched yet plays only together."

        /** The Companion feature that keeps [locked]; older plugins drop it, so the choice isn't offered there. */
        const val LOCK_FEATURE = "schedulelock"

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
    /** "Will invites you to watch with them: Nitrate" (or just "… to watch with them" when nothing's playing yet). */
    val text: String get() = "$fromName invites you to watch with them" + (itemName?.let { ": $it" } ?: "")
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
    /** What the group watches ([seriesId] is the first). */
    val titles: List<GroupTitle> = listOf(GroupTitle(seriesId, seriesName)),
) {
    /** "Severance and The Bear". */
    val titleNames: String get() = WatchPartners.names(titles.map { it.name })
}

/** An invite the user sent: always just "waiting" until accepted or lapsed (nothing says whether the account exists). */
data class OutgoingRequest(
    val id: String,
    val to: String,
    val seriesId: String,
    val seriesName: String,
    val groupId: String?,
    val atMs: Long,
    val titles: List<GroupTitle> = listOf(GroupTitle(seriesId, seriesName)),
) {
    /** "Severance and The Bear". */
    val titleNames: String get() = WatchPartners.names(titles.map { it.name })
}

data class PartnersInfo(val groups: List<WatchGroup>, val incoming: List<IncomingRequest>, val outgoing: List<OutgoingRequest>) {
    /** The user's groups one of whose titles is [titleId]. */
    fun on(titleId: String) = groups.filter { it.holds(titleId) }
}

/** Where another member of one of the user's groups ([groupId]) is in one of its titles, by the group's record. */
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
     * The user's place in each group on the title by the group's own record (group id → the parts
     * watched as far as that group goes, [WatchGroup.ownRecord]). Groups without one (older
     * servers) go by [mine], Jellyfin's watched state.
     */
    val own: Map<String, Set<String>> = emptyMap(),
) {
    /** One film on its own (nothing to keep pace through, only who's seen it). */
    val single get() = order.size == 1

    /** What the user has watched as far as group [groupId] goes: its own record; else (older servers, or no group) Jellyfin's watched state ([mine]). */
    fun mineIn(groupId: String?): Set<String> = groupId?.let(own::get) ?: mine

    /** The first part (index in [order]) the user hasn't watched as far as group [groupId] goes; [order]'s size when all are. */
    fun nextIndexIn(groupId: String?): Int {
        val seen = mineIn(groupId)
        return order.indexOfFirst { it !in seen }.let { if (it < 0) order.size else it }
    }

    companion object {
        /**
         * [seriesId] is the group's title (a show, film, playlist or collection); [items] what it
         * holds, in order; [groups] the user's groups (those holding this title give [own]).
         */
        fun of(seriesId: String, items: List<MediaItem>, progress: List<PartnerProgress>, groups: List<WatchGroup> = emptyList()): KeepUp {
            val real = WatchPartners.countable(items)
            val noun = if (real.all { it.kind == ItemKind.EPISODE }) "episode" else "title"
            val ids = real.map { it.id }
            val own = groups.filter { it.holds(seriesId) && it.ownRecord != null }.associate { g -> g.id to g.ownRecord!!.filter { it in ids }.toSet() }
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

    /** Groups' own resume points (`setGroupResume`, `WatchGroup.resume`). */
    const val RESUME_FEATURE = "groupresume"

    /**
     * Whether [item]'s place in Continue Watching was left by a playback for a group: one of
     * [groups] has a resume point on it at least as recent as Jellyfin's last play (a minute's
     * slack for the reports' timing). A later solo play makes it the user's own again.
     */
    fun resumedForGroup(item: MediaItem, groups: List<WatchGroup>): WatchGroup? {
        val last = item.user.lastPlayedMs ?: return null
        return groups.filter { g -> g.resume[item.id]?.let { it.atMs >= last - 60_000 } == true }.maxByOrNull { it.resume.getValue(item.id).atMs }
    }

    /**
     * Continue Watching without what's half-watched with a group, when Home's Watching together
     * row shows it ([togetherShown]: one home for each). With that row hidden the part stays here
     * as the group's card (carrying [MediaItem.watchGroupId], so it resumes and counts for the group).
     */
    fun continueWatching(items: List<MediaItem>, groups: List<WatchGroup>, togetherShown: Boolean): List<MediaItem> = items.mapNotNull { item ->
        val g = resumedForGroup(item, groups) ?: return@mapNotNull item
        if (togetherShown) null else item.copy(watchGroupId = g.id, user = item.user.copy(positionMs = g.resume.getValue(item.id).positionMs))
    }

    /** [card] (a group's) resuming where the user left it with that group, or from the start: never Jellyfin's solo resume point. */
    fun withGroupResume(card: MediaItem, group: WatchGroup?): MediaItem {
        if (group == null || card.kind == ItemKind.SERIES || card.kind == ItemKind.PLAYLIST || card.kind == ItemKind.BOX_SET) return card
        return card.copy(user = card.user.copy(positionMs = group.resume[card.id]?.positionMs ?: 0))
    }

    /** An invite's titles the user's account can't open on this server (each looked up once; only "unavailable" counts, not a dropped connection). */
    suspend fun unopenable(server: dev.nitrate.core.MediaServer, titles: List<GroupTitle>): List<GroupTitle> =
        titles.filter { t -> runCatching { server.getItem(t.id) }.exceptionOrNull() is dev.nitrate.core.ItemUnavailable }

    /** What the invite says when some of its titles won't open for the user, or null. */
    fun unopenableNote(titles: List<GroupTitle>): String? = titles.takeIf { it.isNotEmpty() }?.let { t ->
        "You can't open ${names(t.map { it.name })} on this server: it's outside your account's libraries or parental rating. " +
            "You can still accept, but you won't be able to watch it until your server's admin changes that."
    }

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
     * The partners whose progress spoiler hiding goes by: only those of groups with Together on
     * (a group with it off only tracks progress and pace). [info] null (not loaded): all of them.
     */
    fun spoilerProgress(progress: List<PartnerProgress>, info: PartnersInfo?): List<PartnerProgress> {
        val together = info?.groups?.filter { it.together }?.map { it.id }?.toSet() ?: return progress
        return progress.filter { it.groupId in together }
    }

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
        // Each partner against the user's place in their group: its own record (older servers: Jellyfin's).
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
     * The user's groups for which [item] is their immediate next part, each by its own record
     * ([KeepUp.mineIn]: the first part of the title, in order, they haven't watched with the group).
     */
    fun nextIn(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): List<WatchGroup> {
        val title = TogetherRow.titleOf(item, standings) ?: return emptyList()
        val k = standings[title] ?: return emptyList()
        val at = k.order.indexOf(item.id).takeIf { it >= 0 } ?: return emptyList()
        return info?.on(title).orEmpty().filter { k.nextIndexIn(it.id) == at }
    }

    /**
     * The groups besides [groupId] for which [item] is also the user's immediate next part: after
     * a playback from [groupId]'s card finishes, the app offers "Just this group / All groups
     * where it's next" when there are any.
     */
    fun alsoNextIn(item: MediaItem, groupId: String, info: PartnersInfo?, standings: Map<String, KeepUp>): List<WatchGroup> =
        nextIn(item, info, standings).filter { it.id != groupId }

    /**
     * Which groups a playback of [item] counts for, by where it started ([origin]): from a group's
     * card, that group (with the others it's next in as [Counting.alsoNext]); started anywhere
     * else (Continue Watching, the library, search, downloads) or "Watch for myself", no group:
     * the group you came in through decides, nothing guesses. Live sessions are counted by the server for whoever's
     * there (the Companion's `TogetherRecorder`), not by this.
     */
    fun countingFor(origin: PlayOrigin, item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): Counting = when (origin) {
        is PlayOrigin.GroupCard -> {
            val g = info?.groups?.firstOrNull { it.id == origin.groupId }
            Counting(origin, listOfNotNull(g), g?.let { alsoNextIn(item, it.id, info, standings) }.orEmpty())
        }
        PlayOrigin.Elsewhere, PlayOrigin.ForMyself -> Counting(origin, emptyList())
    }

    /**
     * Who playing [item] counts with, for the player's chip and the cards: the user's own choice
     * ([with]: a group on the title, or just them); in a live session ([liveParticipants], names),
     * every group on the title with a member there; else, started anywhere but a group's card, just the user. Empty for a title
     * the user shares with nobody. [origin]: where the playback started (a group's card counts for
     * that group, "Watch for myself" for none), after the user's own choice and a live session.
     */
    fun contextFor(
        item: MediaItem,
        info: PartnersInfo?,
        standings: Map<String, KeepUp>,
        liveParticipants: List<String> = emptyList(),
        with: WatchingWith? = null,
        origin: PlayOrigin = PlayOrigin.Elsewhere,
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
        when (origin) {
            is PlayOrigin.GroupCard -> groups.firstOrNull { it.id == origin.groupId }?.let { return PlayContext(title, listOf(it), PlayContext.Reason.CARD) }
            PlayOrigin.ForMyself -> return PlayContext(title, emptyList(), PlayContext.Reason.CHOSEN)
            PlayOrigin.Elsewhere -> Unit
        }
        return PlayContext(title, emptyList(), PlayContext.Reason.NONE)
    }
}

/**
 * Where a playback started, which decides which groups it counts for ([WatchPartners.countingFor]):
 * a group's card on Home's Watching together row ([GroupCard], [MediaItem.watchGroupId]), anywhere
 * else ([Elsewhere]), or "Watch for myself" from a guard's question ([ForMyself]: no group).
 */
sealed interface PlayOrigin {
    data class GroupCard(val groupId: String) : PlayOrigin
    data object Elsewhere : PlayOrigin
    data object ForMyself : PlayOrigin

    companion object {
        /** Where playing [card] starts from: its group's card, else elsewhere. */
        fun of(card: MediaItem): PlayOrigin = card.watchGroupId?.let(::GroupCard) ?: Elsewhere

        /**
         * Where a play from a details page opened from group [groupId]'s card starts (null
         * [groupId]: opened any other way): that group's card when [item] is a part of one of the
         * group's titles (the title holding it by [standings]), like playing the card itself; else
         * null (elsewhere: a trailer, an extra, something similar). [info] not loaded yet: the card's group.
         */
        fun fromDetails(groupId: String?, item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): PlayOrigin? {
            if (groupId == null) return null
            if (info == null) return GroupCard(groupId)
            val g = info.groups.firstOrNull { it.id == groupId } ?: return null
            val title = TogetherRow.titleOf(item, standings) ?: return null
            return if (g.holds(title)) GroupCard(groupId) else null
        }
    }
}

/**
 * What a playback counts for ([WatchPartners.countingFor]): [groups] (empty: no group), and from a
 * group's card the other groups it's also next in ([alsoNext]). The app records it when the
 * playback finishes (Jellyfin's played threshold) with `MediaServer.markForGroups`.
 */
data class Counting(val origin: PlayOrigin, val groups: List<WatchGroup>, val alsoNext: List<WatchGroup> = emptyList()) {
    /** From a group's card and next in other groups too: ask "Just this group / All groups where it's next" when it finishes. */
    val asksAll: Boolean get() = origin is PlayOrigin.GroupCard && groups.isNotEmpty() && alsoNext.isNotEmpty()

    /** The group ids to record it for ([all]: with [alsoNext], "All groups where it's next"). */
    fun recordFor(all: Boolean = false): List<String> = (groups + if (all) alsoNext else emptyList()).map { it.id }.distinct()
}

/** Who a playback counts with, as the user chose it (the player's chip): one of their watch groups, or just them. */
sealed interface WatchingWith {
    /** Watching on their own: it counts for no group. */
    data object JustMe : WatchingWith

    /** Watching with group [groupId]. */
    data class Group(val groupId: String) : WatchingWith
}

/**
 * Who a playback of a part of a shared title counts with: [groups] (empty: just the user, or no
 * group on [titleId]), and why ([reason]).
 */
data class PlayContext(val titleId: String?, val groups: List<WatchGroup>, val reason: Reason) {
    enum class Reason {
        /** The user chose it ([WatchingWith]). */
        CHOSEN,

        /** In a live (SyncPlay) session with members of these groups. */
        LIVE,

        /** Played from this group's card ([PlayOrigin.GroupCard]). */
        CARD,

        /** No group: none on the title, or started anywhere but a group's card. */
        NONE,
    }

    val justMe get() = groups.isEmpty()

    /** "With Sam", "With Sam and Kim", "Just me". */
    val label: String get() = if (groups.isEmpty()) "Just me" else "With " + WatchPartners.names(groups.flatMap { g -> g.others.map { it.name } }.distinct())
}

/**
 * Why playing a part of a shared title asks first: it isn't out yet by [group]'s schedule, or
 * ([together]) [group] has Together on and hasn't watched it yet. Nothing is ever refused.
 */
data class PartLock(val group: WatchGroup, val together: Boolean, val opens: String? = null) {
    /** The card's word in place of the user's standing: "Opens Thursday 7pm", "Opens later", "Watch together". */
    val word: String get() = if (together) WATCH_TOGETHER else opens?.let { "Opens $it" } ?: "Opens later"

    companion object {
        const val WATCH_TOGETHER = "Watch together"

        /**
         * What asks first about [itemId] (a part of the title in [order]) among [groups]: not out
         * yet comes first, then a Together group that hasn't watched it ([together] false: only
         * not out yet, as from a group's own card).
         */
        fun of(itemId: String, order: List<String>, groups: List<WatchGroup>, together: Boolean = true): PartLock? {
            groups.firstOrNull { it.schedule?.limits == true && it.locks(itemId, order) }?.let { return PartLock(it, together = false, opens = it.opensLabelFor(itemId, order)) }
            if (together) groups.firstOrNull { it.needsTogether(itemId, order) }?.let { return PartLock(it, together = true) }
            return null
        }

        /**
         * What asks first about [item], as its card and spoiler hiding see it: on a group's card
         * ([MediaItem.watchGroupId]) that group's schedule only; anywhere else, every group on its
         * title (a part its schedule hasn't let out, in any group; one a Together group hasn't watched).
         */
        fun of(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): PartLock? {
            if (info == null || info.groups.none { it.schedule?.active == true }) return null
            val title = TogetherRow.titleOf(item, standings) ?: return null
            val order = standings[title]?.order ?: return null
            item.watchGroupId?.let { id -> return of(item.id, order, info.groups.filter { it.id == id }, together = false) }
            return of(item.id, order, info.on(title))
        }
    }
}

/**
 * The watch-partners plugin. Before a part of a shared title plays it asks first when it isn't
 * out yet by a group's schedule, or when a group with Together on hasn't watched it yet and it's
 * played from anywhere but that group's card ("Start a Group Watch Session", "Watch for myself", "Not
 * now"); a group whose schedule doesn't allow playing ahead ([GroupSchedule.locked]) refuses what
 * isn't out yet and, with Together on, plays what it hasn't watched only together. Home's "Watching together" row ([TogetherRow]) and the cards'
 * lines keep everyone in step. Its switch turns the whole feature on or off.
 */
class WatchPartnersPlugin : PlaybackGuard {
    override val id = ID
    override val name = "Watch groups"
    override val description = "Keeps what you watch with others in step (shows, films, playlists and collections): groups who see each other's progress, " +
        "schedules that pace what the group hasn't watched yet, and a Home row of where everyone is. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    private fun key(host: PluginHost, titleId: String) = "${runCatching { host.server.userId }.getOrNull()}|$titleId"

    /** "Counts for" as chosen on the player's chip, per account and title, for this run of the app: later parts play with it too. */
    private val chosen = kotlinx.coroutines.flow.MutableStateFlow<Map<String, WatchingWith>>(emptyMap())

    /** "We're in the same room", per account and title, for this run of the app. */
    private val rooms = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SameRoom>>(emptyMap())

    /** Where the guard's answer says an item now plays from ("Watch for myself", "Start a Group Watch Session"), per account and item, until it's asked for. */
    private val answered = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PlayOrigin>>(emptyMap())

    /** Who the user chose to watch [titleId] with (the player's chip), if they did. */
    fun chosenOn(host: PluginHost, titleId: String): WatchingWith? = chosen.value[key(host, titleId)]

    /** The user chose who [titleId] counts with ("Counts for": a group, or just them). */
    fun choose(host: PluginHost, titleId: String, with: WatchingWith) {
        chosen.update { it + (key(host, titleId) to with) }
    }

    /** Who's in the room watching [titleId] with the user, if anyone. */
    fun roomOn(host: PluginHost, titleId: String): SameRoom? = rooms.value[key(host, titleId)]

    /** "We're in the same room" for [titleId] ([room]; null: no longer), which also counts it for that group. */
    fun setRoom(host: PluginHost, titleId: String, room: SameRoom?) {
        rooms.update { if (room == null || room.present.isEmpty()) it - key(host, titleId) else it + (key(host, titleId) to room) }
        if (room != null && room.present.isNotEmpty()) choose(host, titleId, WatchingWith.Group(room.groupId))
    }

    /** Where the user's current (or last) playback of each title started, per account and title: Up Next carries it on. */
    private val playing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PlayOrigin>>(emptyMap())

    /**
     * Where playing [item] really starts from: what the user answered the guard's question with
     * ("Watch for myself": [PlayOrigin.ForMyself]; "Start a Group Watch Session", "We're in the same room":
     * that group's card), else [given] (the card it was played from), else elsewhere. The answer is used once.
     */
    fun originOf(host: PluginHost, item: MediaItem, given: PlayOrigin? = null): PlayOrigin =
        takeOrigin(host, item) ?: given ?: PlayOrigin.Elsewhere

    /**
     * The player takes where [item]'s playback started, as the guards left it (the card it was
     * played from, or the guard's answer); null when nothing was said (a queue, a playback the
     * guards never saw). Used once: the player keeps it for the playback and its Up Next.
     */
    fun takeOrigin(host: PluginHost, item: MediaItem): PlayOrigin? {
        val k = key(host, "item:" + item.id)
        val said = answered.value[k] ?: return null
        answered.update { it - k }
        return said
    }

    /** The player says where its playback of [titleId] started ([origin]), so Up Next in the same title carries on from there. */
    fun nowPlaying(host: PluginHost, titleId: String, origin: PlayOrigin) {
        playing.update { it + (key(host, titleId) to origin) }
    }

    /**
     * Where Up Next's [next] plays from: the same title's playback carries on from where it
     * started (a group's card, or "for myself"); null when [next] is another title or it started elsewhere.
     */
    fun upNextOrigin(host: PluginHost, next: MediaItem, standings: Map<String, KeepUp>): PlayOrigin? {
        val title = TogetherRow.titleOf(next, standings) ?: return null
        return playing.value[key(host, title)]?.takeIf { it != PlayOrigin.Elsewhere }
    }

    override fun cancelled(item: MediaItem, host: PluginHost) {
        answered.update { it - key(host, "item:" + item.id) }
    }

    private fun answer(host: PluginHost, item: MediaItem, origin: PlayOrigin) {
        answered.update { it + (key(host, "item:" + item.id) to origin) }
    }

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision = beforePlay(item, host, null)

    /** The watch groups (ids) this device started a live session for ("Start a Group Watch Session"), per account, with that session's id. */
    private val liveFor = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * This device started live session [sessionId] (a SyncPlay group) for watch group [groupId]
     * ("Start a Group Watch Session", a Group Watch Session): while it's in that session, playing what the
     * group hasn't watched counts as watching together, even before anyone else has joined.
     */
    fun watchingNow(host: PluginHost, groupId: String, sessionId: String) {
        liveFor.update { it + (key(host, "live:$groupId") to sessionId) }
    }

    /** How strict one group is about a playback, strictest last. */
    private enum class Strictness { ASK_TOGETHER, ASK_NOT_OUT, TOGETHER_ONLY, REFUSE }

    private data class Verdict(val level: Strictness, val group: WatchGroup)

    /**
     * Ask first (the schedule's default): from a group's card ([origin] [PlayOrigin.GroupCard]),
     * only that group's schedule asks, when the part isn't out yet ("Opens Thursday 7pm": Watch for
     * myself / Not now). From anywhere else: each group on the title asks when the part isn't out
     * yet, and a group with Together on when it hasn't watched it ("Start a Group Watch Session", "We're in
     * the same room", "Watch for myself", "Not now"); not while the user is in a live session or
     * the same room with someone from that group. "Watch for myself" asks nothing more.
     *
     * Don't allow ([WatchGroup.locked]), from anywhere: a part the group's schedule hasn't let out
     * is refused ("Opens Thursday 7pm", OK only), and with Together on a part the group hasn't
     * watched plays only together ("Start a Group Watch Session", "We're in the same room", "Not now")
     * unless someone from the group is in the session or the room already. Catching up to what the
     * group has watched, and rewatching (the user has seen it), stay free of the lock. With several
     * groups on the title the strictest decides, and its question names it.
     */
    override suspend fun beforePlay(item: MediaItem, host: PluginHost, origin: PlayOrigin?): GuardDecision {
        // Handed to the player ([takeOrigin]); an answer to the question below takes its place.
        if (origin != null) answer(host, item, origin) else cancelled(item, host)
        if (!item.isPlayable) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val info = runCatching { host.server.partners() }.getOrNull() ?: return GuardDecision.Allow
        val card = (origin as? PlayOrigin.GroupCard)?.groupId
        val mine = origin == PlayOrigin.ForMyself
        fun held(g: WatchGroup) = g.schedule?.limits == true && g.titles.any { it.lockedFromId != null }
        fun unseen(g: WatchGroup) = g.together && g.titles.any { it.togetherFromId != null }
        val asking = info.groups.filter { g ->
            when {
                !held(g) && !unseen(g) -> false
                g.locked -> true
                mine -> false
                card != null -> g.id == card && held(g)
                else -> true
            }
        }
        if (asking.isEmpty()) return GuardDecision.Allow
        val together = host.together
        val present = together?.participants().orEmpty().map { it.lowercase() }.toSet()
        val session = runCatching { together?.sessionId() }.getOrNull()
        for (title in asking.flatMap { g -> g.titles.map { it.id } }.distinct()) {
            val items = runCatching { host.server.titleItems(title) }.getOrNull() ?: continue
            val order = WatchPartners.countable(items).map { it.id }
            if (item.id !in order) continue
            val room = roomOn(host, title)
            val verdicts = asking.filter { it.holds(title) }.mapNotNull { g ->
                // With someone from the group, in a live session or the same room (or a session started for it): that's watching together.
                val withThem = g.others.any { it.name.lowercase() in present || (room?.groupId == g.id && it.id in room.present) } ||
                    (session != null && liveFor.value[key(host, "live:${g.id}")] == session)
                verdict(g, item, order, card, mine, withThem)
            }
            // The strictest decides; among equals, the first group.
            val v = verdicts.maxByOrNull { it.level.ordinal } ?: continue
            return when (v.level) {
                Strictness.REFUSE -> refused(item, v.group, order)
                Strictness.TOGETHER_ONLY -> unwatched(item, v.group, title, host, alone = false)
                Strictness.ASK_NOT_OUT -> notOutYet(item, PartLock(v.group, together = false, opens = v.group.opensLabelFor(item.id, order)), host)
                Strictness.ASK_TOGETHER -> unwatched(item, v.group, title, host, alone = true)
            }
        }
        return GuardDecision.Allow
    }

    private fun verdict(g: WatchGroup, item: MediaItem, order: List<String>, card: String?, mine: Boolean, withThem: Boolean): Verdict? {
        val notOut = g.schedule?.limits == true && g.locks(item.id, order)
        val unseen = g.needsTogether(item.id, order)
        // Don't allow (rewatching what the user has seen stays free of it: asked as Ask first).
        if (g.locked && !item.user.played) return when {
            notOut -> Verdict(Strictness.REFUSE, g)
            unseen && !withThem -> Verdict(Strictness.TOGETHER_ONLY, g)
            else -> null
        }
        if (mine) return null
        if (card != null) return if (card == g.id && notOut) Verdict(Strictness.ASK_NOT_OUT, g) else null
        if (withThem) return null
        return when {
            notOut -> Verdict(Strictness.ASK_NOT_OUT, g)
            unseen -> Verdict(Strictness.ASK_TOGETHER, g)
            else -> null
        }
    }

    private fun forMyself(item: MediaItem, host: PluginHost) =
        GuardChoice("Watch for myself", GuardAction.RunThenPlay { answer(host, item, PlayOrigin.ForMyself) })

    private fun notOutYet(item: MediaItem, lock: PartLock, host: PluginHost): GuardDecision.Ask {
        val g = lock.group
        val s = g.schedule!!
        val opens = lock.opens ?: "once the group has watched the one before"
        val what = item.episodeLabel ?: item.name
        return GuardDecision.Ask(
            title = lock.word,
            message = "$what isn't out yet for your group with ${g.label}: it follows the group's schedule (${s.label}) and opens $opens. " +
                "Watching it for yourself now counts for no group.",
            choices = listOf(forMyself(item, host), GuardChoice("Not now", GuardAction.Cancel)),
        )
    }

    /** Don't allow, not out yet: "Opens Thursday 7pm", why, and only OK. */
    private fun refused(item: MediaItem, g: WatchGroup, order: List<String>): GuardDecision.Ask {
        val lock = PartLock(g, together = false, opens = g.opensLabelFor(item.id, order))
        val opens = lock.opens ?: "once the group has watched the one before"
        val what = item.episodeLabel ?: item.name
        return GuardDecision.Ask(
            title = lock.word,
            message = "$what isn't out yet for your group with ${g.label}. The group's schedule (${g.schedule!!.label}) doesn't allow playing ahead: " +
                "it opens $opens. Most of the group can agree to change the schedule.",
            choices = listOf(GuardChoice(OK, GuardAction.Cancel)),
        )
    }

    /**
     * "Tommy & Suzy haven't watched this yet": Start a Group Watch Session (for the group),
     * We're in the same room (everyone in the group here; it counts for them too, changeable in
     * the player), Watch for myself (no group; not when the group's schedule doesn't allow it,
     * [alone] false), Not now.
     */
    private fun unwatched(item: MediaItem, g: WatchGroup, titleId: String, host: PluginHost, alone: Boolean): GuardDecision.Ask {
        val what = item.episodeLabel ?: item.name
        val together = host.together?.takeIf { it.available() }?.let { t ->
            GuardChoice(START_SESSION, GuardAction.RunThenPlay(playIfFailed = false) {
                t.start(item, g.id, g.others.map { it.id }, g.label)
                answer(host, item, PlayOrigin.GroupCard(g.id))
            })
        }
        val room = GuardChoice("We're in the same room", GuardAction.RunThenPlay {
            setRoom(host, titleId, SameRoom(g.id, g.others.map { it.id }))
            answer(host, item, PlayOrigin.GroupCard(g.id))
        })
        return GuardDecision.Ask(
            title = unwatchedTitle(g),
            message = if (alone) "You watch $what together with ${g.label}. Start a Group Watch Session, watch here in the same room, " +
                "or for yourself (it counts for no group)."
            else "You watch $what together with ${g.label}, and the group's schedule (${g.schedule!!.label}) keeps what it hasn't watched yet " +
                "for watching together. Start a Group Watch Session, or watch here in the same room.",
            choices = listOfNotNull(together, room, if (alone) forMyself(item, host) else null, GuardChoice("Not now", GuardAction.Cancel)),
        )
    }

    companion object {
        const val ID = "watch-partners"

        /** The only choice when a group's schedule refuses a playback. */
        const val OK = "OK"

        /** The guard's choice that starts a Group Watch Session for the group. */
        const val START_SESSION = "Start a Group Watch Session"

        /** "Tommy & Suzy haven't watched this yet", "Tommy hasn't watched this yet". */
        fun unwatchedTitle(g: WatchGroup): String = WithChip.nameOf(g) + (if (g.others.size == 1) " hasn't" else " haven't") + " watched this yet"
    }
}
