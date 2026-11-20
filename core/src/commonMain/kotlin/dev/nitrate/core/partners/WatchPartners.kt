package dev.nitrate.core.partners

import dev.nitrate.core.MediaServer
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
data class WatchGroup(val id: String, val seriesId: String, val seriesName: String, val others: List<UserRef>, val sinceMs: Long) {
    /** "Sam", "Frank, Tommy and Jane". */
    val label get() = WatchPartners.names(others.map { it.name })
}

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
data class KeepUp(val seriesId: String, val order: List<String>, val mine: Set<String>, val progress: List<PartnerProgress>) {
    companion object {
        fun of(seriesId: String, episodes: List<MediaItem>, progress: List<PartnerProgress>): KeepUp {
            val real = episodes.filter { !it.isMissing && (it.seasonNumber ?: 0) != 0 }
            return KeepUp(seriesId, real.map { it.id }, real.filter { it.user.played }.map { it.id }.toSet(), progress)
        }
    }
}

/** A card's line about a shared show ([short]) and the summary's sentence ([long]); [catchUp]: the user is behind. */
data class Standing(val short: String, val long: String, val catchUp: Boolean)

/** Whether the user is private (never listed, not findable by name), and whether an admin made it so. */
data class Privacy(val isPrivate: Boolean, val byAdmin: Boolean)

/**
 * Watching a show with one of the user's groups, as chosen before playing: for a while every
 * episode of it the user finishes is also marked watched for the group's other members,
 * including ones that play on by themselves.
 */
data class TogetherSession(val seriesId: String, val groupId: String, val names: List<String>, val untilMs: Long, val known: Set<String>)

/** Watch partners logic: who's behind, what to warn about, what to mark. */
object WatchPartners {
    /** How long "watching with" lasts after it's chosen. */
    const val TOGETHER_MS = 8 * 60 * 60_000L

    /** The Companion feature. */
    const val FEATURE = "partners"

    /** Partners who haven't seen [episode]. */
    fun behind(episode: MediaItem, progress: List<PartnerProgress>): List<PartnerProgress> = progress.filter { episode.id !in it.watched }

    /** Each group in which someone hasn't seen [episode]: the group and all its other members, in order met. */
    fun groupsBehind(episode: MediaItem, progress: List<PartnerProgress>): List<Pair<String, List<PartnerProgress>>> =
        progress.groupBy { it.groupId }.filterValues { members -> members.any { episode.id !in it.watched } }.toList()

    /** Up next together per group: the first episode someone in the group (the user included) hasn't seen. */
    fun nextPerGroup(episodes: List<MediaItem>, progress: List<PartnerProgress>): List<MediaItem> =
        progress.groupBy { it.groupId }.values.mapNotNull { nextTogether(episodes, it) }.distinctBy { it.id }

    /** "Sam", "Sam and Kim", "Sam, Kim and Lee". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Spoiler hiding goes by whoever is furthest behind: an episode's description stays hidden
     * while the user or any partner on the show hasn't seen it.
     */
    fun hidesDescription(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>): Boolean =
        prefs.hideSpoilers && item.kind == ItemKind.EPISODE && (!item.user.played || progress.any { item.id !in it.watched })

    /** Blurs an episode still by the same rule. */
    fun isSpoiler(prefs: DisplayPrefs, item: MediaItem, progress: List<PartnerProgress>): Boolean = hidesDescription(prefs, item, progress)

    /**
     * Up next together for a show: the first episode (in order) someone hasn't seen yet, the
     * user or a partner. Null when everyone's seen everything.
     */
    fun nextTogether(episodes: List<MediaItem>, progress: List<PartnerProgress>): MediaItem? =
        episodes.filter { !it.isMissing && (it.seasonNumber ?: 0) != 0 }.firstOrNull { e -> !e.user.played || progress.any { e.id !in it.watched } }

