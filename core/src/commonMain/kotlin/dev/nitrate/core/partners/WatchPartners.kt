package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.plugins.NitratePlugin
import dev.nitrate.core.writing.UserRef

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

/** A card's line about a shared show ([short]) and the summary's sentence ([long]); [catchUp]: the user is behind. */
data class Standing(val short: String, val long: String, val catchUp: Boolean)

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

    /**
     * Where the user stands with their group(s) for [item] (part of the title, or the title itself): someone ahead
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
            val who = names(ahead.map { it.first.userName })
            if (k.single) return Standing("$who ${if (ahead.size == 1) "has" else "have"} seen it", "$who ${if (ahead.size == 1) "has" else "have"} seen it; you haven't yet", catchUp = true)
            val short = if (ahead.size == 1) "${first.userName} is $n ahead" else "$who are ahead"
            val detail = ahead.joinToString(", ") { (p, c) -> "${p.userName} by $c" }
            return Standing(short, "Catch up with your group: ${count(n, k.noun)} to go ($detail)", catchUp = true)
        }
        if (item.id in inShow && item.id !in k.mine) {
            val waiting = members.filter { item.id !in it.watched }
            if (waiting.isNotEmpty()) {
                val who = names(waiting.map { it.userName })
                return Standing("Watch with $who", "$who ${if (waiting.size == 1) "hasn't" else "haven't"} seen this yet either: watch it together", catchUp = false)
            }
        }
        val behind = members.map { it to behindBy(it) }.filter { it.second > 0 }
        if (behind.isNotEmpty()) {
            val who = names(behind.map { it.first.userName })
            if (k.single) return Standing("$who ${if (behind.size == 1) "hasn't" else "haven't"} seen it", "You've seen it; $who ${if (behind.size == 1) "hasn't" else "haven't"} yet", catchUp = false)
            return Standing("$who catching up", "You're ahead: $who ${if (behind.size == 1) "is" else "are"} catching up (${behind.joinToString(", ") { (p, c) -> "${p.userName} by $c" }})", catchUp = false)
        }
        val who = names(members.map { it.userName })
        return Standing("In step with $who", "You and $who are at the same place", catchUp = false)
    }
}

/**
 * The watch-partners plugin. It never stands between the user and Play: shows watched with
 * others just play, and Home's "Watching together" row ([TogetherRow]) and the cards' standing
 * lines keep everyone in step. Its switch turns the whole feature on or off on this device.
 */
class WatchPartnersPlugin : NitratePlugin {
    override val id = ID
    override val name = "Watch partners"
    override val description = "Keeps what you watch with others in step (shows, films, playlists and collections): groups who see each other's progress, " +
        "and a Home row of whose turn it is. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    companion object {
        const val ID = "watch-partners"
    }
}
