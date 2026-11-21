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
) {
    /** "Sam", "Frank, Tommy and Jane". */
    val label get() = WatchPartners.names(others.map { it.name })

    /** Whether [itemId] is locked by the schedule, given the title's parts in [order]. */
    fun locks(itemId: String, order: List<String>): Boolean {
        val from = lockedFromId?.let(order::indexOf)?.takeIf { it >= 0 } ?: return false
        val at = order.indexOf(itemId)
        return at >= from
    }

    /** When the first locked part opens: "Thursday 7pm". */
    val opensLabel: String? get() = schedule?.let { s -> opensDay?.let { "${GroupSchedule.DAYS[it]} ${s.clock}" } }
}

/**
 * How a group lets its title out: all at once ([BINGE]), or one new part every 2 days, every 3
 * days or weekly on [day], at [hour]:[minute] in the server's time zone. Anything someone in the
 * group has seen always stays open; only what nobody's seen yet is rationed. Locked parts can't
 * be played in Nitrate, or ([softLock]) only after a warning.
 */
data class GroupSchedule(
    val cadence: String = BINGE,
    val day: Int = 4,
    val hour: Int = 19,
    val minute: Int = 0,
    val softLock: Boolean = false,
) {
    val limits get() = cadence == DAYS2 || cadence == DAYS3 || cadence == WEEKLY

    /** "7pm", "7:30pm". */
    val clock: String get() = clockOf(hour, minute)

    /** "Binge", "Every 2 days, 7pm", "Thursdays 7pm", with ", ask first" for a soft lock. */
    val label: String get() = when (cadence) {
        DAYS2 -> "Every 2 days, $clock"
        DAYS3 -> "Every 3 days, $clock"
        WEEKLY -> "${DAYS[day.coerceIn(0, 6)]}s $clock"
        else -> "Binge"
    } + if (limits && softLock) ", ask first" else ""

    companion object {
        const val BINGE = "binge"
        const val DAYS2 = "days2"
        const val DAYS3 = "days3"
        const val WEEKLY = "weekly"
        val CADENCES = listOf(BINGE to "Binge", DAYS2 to "Every 2 days", DAYS3 to "Every 3 days", WEEKLY to "Weekly")
        val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

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
 * The watch-partners plugin. It doesn't stand between the user and Play, except for a group's
 * schedule: a part of a title the group hasn't let out yet can't be played in Nitrate (a hard
 * lock), or only after a warning (a soft lock). Home's "Watching together" row ([TogetherRow])
 * and the cards' lines keep everyone in step. Its switch turns the whole feature on or off.
 */
class WatchPartnersPlugin : PlaybackGuard {
    override val id = ID
    override val name = "Watch partners"
    override val description = "Keeps what you watch with others in step (shows, films, playlists and collections): groups who see each other's progress, " +
        "schedules that let a title out one part at a time, and a Home row of where everyone is. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision {
        if (!item.isPlayable) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val scheduled = runCatching { host.server.partners() }.getOrNull()?.groups.orEmpty().filter { it.schedule?.limits == true && it.lockedFromId != null }
        for (g in scheduled) {
            val order = runCatching { WatchPartners.countable(host.server.titleItems(g.seriesId)).map { it.id } }.getOrNull() ?: continue
            if (!g.locks(item.id, order)) continue
            val s = g.schedule ?: continue
            val opens = g.opensLabel ?: s.label
            val what = item.episodeLabel ?: item.name
            val message = "$what isn't out yet for your group with ${g.label}: ${g.seriesName} follows the group's schedule (${s.label}). It opens $opens."
            return GuardDecision.Ask(
                title = "Opens $opens",
                message = message,
                choices = if (s.softLock) listOf(GuardChoice("Watch anyway", GuardAction.PlayAnyway), GuardChoice("Not now", GuardAction.Cancel))
                else listOf(GuardChoice("OK", GuardAction.Cancel)),
            )
        }
        return GuardDecision.Allow
    }

    companion object {
        const val ID = "watch-partners"
    }
}