    /**
     * Where the user stands with their group(s) for [item] (an episode or the show): someone ahead
     * (catch up), an episode nobody's seen (watch it together), the user ahead, or in step.
     */
    fun standing(item: MediaItem, k: KeepUp): Standing? {
        val members = k.progress.distinctBy { it.userId }
        if (members.isEmpty()) return null
        val inShow = k.order.toSet()
        fun ahead(p: PartnerProgress) = (p.watched intersect inShow).count { it !in k.mine }
        fun behindBy(p: PartnerProgress) = k.mine.count { it in inShow && it !in p.watched }
        val ahead = members.map { it to ahead(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (ahead.isNotEmpty()) {
            val (first, n) = ahead.first()
            val short = if (ahead.size == 1) "${first.userName} is $n ahead" else "${names(ahead.map { it.first.userName })} are ahead"
            val detail = ahead.joinToString(", ") { (p, c) -> "${p.userName} by $c" }
            val episodes = if (n == 1) "1 episode" else "$n episodes"
            return Standing(short, "Catch up with your group: $episodes to go ($detail)", catchUp = true)
        }
        if (item.kind == ItemKind.EPISODE && item.id !in k.mine) {
            val waiting = members.filter { item.id !in it.watched }
            if (waiting.isNotEmpty()) {
                val who = names(waiting.map { it.userName })
                return Standing("Watch with $who", "$who ${if (waiting.size == 1) "hasn't" else "haven't"} seen this yet either: watch it together", catchUp = false)
            }
        }
        val behind = members.map { it to behindBy(it) }.filter { it.second > 0 }
        if (behind.isNotEmpty()) {
            val who = names(behind.map { it.first.userName })
            return Standing("$who catching up", "You're ahead: $who ${if (behind.size == 1) "is" else "are"} catching up (${behind.joinToString(", ") { (p, c) -> "${p.userName} by $c" }})", catchUp = false)
        }
        val who = names(members.map { it.userName })
        return Standing("In step with $who", "You and $who are at the same place", catchUp = false)
    }

    /** The episodes of a together session newly finished: played now, not when it started. */
    fun newlyWatched(session: TogetherSession, episodes: List<MediaItem>): List<MediaItem> =
        episodes.filter { it.user.played && it.id !in session.known }
}

/** Sessions of watching with partners, kept on the device for a few hours (safe from any thread). */
class TogetherSessions(private val now: () -> Long) {
    private val sessions = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, TogetherSession>())

    fun active(seriesId: String): TogetherSession? = sessions.value[seriesId]?.takeIf { it.untilMs > now() }

    fun all(): List<TogetherSession> {
        val t = now()
        sessions.update { m -> m.filterValues { it.untilMs > t } }
        return sessions.value.values.toList()
    }

    fun put(session: TogetherSession) = sessions.update { it + (session.seriesId to session) }

    fun stop(seriesId: String) = sessions.update { it - seriesId }

    /** Forgets them all: they belong to the account that chose them, not the next one on this device. */
    fun clear() = sessions.update { emptyMap() }
}

/**
 * The watch-partners plugin: before an episode of a shared show plays, if a partner hasn't seen
 * it, asks whether the user is watching with them (then it counts for both) or alone.
 */
class WatchPartnersPlugin(val sessions: TogetherSessions, private val now: () -> Long) : PlaybackGuard {
    override val id = "watch-partners"
    override val name = "Watch partners"
    override val description = "Keeps shows you watch with others in step: groups per show who see each other's progress, " +
        "a check before you watch ahead alone, and episodes watched together counted for everyone. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision {
        val series = item.seriesId ?: return GuardDecision.Allow
        if (item.kind != ItemKind.EPISODE) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val progress = host.server.partnerProgress(series)
        val groups = WatchPartners.groupsBehind(item, progress)
        if (groups.isEmpty()) return GuardDecision.Allow
        // Already watching this show with a group: carry on.
        if (sessions.active(series) != null) return GuardDecision.Allow
        val behind = WatchPartners.behind(item, progress).distinctBy { it.userId }
        val who = WatchPartners.names(behind.map { it.userName })
        val where = behind.joinToString(" · ") { "${it.userName}: ${it.last ?: "not started"}" }
        return GuardDecision.Ask(
            title = "$who ${if (behind.size == 1) "hasn't" else "haven't"} seen this yet",
            message = "${item.episodeLabel ?: item.name} · $where. Watching with a group counts it for everyone in it, and for the next episodes too.",
            choices = groups.map { (groupId, members) ->
                val names = members.map { it.userName }
                GuardChoice("Watching with ${WatchPartners.names(names)}", GuardAction.RunThenPlay { start(host.server, series, groupId, names) })
            } + listOf(
                GuardChoice("Just me", GuardAction.PlayAnyway),
                GuardChoice("Cancel", GuardAction.Cancel),
            ),
        )
    }

    /** Starts watching [seriesId] with a group: what the user has already seen doesn't count. */
    suspend fun start(server: MediaServer, seriesId: String, groupId: String, names: List<String>) {
        val seen = runCatching { server.allEpisodes(seriesId) }.getOrDefault(emptyList()).filter { it.user.played }.map { it.id }.toSet()
        sessions.put(TogetherSession(seriesId, groupId, names, now() + WatchPartners.TOGETHER_MS, seen))
    }

    /**
     * After the user's watched state changed: marks episodes of together sessions the user has
     * just finished as watched for the rest of the group. Returns how many were marked.
     */
    suspend fun settle(server: MediaServer): Int {
        var marked = 0
        for (session in sessions.all()) {
            val episodes = runCatching { server.allEpisodes(session.seriesId) }.getOrNull() ?: continue
            val fresh = WatchPartners.newlyWatched(session, episodes)
            for (e in fresh) {
                runCatching { server.watchedTogether(e.id, session.groupId) }.onSuccess { marked++ }
            }
            if (fresh.isNotEmpty()) sessions.put(session.copy(known = session.known + fresh.map { it.id }))
        }
        return marked
    }

    companion object {
        const val ID = "watch-partners"
    }
}
