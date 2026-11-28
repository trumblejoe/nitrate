package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** Whose move it is in a shared show: the user's, the user's to catch up, or the others'. */
enum class Turn { YOURS, CATCH_UP, WAITING }

/**
 * One title the user watches with one or more groups (a show, a film, a playlist or a collection):
 * the title itself (when known), what it holds in watching order, where everyone is, and the
 * user's groups holding it ([groupIds]; by default every group the standings name).
 */
class SharedShow(
    val series: MediaItem?,
    val episodes: List<MediaItem>,
    val keepUp: KeepUp,
    val groupIds: List<String> = TogetherRow.groupsOf(keepUp),
)

/**
 * One card of Home's Watching together row: [item] (carrying [MediaItem.watchGroupId]) is the
 * user's next part of title [titleId] by group [groupId]'s record, else the title while waiting on
 * the others; [turn] whose move it is there. Playing it starts from that group's card
 * ([PlayOrigin.of]).
 */
data class TogetherCard(val item: MediaItem, val groupId: String, val titleId: String, val turn: Turn)

/** What holding OK (or a long press) on a card of the row offers. */
sealed interface TogetherChoice {
    val label: String

    /** Accept an invite into a group ([IncomingRequest.willSee] is already on the card's prompt). */
    data class Accept(val request: IncomingRequest) : TogetherChoice { override val label = "Accept" }

    data class Decline(val request: IncomingRequest) : TogetherChoice { override val label = "Decline" }

    /**
     * Mark what [who] has seen and the user hasn't as watched for the user; [groupId]: the
     * group (by its own record) it's caught up in, where it's counted instead (older servers: null, Jellyfin's state).
     */
    data class CatchUp(val who: String, val episodeIds: List<String>, val noun: String = "episode", val groupId: String? = null) : TogetherChoice {
        override val label = "Catch up to $who (${WatchPartners.count(episodeIds.size, noun)})"
    }

    /** The user watched [episodeId] in the same room as the group: count it for them too. */
    data class WatchedWith(val groupId: String, val names: String, val episodeId: String, val episodeLabel: String) : TogetherChoice {
        override val label = "$names watched $episodeLabel with me"
    }

    /**
     * Mark [itemIds] watched ([watched]) or unwatched for the user in group [groupId]'s own record
     * only ("Mark S1:E3 watched for this group", "Mark everything up to here watched").
     */
    data class Mark(val groupId: String, val itemIds: List<String>, val watched: Boolean, override val label: String) : TogetherChoice

    /**
     * "We're in the same room": [item] plays with the group's [present] members here, counting for
     * them too (who's here can be changed in the player).
     */
    data class InTheRoom(val groupId: String, val titleId: String, val item: MediaItem, val present: List<String>, override val label: String = "We're in the same room") : TogetherChoice

    /** Add a title to group [group] (any member may). */
    data class AddTitle(val group: WatchGroup, override val label: String = "Add a title to the group") : TogetherChoice

    /** Remove title [titleId] from group [group] (any member may; a group keeps at least one; what was watched stays in the record). */
    data class RemoveTitle(val group: WatchGroup, val titleId: String, val name: String, override val label: String = "Remove $name from the group") : TogetherChoice

    /** "Start a Group Watch Session": one for the group on [episode] (its members told at once, "Join"), then it plays (counting for the group). */
    data class WatchNow(val episode: MediaItem, val groupId: String, val userIds: List<String>, val names: String) : TogetherChoice {
        override val label = "Start a Group Watch Session with $names"
    }

    /** "Ask to watch together": ask the group to start a Group Watch Session of [episode] (someone else hosts it), now or later. */
    data class AskToWatch(val episode: MediaItem, val groupId: String, val names: String) : TogetherChoice {
        override val label = "Ask $names to watch together"

        /** What it plays, spoiler-safe: "S1:E4 of The Bear" (never an episode's name), or a film's title. */
        val what: String get() = if (episode.kind == ItemKind.EPISODE) listOfNotNull(TogetherRow.shortLabel(episode), episode.seriesName).joinToString(" of ") else episode.name
    }

