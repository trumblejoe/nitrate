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
import kotlinx.coroutines.flow.update

/** A show two partners watch together. */
data class PartnerShow(val seriesId: String, val name: String)

/** Someone the user watches shows with; [id] is the partnership's. */
data class WatchPartner(val id: String, val userId: String, val userName: String, val shows: List<PartnerShow>, val sinceMs: Long)

/** A request someone sent the user. */
data class IncomingRequest(val id: String, val fromId: String, val fromName: String, val seriesId: String, val seriesName: String, val atMs: Long)

/** A request the user sent: always just "waiting" until accepted or lapsed (nothing says whether the account exists). */
data class OutgoingRequest(val id: String, val to: String, val seriesId: String, val seriesName: String, val atMs: Long)

data class PartnersInfo(val partners: List<WatchPartner>, val incoming: List<IncomingRequest>, val outgoing: List<OutgoingRequest>) {
    /** The partners on [seriesId]. */
    fun on(seriesId: String) = partners.filter { p -> p.shows.any { it.seriesId == seriesId } }
}

/** Where a partner is in a shared show. */
data class PartnerProgress(val userId: String, val userName: String, val watched: Set<String>, val last: String?, val nextId: String?)

/** Whether the user is private (never listed, not findable by name), and whether an admin made it so. */
data class Privacy(val isPrivate: Boolean, val byAdmin: Boolean)

/**
 * Watching a show with partners, as chosen before playing: for a while every episode of it the
 * user finishes is also marked watched for them, including ones that play on by themselves.
 */
data class TogetherSession(val seriesId: String, val partnerIds: List<String>, val names: List<String>, val untilMs: Long, val known: Set<String>)

/** Watch partners logic: who's behind, what to warn about, what to mark. */
object WatchPartners {
    /** How long "watching with" lasts after it's chosen. */
    const val TOGETHER_MS = 8 * 60 * 60_000L

    /** The Companion feature. */
    const val FEATURE = "partners"

    /** Partners who haven't seen [episode]. */
    fun behind(episode: MediaItem, progress: List<PartnerProgress>): List<PartnerProgress> = progress.filter { episode.id !in it.watched }

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
}

/**
 * The watch-partners plugin: before an episode of a shared show plays, if a partner hasn't seen
 * it, asks whether the user is watching with them (then it counts for both) or alone.
 */
class WatchPartnersPlugin(val sessions: TogetherSessions, private val now: () -> Long) : PlaybackGuard {
    override val id = "watch-partners"
    override val name = "Watch partners"
    override val description = "Keeps shows you watch with someone in step: asks before you watch ahead alone, and " +
        "counts episodes you watch together for both of you. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    override suspend fun beforePlay(item: MediaItem, host: PluginHost): GuardDecision {
        val series = item.seriesId ?: return GuardDecision.Allow
        if (item.kind != ItemKind.EPISODE) return GuardDecision.Allow
        if (host.server.companion()?.features?.contains(WatchPartners.FEATURE) != true) return GuardDecision.Allow
        val progress = host.server.partnerProgress(series)
        val behind = WatchPartners.behind(item, progress)
        if (behind.isEmpty()) return GuardDecision.Allow
        // Already watching this show with them: carry on.
        val session = sessions.active(series)
        if (session != null && behind.all { it.userId in session.partnerIds }) return GuardDecision.Allow
        val who = WatchPartners.names(behind.map { it.userName })
        val where = behind.joinToString(" · ") { "${it.userName}: ${it.last ?: "not started"}" }
        return GuardDecision.Ask(
            title = "$who ${if (behind.size == 1) "hasn't" else "haven't"} seen this yet",
            message = "${item.episodeLabel ?: item.name} · $where. Watching together counts it for both of you, and for the next episodes too.",
            choices = listOf(
                GuardChoice("Watching with $who", GuardAction.RunThenPlay {
                    start(host.server, series, behind)
                }),
                GuardChoice("Just me", GuardAction.PlayAnyway),
                GuardChoice("Cancel", GuardAction.Cancel),
            ),
        )
    }

    /** Starts watching [seriesId] with [partners]: what the user has already seen doesn't count. */
    suspend fun start(server: MediaServer, seriesId: String, partners: List<PartnerProgress>) {
        val seen = runCatching { server.allEpisodes(seriesId) }.getOrDefault(emptyList()).filter { it.user.played }.map { it.id }.toSet()
        sessions.put(TogetherSession(seriesId, partners.map { it.userId }, partners.map { it.userName }, now() + WatchPartners.TOGETHER_MS, seen))
    }

    /**
     * After the user's watched state changed: marks episodes of together sessions the user has
     * just finished as watched for their partners too. Returns how many were marked.
     */
    suspend fun settle(server: MediaServer): Int {
        var marked = 0
        for (session in sessions.all()) {
            val episodes = runCatching { server.allEpisodes(session.seriesId) }.getOrNull() ?: continue
            val fresh = WatchPartners.newlyWatched(session, episodes)
            for (e in fresh) {
                runCatching { server.watchedTogether(e.id, session.partnerIds) }.onSuccess { marked++ }
            }
            if (fresh.isNotEmpty()) sessions.put(session.copy(known = session.known + fresh.map { it.id }))
        }
        return marked
    }

    companion object {
        const val ID = "watch-partners"
    }
}
