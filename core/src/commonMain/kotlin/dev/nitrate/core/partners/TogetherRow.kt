package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** Whose move it is in a shared show: the user's, the user's to catch up, or the others'. */
enum class Turn { YOURS, CATCH_UP, WAITING }

/**
 * One title the user watches with a group (a show, a film, a playlist or a collection): the title
 * itself (when known), what it holds in watching order, and where everyone is.
 */
class SharedShow(val series: MediaItem?, val episodes: List<MediaItem>, val keepUp: KeepUp)

/** What holding OK (or a long press) on a card of the row offers. */
sealed interface TogetherChoice {
    val label: String

    /** Accept an invite into a group ([IncomingRequest.willSee] is already on the card's prompt). */
    data class Accept(val request: IncomingRequest) : TogetherChoice { override val label = "Accept" }

    data class Decline(val request: IncomingRequest) : TogetherChoice { override val label = "Decline" }

    /** Mark what [who] has seen and the user hasn't as watched for the user. */
    data class CatchUp(val who: String, val episodeIds: List<String>, val noun: String = "episode") : TogetherChoice {
        override val label = "Catch up to $who (${WatchPartners.count(episodeIds.size, noun)})"
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

    /** Spoiler hiding for this show by the user's own progress only ([on]), or by the group's again (shows only). */
    data class OwnSpoilers(val seriesId: String, val on: Boolean) : TogetherChoice {
        override val label = if (on) "Hide spoilers by my progress only" else "Hide spoilers by the group's progress again"
    }

    data class OpenShow(val seriesId: String, override val label: String = "Open the show") : TogetherChoice

    /** Leave group [group] (asked to confirm first). */
    data class Leave(val group: WatchGroup) : TogetherChoice { override val label = "Leave the group with ${group.label}" }

    /** Ask the group to change its schedule (opens the schedule picker; takes effect once most agree). */
    data class ChangeSchedule(val group: WatchGroup) : TogetherChoice {
        override val label = "Change the schedule (${group.schedule?.label ?: "Binge"})"
    }

    /** A yes or no to the group's waiting schedule change. */
    data class Vote(val groupId: String, val changeId: String, val yes: Boolean, val proposed: GroupSchedule, val from: String) : TogetherChoice {
        override val label = if (yes) "Agree to $from's change: ${proposed.label}" else "Keep the schedule as it is"
    }

    /** Pick a title and people to start watching with ([TogetherRow.start]'s card). */
    data object Start : TogetherChoice { override val label = "Watch something with someone" }
}

/**
 * Home's "Watching together" row: invites waiting first, then each shared title (show, film,
 * playlist or collection) by whose turn it
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
    val start = MediaItem(START_ID, "Watch with someone", ItemKind.OTHER, overview = "Pick a show, film, playlist or collection and the people to watch it with.")

    fun isStart(item: MediaItem) = item.id == START_ID

    private fun real(episodes: List<MediaItem>) = WatchPartners.countable(episodes)

    /** The user's next part of a title: the first (in order) they haven't finished. */
    fun yourNext(episodes: List<MediaItem>): MediaItem? = real(episodes).firstOrNull { !it.user.played }

    /** Whose move it is: someone ahead (catch up), else someone behind (waiting), else the user's. */
    fun turn(k: KeepUp): Turn {
        val inShow = k.order.toSet()
        val members = k.progress.distinctBy { it.userId }
        if (members.any { p -> p.watched.any { it in inShow && it !in k.mine } }) return Turn.CATCH_UP
        if (members.any { p -> k.mine.any { it in inShow && it !in p.watched } }) return Turn.WAITING
        return Turn.YOURS
    }