    /** Invite someone else into group [groupId]. */
    data class Invite(val groupId: String, val names: String) : TogetherChoice {
        override val label = "Invite someone to watch with $names"
    }

    /** Spoiler hiding for this show by the user's own progress only ([on]), or by the group's again (shows only). */
    data class OwnSpoilers(val seriesId: String, val on: Boolean) : TogetherChoice {
        override val label = if (on) "Hide spoilers by my progress only" else "Hide spoilers by the group's progress again"
    }

    /** Opens the title's details; from a group's card, carrying that group ([groupId]) so plays there count for it. */
    data class OpenShow(val seriesId: String, override val label: String = "Open the show", val groupId: String? = null) : TogetherChoice

    /** Leave group [group] (asked to confirm first). */
    data class Leave(val group: WatchGroup) : TogetherChoice { override val label = "Leave the group with ${group.label}" }

    /** Ask the group to change its schedule or Group Watch Session (opens the schedule picker; takes effect once most agree). */
    data class ChangeSchedule(val group: WatchGroup, val suffix: String = "") : TogetherChoice {
        override val label = "Change the schedule or Group Watch Session (${group.schedule?.label ?: "Binge"})$suffix"
    }

    /** A yes or no to the group's waiting schedule change. */
    data class Vote(val groupId: String, val changeId: String, val yes: Boolean, val proposed: GroupSchedule, val from: String) : TogetherChoice {
        override val label = if (yes) "Agree to $from's change: ${proposed.label}" else "Keep the schedule as it is"
    }

    /**
     * The group's next Group Watch Session: "Tonight's Group Watch Session: 8:15 · S1:E4 · Postpone… · Not tonight".
     * Opens the same prompt as its reminder (Join, +15/+30/+60 min, Not tonight, Can't make it),
     * so nothing depends on catching the reminder.
     */
    data class Party(val group: WatchGroup, val party: WatchParty, val nowMs: Long) : TogetherChoice {
        override val label = party.line(nowMs) + if (party.skipped) "" else " · Postpone… · Not tonight"
    }

    /** Pick a title and people to start watching with ([TogetherRow.start]'s card). */
    data object Start : TogetherChoice { override val label = "Watch something with someone" }
}

/**
 * Home's "Watching together" row: invites waiting first, then one card per group and title (the
 * user's next part of it by that group's record, else the title while waiting on the others), by
 * whose turn it is (the user's, catching up, waiting on the others), then a card to start
 * watching with someone. Continue Watching and Next Up stay the user's own.
 */
object TogetherRow {
    /** The row's key, kept from when it was "Up next together": display settings are saved against it. */
    const val KEY = "home/up_next_together"
    const val TITLE = "Watching together"
    const val START_ID = "nitrate-together-start"

    /** The last card: picks a title and people. Not a server item (no art; the card shows its name). */
    val start = MediaItem(START_ID, "Watch with someone", ItemKind.OTHER, overview = "Pick a show, film, playlist or collection and the people to watch it with.")

    fun isStart(item: MediaItem) = item.id == START_ID

    private fun real(episodes: List<MediaItem>) = WatchPartners.countable(episodes)

    /** The user's next part of a title: the first (in order) they haven't finished. */
    fun yourNext(episodes: List<MediaItem>): MediaItem? = real(episodes).firstOrNull { !it.user.played }

    /** Whose move it is: someone ahead (catch up), else someone behind (waiting), else the user's; each partner against the user's place in their group. */
    fun turn(k: KeepUp): Turn = turnIn(k, null)

    /** [turn] among the partners of [groupIds] only (null: every group). */
    private fun turnIn(k: KeepUp, groupIds: Set<String>?): Turn {
        val inShow = k.order.toSet()
        val members = k.progress.filter { groupIds == null || it.groupId in groupIds }.distinctBy { it.userId }
        if (members.any { p -> p.watched.any { it in inShow && it !in k.mineIn(p.groupId) } }) return Turn.CATCH_UP
        if (members.any { p -> k.mineIn(p.groupId).any { it in inShow && it !in p.watched } }) return Turn.WAITING
        return Turn.YOURS
    }

