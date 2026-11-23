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
            " You'd watch it together only: anything nobody in the group has seen yet plays in Nitrate only while watching together with someone from the group" +
                (if (softLock) " (or after a warning)." else ".")
        } else ""
        return "$pace$with Accepting agrees to it."
    }

    /** The schedule picker's note under its choices. */
    val note: String get() =
        "Anything someone in the group has seen stays open, so whoever's behind can catch up." +
            (if (together) " What nobody has seen yet is for watching together: in Nitrate it plays only in a watch-together session with someone from the group." else "") +
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
    /** "Will invited you to watch Nitrate together" (or "to watch together" when nothing's playing yet). */
    val text: String get() = "$fromName invited you to watch " + (itemName?.let { "$it together" } ?: "together")
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
) {
    /** One film on its own (nothing to keep pace through, only who's seen it). */
    val single get() = order.size == 1

    companion object {
        /** [seriesId] is the group's title (a show, film, playlist or collection); [items] what it holds, in order. */
        fun of(seriesId: String, items: List<MediaItem>, progress: List<PartnerProgress>): KeepUp {
            val real = WatchPartners.countable(items)
            val noun = if (real.all { it.kind == ItemKind.EPISODE }) "episode" else "title"
            return KeepUp(seriesId, real.map { it.id }, real.filter { it.user.played }.map { it.id }.toSet(), progress, noun)
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
    fun standing(item: MediaItem, k: KeepUp): Standing? {
        val members = k.progress.distinctBy { it.userId }
        if (members.isEmpty()) return null
        val index = k.order.withIndex().associate { it.value to it.index + 1 }
        fun reach(watched: Set<String>) = watched.maxOfOrNull { index[it] ?: 0 } ?: 0
        val me = reach(k.mine)
        val deltas = members.map { it to reach(it.watched) - me }.sortedByDescending { it.second }
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
        val myWord = if (k.single) (if (k.order.first() in k.mine) "Seen" else if (ahead) "Not seen" else "Not yet") else word
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
}

/**
 * Why the user can't freely play a part of a shared title now: it isn't out yet for [group]
 * (its schedule rations the title), or ([together]) [group] watches together only and nobody in
 * it has seen the part yet.
 */
data class PartLock(val group: WatchGroup, val together: Boolean) {
    /** When it opens ("Thursday 7pm"), for a part not out yet; null while it waits on someone watching the open one. */
    val opens: String? get() = if (together) null else group.opensLabel

    /** The card's word in place of the user's standing: "Opens Thursday 7pm", "Opens later", "Watch together". */
    val word: String get() = if (together) WATCH_TOGETHER else opens?.let { "Opens $it" } ?: "Opens later"

    companion object {
        const val WATCH_TOGETHER = "Watch together"

        /**
         * The lock on [itemId] (a part of the title in [order]) from any of [groups] (the user's
         * groups on that title): not out yet comes first, then together only.
         */
        fun of(itemId: String, order: List<String>, groups: List<WatchGroup>): PartLock? {
            groups.firstOrNull { it.schedule?.limits == true && it.locks(itemId, order) }?.let { return PartLock(it, together = false) }
            groups.firstOrNull { it.needsTogether(itemId, order) }?.let { return PartLock(it, together = true) }
            return null
        }

        /** The lock on [item], as cards and spoiler hiding see it (the user's groups and where they stand). */
        fun of(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): PartLock? {
            if (info == null || info.groups.none { it.schedule?.active == true }) return null
            val title = TogetherRow.titleOf(item, standings) ?: return null
            val order = standings[title]?.order ?: return null
            return of(item.id, order, info.on(title))
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
    override val name = "Watch partners"
    override val description = "Keeps what you watch with others in step (shows, films, playlists and collections): groups who see each other's progress, " +
        "schedules that let a title out one part at a time or keep it for watching together, and a Home row of where everyone is. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision {
        if (!item.isPlayable) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val held = runCatching { host.server.partners() }.getOrNull()?.groups.orEmpty().filter {
            (it.schedule?.limits == true && it.lockedFromId != null) || (it.schedule?.together == true && it.togetherFromId != null)
        }
        if (held.isEmpty()) return GuardDecision.Allow
        val orders = held.map { it.seriesId }.distinct().associateWith { title ->
            runCatching { WatchPartners.countable(host.server.titleItems(title)).map { it.id } }.getOrNull()
        }
        val locks = held.mapNotNull { g -> orders[g.seriesId]?.let { PartLock.of(item.id, it, listOf(g)) } }
        // Not out yet: nothing gets it out early, watching together or not.
        locks.firstOrNull { !it.together }?.let { return notOutYet(item, it.group) }
        // Together only: fine in a watch-together session with someone from that group.
        val present = host.together?.participants().orEmpty().map { it.lowercase() }.toSet()
        val alone = locks.firstOrNull { l -> l.group.others.none { it.name.lowercase() in present } } ?: return GuardDecision.Allow
        return togetherOnly(item, alone.group, host)
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
            GuardChoice("Watch it together now", GuardAction.RunThenPlay(playIfFailed = false) { t.start(item, g.id, g.others.map { it.id }, g.label) })
        }
        return GuardDecision.Ask(
            title = PartLock.WATCH_TOGETHER,
            message = "Nobody in your group with ${g.label} has seen $what yet, and you watch ${g.seriesName} together only: " +
                "play it in a watch-together session with them. Catching up and rewatching are always free.",
            choices = listOfNotNull(
                together,
                GuardChoice("Watch anyway", GuardAction.PlayAnyway).takeIf { g.schedule?.softLock == true },
                GuardChoice("Not now", GuardAction.Cancel),
            ),
        )
    }

    companion object {
        const val ID = "watch-partners"
    }
}
