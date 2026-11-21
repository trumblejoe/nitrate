package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** Whose move it is in a shared show: the user's, the user's to catch up, or the others'. */
enum class Turn { YOURS, CATCH_UP, WAITING }

/** One show the user watches with a group: the series (when known), its episodes, and where everyone is. */
class SharedShow(val series: MediaItem?, val episodes: List<MediaItem>, val keepUp: KeepUp)

/** What holding OK (or a long press) on a card of the row offers. */
sealed interface TogetherChoice {
    val label: String

    /** Accept an invite into a group ([IncomingRequest.willSee] is already on the card's prompt). */
    data class Accept(val request: IncomingRequest) : TogetherChoice { override val label = "Accept" }

    data class Decline(val request: IncomingRequest) : TogetherChoice { override val label = "Decline" }

    /** Mark the episodes [who] has seen and the user hasn't as watched for the user. */
    data class CatchUp(val who: String, val episodeIds: List<String>) : TogetherChoice {
        override val label = "Catch up to $who (${if (episodeIds.size == 1) "1 episode" else "${episodeIds.size} episodes"})"
    }

    /** The user watched [episodeId] in the same room as the group: count it for them too. */
    data class WatchedWith(val groupId: String, val names: String, val episodeId: String, val episodeLabel: String) : TogetherChoice {
        override val label = "$names watched $episodeLabel with me"
    }

    /** Start a watch-together group, invite the group's members and play [episode]. */
    data class WatchNow(val episode: MediaItem, val groupId: String, val userIds: List<String>, val names: String) : TogetherChoice {
        override val label = "Watch it together now with $names"
    }

    /** Invite someone else into group [groupId]. */
    data class Invite(val groupId: String, val names: String) : TogetherChoice {
        override val label = "Invite someone to watch with $names"
    }

    /** Spoiler hiding for this show by the user's own progress only ([on]), or by the group's again. */
    data class OwnSpoilers(val seriesId: String, val on: Boolean) : TogetherChoice {
        override val label = if (on) "Hide spoilers by my progress only" else "Hide spoilers by the group's progress again"
    }

    data class OpenShow(val seriesId: String) : TogetherChoice { override val label = "Open the show" }

    /** Leave group [group] (asked to confirm first). */
    data class Leave(val group: WatchGroup) : TogetherChoice { override val label = "Leave the group with ${group.label}" }

    /** Pick a show and people to start watching with ([TogetherRow.start]'s card). */
    data object Start : TogetherChoice { override val label = "Watch a show with someone" }
}

/**
 * Home's "Watching together" row: invites waiting first, then each shared show by whose turn it
 * is (the user's, catching up, waiting on the others), then a card to start watching with
 * someone. Nothing is asked when Play is pressed; this row and the cards' standing lines are
 * where watching with others shows.
 */
object TogetherRow {
    /** The row's key, kept from when it was "Up next together": display settings are saved against it. */
    const val KEY = "home/up_next_together"
    const val TITLE = "Watching together"
    const val START_ID = "nitrate-together-start"

    /** The last card: picks a show and people. Not a server item (no art; the card shows its name). */
    val start = MediaItem(START_ID, "Watch with someone", ItemKind.OTHER, overview = "Pick a show and the people to watch it with.")

    fun isStart(item: MediaItem) = item.id == START_ID

    private fun real(episodes: List<MediaItem>) = episodes.filter { !it.isMissing && (it.seasonNumber ?: 0) != 0 }

    /** The user's next episode of a show: the first (in order) they haven't finished. */
    fun yourNext(episodes: List<MediaItem>): MediaItem? = real(episodes).firstOrNull { !it.user.played }

    /** Whose move it is: someone ahead (catch up), else someone behind (waiting), else the user's. */
    fun turn(k: KeepUp): Turn {
        val inShow = k.order.toSet()
        val members = k.progress.distinctBy { it.userId }
        if (members.any { p -> p.watched.any { it in inShow && it !in k.mine } }) return Turn.CATCH_UP
        if (members.any { p -> k.mine.any { it in inShow && it !in p.watched } }) return Turn.WAITING
        return Turn.YOURS
    }

    /** A show's card: the user's next episode, else (waiting, all seen) the show; nothing when everyone's done. */
    fun card(show: SharedShow): MediaItem? {
        yourNext(show.episodes)?.let { return it }
        if (turn(show.keepUp) != Turn.WAITING) return null
        return show.series ?: MediaItem(show.keepUp.seriesId, show.episodes.firstOrNull()?.seriesName ?: "", ItemKind.SERIES)
    }