    /** The groups the standings for a title name: those with a record of the user's, and those with partners in it. */
    fun groupsOf(k: KeepUp): List<String> = (k.own.keys + k.progress.map { it.groupId }).distinct()

    /**
     * A title's cards, whose move first: for each group holding it, the user's next part by that
     * group's record, else (waiting there, all seen) the title; nothing where everyone's done.
     * One person at episode 3 with Tommy and 8 with Suzy has a card for each; the same episode
     * next in two groups is two cards, each for its group.
     */
    fun cardsOf(show: SharedShow): List<TogetherCard> =
        show.groupIds.mapNotNull { g ->
            val turn = turnIn(show.keepUp, setOf(g))
            val mine = show.keepUp.mineIn(g)
            val next = real(show.episodes).firstOrNull { it.id !in mine } ?: show.series?.takeIf { turn == Turn.WAITING }
            next?.let { TogetherCard(it.copy(watchGroupId = g), g, show.keepUp.seriesId, turn) }
        }.sortedBy { it.turn.ordinal }

    /** A title's first card: the user's next part of it in one of their groups, else (waiting, all seen) the title. */
    fun card(show: SharedShow): MediaItem? = cardsOf(show).firstOrNull()?.item

    /** Whether a title's cards need the title itself: in one of the user's groups on it they've seen everything. */
    fun needsTitle(episodes: List<MediaItem>, k: KeepUp): Boolean = groupsOf(k).any { g -> real(episodes).none { it.id !in k.mineIn(g) } }

    /** The row's cards: [invites] (each the invited title) first, then each group's cards by whose turn it is. */
    fun cards(invites: List<MediaItem>, shows: List<SharedShow>): TogetherCards =
        TogetherCards(invites.distinctBy { it.id }, shows.flatMap { cardsOf(it) }.sortedBy { it.turn.ordinal })

    /** The row's items ([cards]), then [start] (alone when there's neither an invite nor a shared title). */
    fun items(invites: List<MediaItem>, shows: List<SharedShow>): List<MediaItem> = cards(invites, shows).items

    /** Rows whose cards answer for watching together: this one (Continue Watching and Next Up are the user's own). */
    val ROW_KEYS = setOf(KEY)

    /** Whether [item]'s card is about watching together: the start card, an invite, or a title the user shares with a group. */
    fun isShared(item: MediaItem, info: PartnersInfo?, standings: Map<String, KeepUp>): Boolean {
        if (isStart(item) || inviteFor(item, info) != null) return true
        val title = titleOf(item, standings) ?: return false
        return title in standings || info?.on(title).orEmpty().isNotEmpty()
    }