    /** A title's card: the user's next part of it, else (waiting, all seen) the title; nothing when everyone's done. */
    fun card(show: SharedShow): MediaItem? {
        yourNext(show.episodes)?.let { return it }
        if (turn(show.keepUp) != Turn.WAITING) return null
        return show.series
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

    /** The invite a card stands for (a title's card while someone's asking the user to watch it with them). */
    fun inviteFor(item: MediaItem, info: PartnersInfo?): IncomingRequest? =
        if (item.kind == ItemKind.EPISODE || isStart(item)) null else info?.incoming?.firstOrNull { it.seriesId == item.id }

    /**
     * The group title a card belongs to: the title itself, else the shared title holding it (its
     * show first), else its show.
     */
    fun titleOf(item: MediaItem, standings: Map<String, KeepUp>): String? {
        if (item.id in standings) return item.id
        item.seriesId?.let { s -> standings[s]?.takeIf { item.id in it.order }?.let { return s } }
        standings.entries.firstOrNull { item.id in it.value.order }?.let { return it.key }
        return if (item.kind == ItemKind.SERIES) item.id else item.seriesId
    }

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
        standings: Map<String, KeepUp>,
        episodes: List<MediaItem>,
        canWatchNow: Boolean,
        ownSpoilers: Set<String>,
    ): List<TogetherChoice> {
        if (isStart(item)) return listOf(TogetherChoice.Start)
        val series = titleOf(item, standings) ?: return emptyList()
        val keepUp = standings[series]
        inviteFor(item, info)?.let { r ->
            return listOf(TogetherChoice.Accept(r), TogetherChoice.Decline(r), TogetherChoice.OpenShow(series, "Open it"))
        }
        val groups = info?.on(series).orEmpty()
        val isShow = keepUp?.noun != "title" && (item.kind == ItemKind.EPISODE || item.kind == ItemKind.SERIES)
        val out = mutableListOf<TogetherChoice>()
        val ordered = real(episodes)
        if (keepUp != null) {
            val inShow = keepUp.order.toSet()
            keepUp.progress.distinctBy { it.userId }
                .map { p -> p to keepUp.order.filter { it in p.watched && it !in keepUp.mine } }
                .filter { it.second.isNotEmpty() }
                .sortedByDescending { it.second.size }
                .forEach { (p, ids) -> out += TogetherChoice.CatchUp(p.userName, ids, keepUp.noun) }
            // The user's latest finished part that someone in a group hasn't: "Sam watched S1:E3 with me".
            val last = ordered.lastOrNull { it.id in keepUp.mine && it.id in inShow }
            if (last != null) groups.forEach { g ->
                val missing = keepUp.progress.filter { it.groupId == g.id && last.id !in it.watched }
                if (missing.isNotEmpty()) {
                    out += TogetherChoice.WatchedWith(g.id, WatchPartners.names(missing.map { it.userName }), last.id, shortLabel(last))
                }
            }
        }
        // Changes to a group's schedule waiting for the user's vote come first.
        groups.forEach { g ->
            val p = g.pending ?: return@forEach
            if (!p.voted) {
                out.add(0, TogetherChoice.Vote(g.id, p.id, yes = false, proposed = p.proposed, from = p.fromName))
                out.add(0, TogetherChoice.Vote(g.id, p.id, yes = true, proposed = p.proposed, from = p.fromName))
            }
        }
        val hardLocked = lockOf(item, info, standings)?.schedule?.softLock == false
        if (canWatchNow && item.isPlayable && !hardLocked) groups.forEach { g ->
            out += TogetherChoice.WatchNow(item, g.id, g.others.map { it.id }, g.label)
        }
        groups.forEach { g -> out += TogetherChoice.Invite(g.id, g.label) }
        groups.forEach { g -> if (g.pending == null) out += TogetherChoice.ChangeSchedule(g) }
        if (isShow) out += TogetherChoice.OwnSpoilers(series, on = series !in ownSpoilers)
        out += TogetherChoice.OpenShow(series, if (isShow) "Open the show" else "Open it")
        groups.forEach { g -> out += TogetherChoice.Leave(g) }
        return out
    }

    /**
     * The line under a card: the start card's hint, an invite ("Sam invites you"), else where the
     * user stands in the shared show ([WatchPartners.standing]).
     */
    fun line(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): Standing? {
        if (isStart(item)) return Standing("Start · Pick what and who", "Watch something with someone: pick what, then who to ask", catchUp = false, word = "Start", partners = "Pick what and who")
        inviteFor(item, info)?.let { r ->
            val on = r.schedule?.takeIf { it.active }?.let { " on a schedule: ${it.label}" }.orEmpty()
            return Standing("Invited · by ${r.fromName}", "${r.fromName} asked you to watch this with them$on", catchUp = false, word = "Invited", partners = "by ${r.fromName}")
        }
        val title = titleOf(item, standings) ?: return null
        val k = standings[title] ?: return null
        val st = WatchPartners.standing(item, k) ?: return null
        // Locked by the group's schedule: when it opens, or that it's for watching together, in place of the user's word.
        PartLock.of(item, info, standings)?.let { l ->
            val why = if (l.together) "Nobody in the group has seen it yet: you watch it together with ${l.group.label}." else "${l.word}: the group's schedule is ${l.group.schedule?.label}."
            return Standing("${l.word} · ${st.partners}", "$why ${st.long}", catchUp = false, word = l.word, partners = st.partners)
        }
        g@ for (g in info?.on(title).orEmpty()) {
            val p = g.pending ?: continue@g
            if (!p.voted) return st.copy(long = "${p.fromName} asks to change the schedule to ${p.proposed.label}. ${st.long}")
        }
        return st
    }

    /** The group whose schedule locks [item] (it's past what the group has let out), if any; together only is [PartLock]'s. */
    fun lockOf(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): WatchGroup? =
        PartLock.of(item, info, standings)?.takeIf { !it.together }?.group

    /** What kind of title it is, for the picker ("Show", "Film"...). */
    fun kindLabel(item: MediaItem): String? = when (item.kind) {
        ItemKind.SERIES -> "Show"
        ItemKind.MOVIE -> "Film"
        ItemKind.PLAYLIST -> "Playlist"
        ItemKind.BOX_SET -> "Collection"
        else -> null
    }

    /** What can be watched together: shows, films, playlists and collections. */
    val KINDS = listOf(ItemKind.SERIES, ItemKind.MOVIE, ItemKind.PLAYLIST, ItemKind.BOX_SET)

    /** "S1:E3" for an episode, else its name. */
    fun shortLabel(e: MediaItem) = if (e.kind == ItemKind.EPISODE) "S${e.seasonNumber ?: "?"}:E${e.indexNumber ?: "?"}" else e.name
}