    /**
     * The row's cards: [invites] (each the invited show), then shared shows (yours, catching up,
     * waiting), then [start]. Empty when there's neither an invite nor a shared show to show.
     */
    fun items(invites: List<MediaItem>, shows: List<SharedShow>): List<MediaItem> {
        val cards = shows.mapNotNull { s -> card(s)?.let { it to turn(s.keepUp) } }.sortedBy { it.second.ordinal }.map { it.first }
        if (invites.isEmpty() && cards.isEmpty()) return emptyList()
        return (invites + cards).distinctBy { it.id } + start
    }

    /** The invite a card stands for (a show's card while someone's asking the user to watch it with them). */
    fun inviteFor(item: MediaItem, info: PartnersInfo?): IncomingRequest? =
        if (item.kind != ItemKind.SERIES) null else info?.incoming?.firstOrNull { it.seriesId == item.id }

    /** The show a card belongs to. */
    fun seriesOf(item: MediaItem): String? = if (item.kind == ItemKind.SERIES) item.id else item.seriesId

    /**
     * What holding OK on [item] offers: an invite's Accept/Decline; for a shared show, catching up
     * to whoever's ahead, counting the user's last episode for a group that watched it with them,
     * watching the next one together now ([canWatchNow]: the server carries invites), inviting
     * someone, spoilers by the user's own progress ([ownSpoilers]: the shows that already do),
     * opening the show and leaving each group.
     */
    fun choices(
        item: MediaItem,
        info: PartnersInfo?,
        keepUp: KeepUp?,
        episodes: List<MediaItem>,
        canWatchNow: Boolean,
        ownSpoilers: Set<String>,
    ): List<TogetherChoice> {
        if (isStart(item)) return listOf(TogetherChoice.Start)
        val series = seriesOf(item) ?: return emptyList()
        inviteFor(item, info)?.let { r ->
            return listOf(TogetherChoice.Accept(r), TogetherChoice.Decline(r), TogetherChoice.OpenShow(series))
        }
        val groups = info?.on(series).orEmpty()
        val out = mutableListOf<TogetherChoice>()
        val ordered = real(episodes)
        if (keepUp != null) {
            val inShow = keepUp.order.toSet()
            keepUp.progress.distinctBy { it.userId }
                .map { p -> p to keepUp.order.filter { it in p.watched && it !in keepUp.mine } }
                .filter { it.second.isNotEmpty() }
                .sortedByDescending { it.second.size }
                .forEach { (p, ids) -> out += TogetherChoice.CatchUp(p.userName, ids) }
            // The user's latest finished episode that someone in a group hasn't: "Sam watched S1:E3 with me".
            val last = ordered.lastOrNull { it.id in keepUp.mine && it.id in inShow }
            if (last != null) groups.forEach { g ->
                val missing = keepUp.progress.filter { it.groupId == g.id && last.id !in it.watched }
                if (missing.isNotEmpty()) {
                    out += TogetherChoice.WatchedWith(g.id, WatchPartners.names(missing.map { it.userName }), last.id, shortLabel(last))
                }
            }
        }
        if (canWatchNow && item.kind == ItemKind.EPISODE) groups.forEach { g ->
            out += TogetherChoice.WatchNow(item, g.id, g.others.map { it.id }, g.label)
        }
        groups.forEach { g -> out += TogetherChoice.Invite(g.id, g.label) }
        out += TogetherChoice.OwnSpoilers(series, on = series !in ownSpoilers)
        out += TogetherChoice.OpenShow(series)
        groups.forEach { g -> out += TogetherChoice.Leave(g) }
        return out
    }

    /**
     * The line under a card: the start card's hint, an invite ("Sam invites you"), else where the
     * user stands in the shared show ([WatchPartners.standing]).
     */
    fun line(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): Standing? {
        if (isStart(item)) return Standing("Pick a show and people", "Watch a show with someone: pick the show, then who to ask", catchUp = false)
        inviteFor(item, info)?.let { r -> return Standing("${r.fromName} invites you", "${r.fromName} asked you to watch this with them", catchUp = false) }
        val k = standings[seriesOf(item) ?: return null] ?: return null
        return WatchPartners.standing(item, k)
    }

    /** "S1:E3". */
    fun shortLabel(e: MediaItem) = "S${e.seasonNumber ?: "?"}:E${e.indexNumber ?: "?"}"
}