    /** The invite a card stands for (a title's card while someone's asking the user to watch it with them). */
    fun inviteFor(item: MediaItem, info: PartnersInfo?): IncomingRequest? =
        if (item.kind == ItemKind.EPISODE || isStart(item)) null else info?.incoming?.firstOrNull { r -> r.titles.any { it.id == item.id } }

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
     * What holding OK on [item] (or "Watch group…" on its details page) offers, per group: an
     * invite's Accept/Decline; for a shared title, a waiting schedule change's vote and the Group
     * Watch Session first, then for its part (the item itself, else the user's next part with that
     * group): mark it watched or unwatched for this group (with "Mark everything up to here
     * watched" when earlier parts aren't in the record yet), catching up to whoever's ahead, Watch
     * now together ([canWatchNow]: the server has Group Watch Sessions), We're in the same room, invite
     * someone, change the schedule or Group Watch Session, add a title or remove this one,
     * spoilers by the user's own progress (shows in a Together group; [ownSpoilers]: the shows
     * that already do), open it, start something new, leave. A group's card
     * ([MediaItem.watchGroupId]) offers its group's only; with several groups each choice names its group.
     */
    fun choices(
        item: MediaItem,
        info: PartnersInfo?,
        standings: Map<String, KeepUp>,
        episodes: List<MediaItem>,
        canWatchNow: Boolean,
        ownSpoilers: Set<String>,
        /** Now, for the Group Watch Session's "Tonight's Group Watch Session: 8:15". */
        nowMs: Long = 0L,
        /** The server takes asks to watch together (Companion feature `asks`). */
        canAsk: Boolean = false,
    ): List<TogetherChoice> {
        if (isStart(item)) return listOf(TogetherChoice.Start)
        val series = titleOf(item, standings) ?: return emptyList()
        val keepUp = standings[series]
        inviteFor(item, info)?.let { r ->
            return listOf(TogetherChoice.Accept(r), TogetherChoice.Decline(r), TogetherChoice.OpenShow(series, "Open it"))
        }
        val groups = info?.on(series).orEmpty().filter { item.watchGroupId == null || it.id == item.watchGroupId }
        val ids = groups.map { it.id }.toSet()
        val several = groups.size > 1
        fun whose(g: WatchGroup) = Viewing.group(g)
        fun tag(g: WatchGroup) = if (several) " (with ${g.label})" else ""
        val isShow = keepUp?.noun != "title" && (item.kind == ItemKind.EPISODE || item.kind == ItemKind.SERIES)
        val out = mutableListOf<TogetherChoice>()
        val ordered = real(episodes)
        // Changes to a group's schedule waiting for the user's vote come first, then each group's Group Watch Session.
        groups.forEach { g ->
            val p = g.pending ?: return@forEach
            if (!p.voted) {
                out += TogetherChoice.Vote(g.id, p.id, yes = true, proposed = p.proposed, from = p.fromName)
                out += TogetherChoice.Vote(g.id, p.id, yes = false, proposed = p.proposed, from = p.fromName)
            }
        }
        groups.forEach { g -> g.party?.let { out += TogetherChoice.Party(g, it, nowMs) } }
        // The part each group's choices are about: the item itself, else the user's next part with that group.
        fun partOf(g: WatchGroup): MediaItem? = when {
            keepUp == null -> item.takeIf { it.isPlayable }
            item.id in keepUp.order -> ordered.firstOrNull { it.id == item.id } ?: item
            else -> ordered.firstOrNull { it.id !in keepUp.mineIn(g.id) }
        }
        // Where the user stands isn't known yet: the part itself can still be marked for the group.
        if (keepUp == null) groups.forEach { g ->
            val part = partOf(g) ?: return@forEach
            out += TogetherChoice.Mark(g.id, listOf(part.id), watched = true, "Mark ${shortLabel(part)} watched · ${whose(g)}")
        }
        if (keepUp != null) groups.forEach { g ->
            val part = partOf(g) ?: return@forEach
            val mine = keepUp.mineIn(g.id)
            val what = shortLabel(part)
            if (part.id in mine) out += TogetherChoice.Mark(g.id, listOf(part.id), watched = false, "Mark $what unwatched · ${whose(g)}")
            else {
                out += TogetherChoice.Mark(g.id, listOf(part.id), watched = true, "Mark $what watched · ${whose(g)}")
                val upTo = keepUp.order.indexOf(part.id).takeIf { it >= 0 }?.let { at -> keepUp.order.take(at + 1).filter { it !in mine } }.orEmpty()
                if (upTo.size > 1) out += TogetherChoice.Mark(g.id, upTo, watched = true, "Mark everything up to here watched (${WatchPartners.count(upTo.size, keepUp.noun)})${tag(g)}")
            }
        }
        if (keepUp != null) {
            // Each partner against the user's place in their group (its own record).
            keepUp.progress.filter { it.groupId in ids }.distinctBy { it.userId }
                .map { p -> p to keepUp.order.filter { it in p.watched && it !in keepUp.mineIn(p.groupId) } }
                .filter { it.second.isNotEmpty() }
                .sortedByDescending { it.second.size }
                .forEach { (p, ids) -> out += TogetherChoice.CatchUp(p.userName, ids, keepUp.noun, p.groupId.takeIf { it in keepUp.own }) }
        }
        groups.forEach { g ->
            val part = partOf(g)?.takeIf { it.isPlayable } ?: return@forEach
            if (canWatchNow) out += TogetherChoice.WatchNow(part, g.id, g.others.map { it.id }, g.label)
            if (canWatchNow && canAsk) out += TogetherChoice.AskToWatch(part, g.id, g.label)
            out += TogetherChoice.InTheRoom(g.id, series, part, g.others.map { it.id }, "We're in the same room${tag(g)}")
        }
        groups.forEach { g -> out += TogetherChoice.Invite(g.id, g.label) }
        groups.forEach { g -> if (g.pending == null) out += TogetherChoice.ChangeSchedule(g, tag(g)) }
        groups.forEach { g ->
            out += TogetherChoice.AddTitle(g, "Add a title to the group${tag(g)}")
            if (g.titles.size > 1) g.titles.firstOrNull { it.id == series }?.let { t -> out += TogetherChoice.RemoveTitle(g, t.id, t.name, "Remove ${t.name} from the group${tag(g)}") }
        }
        if (isShow && groups.any { it.together }) out += TogetherChoice.OwnSpoilers(series, on = series !in ownSpoilers)
        out += TogetherChoice.OpenShow(series, if (isShow) "Open the show" else "Open it", item.watchGroupId)
        out += TogetherChoice.Start
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
        // A group's card: where the user stands with that group; else with the group(s) this part would count for.
        val context = item.watchGroupId?.let { setOf(it) } ?: WatchPartners.contextFor(item, info, standings).groups.map { it.id }.toSet()
        val st = (if (context.isNotEmpty()) WatchPartners.standing(item, k, context) else null) ?: WatchPartners.standing(item, k) ?: return null
        // Held back by the group's schedule, or not watched by a Together group yet: that, in place of the user's word.
        PartLock.of(item, info, standings)?.let { l ->
            val why = if (l.together) "Your group with ${l.group.label} hasn't watched it yet: you watch it together." else "${l.word}: the group's schedule is ${l.group.schedule?.label}."
            return Standing("${l.word} · ${st.partners}", "$why ${st.long}", catchUp = false, word = l.word, partners = st.partners)
        }
        // The standing is the whole title's: on one of its parts (a season's episodes on a details page)
        // it shows only on the part that's next with the group, else every episode read "Behind" alike.
        if (item.id != title && !k.single && item.id !in nextParts(k, context)) return null
        g@ for (g in info?.on(title).orEmpty().filter { item.watchGroupId == null || it.id == item.watchGroupId }) {
            val p = g.pending ?: continue@g
            if (!p.voted) return st.copy(long = "${p.fromName} asks to change the schedule to ${p.proposed.label}. ${st.long}")
        }
        return st
    }

    /** The part that's next in [k] for each of [groupIds] (by each group's record), or the user's own next with none. */
    fun nextParts(k: KeepUp, groupIds: Set<String>): Set<String> =
        (if (groupIds.isEmpty()) listOf(k.mine) else groupIds.map(k::mineIn)).mapNotNull { seen -> k.order.firstOrNull { it !in seen } }.toSet()

    /** The group whose schedule hasn't let [item] out yet, if any; Together's question is [PartLock]'s. */
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

/** Home's Watching together row: [invites] first, then one card per group and title ([TogetherCard]). */
data class TogetherCards(
    val invites: List<MediaItem> = emptyList(),
    val cards: List<TogetherCard> = emptyList(),
    /** Whether the server has watch groups at all: then the row always shows, with at least the start card. */
    val available: Boolean = true,
) {
    val isEmpty get() = invites.isEmpty() && cards.isEmpty()

    /**
     * The row's items: the invites, the cards, then the start card. On by default: with nothing
     * shared yet it's the start card alone, so watching with someone is always a card away (the
     * row is hidden like any other). Nothing without the Companion's watch groups.
     */
    val items: List<MediaItem> get() = if (!available) emptyList() else invites + cards.map { it.item } + TogetherRow.start
}
