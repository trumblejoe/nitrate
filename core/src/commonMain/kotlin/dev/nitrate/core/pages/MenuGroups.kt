package dev.nitrate.core.pages

import kotlinx.serialization.Serializable

/**
 * A group of menu stops (TV), shown as one stop `group:<id>` that opens in place onto its
 * [stops] (normally libraries): "Anime" holding Anime Movies and Anime TV Shows. A stop in a group
 * isn't shown on its own.
 */
@Serializable
data class MenuGroup(val id: String, val name: String, val stops: List<String>)

/** A suggested group: [name] for these library stops (shared leading words). */
data class GroupSuggestion(val name: String, val stops: List<String>) {
    val key: String get() = MenuGroups.suggestionKey(stops)
}

object MenuGroups {
    /** The same suggestion whatever order its members come in. */
    fun suggestionKey(stops: List<String>) = stops.sorted().joinToString("|")

    /**
     * Groups worth suggesting from the user's libraries ([libraries]: stop id → name): libraries
     * whose names start with the same word or words and each have something after them ("Anime
     * Movies" + "Anime TV Shows" → "Anime"), two or more; the longest shared start wins. Never one
     * whose libraries are already in a group, nor one dismissed.
     */
    fun suggest(libraries: Map<String, String>, groups: List<MenuGroup>, dismissed: Set<String>): List<GroupSuggestion> {
        val grouped = groups.flatMap { it.stops }.toSet()
        val free = libraries.filterKeys { it !in grouped }
        val byFirst = free.entries.groupBy { words(it.value).firstOrNull()?.lowercase() }.filterKeys { it != null }
        return byFirst.values.mapNotNull { members ->
            if (members.size < 2) return@mapNotNull null
            val split = members.map { words(it.value) }
            // The longest run of leading words they all share, leaving each something after it.
            var n = 0
            while (split.all { it.size > n + 1 } && split.map { it[n].lowercase() }.distinct().size == 1) n++
            if (n == 0) return@mapNotNull null
            val name = split.first().take(n).joinToString(" ")
            GroupSuggestion(name, members.map { it.key }).takeIf { it.key !in dismissed }
        }.sortedBy { it.name.lowercase() }
    }

    /** How a member shows inside its group: without the group's own words ("Anime Movies" in "Anime" → "Movies"). */
    fun memberLabel(group: MenuGroup, name: String): String {
        val g = words(group.name)
        val w = words(name)
        return if (w.size > g.size && w.take(g.size).map { it.lowercase() } == g.map { it.lowercase() }) w.drop(g.size).joinToString(" ") else name
    }

    /** A new group's id (not taken by [groups]). */
    fun newId(name: String, groups: List<MenuGroup>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "group" }
        var id = base
        var i = 2
        while (groups.any { it.id == id }) id = "$base-${i++}"
        return id
    }

    /**
     * [prefs] with a new group [name] holding [stops], shown where its first member was in [arranged]
     * (the menu as arranged now; the group goes before Settings when none of them was in it).
     */
    fun make(prefs: DisplayPrefs, name: String, stops: List<String>, arranged: List<String>? = null): DisplayPrefs {
        val group = MenuGroup(newId(name, prefs.tvMenuGroups), name, stops)
        // Members leave any other group and More.
        val others = prefs.tvMenuGroups.map { g -> g.copy(stops = g.stops - stops.toSet()) }.filter { it.stops.isNotEmpty() }
        val menu = arranged?.toMutableList()?.also { list ->
            val at = list.indexOfFirst { it in stops }
            when {
                at >= 0 -> list.add(at, MenuStops.group(group.id))
                else -> list.indexOf(MenuStops.SETTINGS).let { s -> if (s < 0) list += MenuStops.group(group.id) else list.add(s, MenuStops.group(group.id)) }
            }
        }
        return prefs.copy(tvMenuGroups = others + group, tvMoreStops = prefs.tvMoreStops?.let { it - stops.toSet() }, tvMenuStops = menu ?: prefs.tvMenuStops)
    }

    /** The group gone, its members back on their own. */
    fun split(prefs: DisplayPrefs, id: String): DisplayPrefs = prefs.copy(
        tvMenuGroups = prefs.tvMenuGroups.filterNot { it.id == id },
        tvMenuStops = prefs.tvMenuStops?.filterNot { it == MenuStops.group(id) },
        tvMoreStops = prefs.tvMoreStops?.filterNot { it == MenuStops.group(id) },
    )

    fun rename(prefs: DisplayPrefs, id: String, name: String) =
        prefs.copy(tvMenuGroups = prefs.tvMenuGroups.map { if (it.id == id) it.copy(name = name) else it })

    /** The group now holds [stops] (none: it's split up). */
    fun setStops(prefs: DisplayPrefs, id: String, stops: List<String>): DisplayPrefs {
        if (stops.isEmpty()) return split(prefs, id)
        val others = prefs.tvMenuGroups.map { g -> if (g.id == id) g.copy(stops = stops) else g.copy(stops = g.stops - stops.toSet()) }.filter { it.stops.isNotEmpty() }
        return prefs.copy(tvMenuGroups = others, tvMoreStops = prefs.tvMoreStops?.let { it - stops.toSet() })
    }

    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/** Where a stop sits (TV): in the menu itself, on the More page, or nowhere. */
enum class StopPlace(val label: String) { MENU("In the menu"), MORE("In More"), HIDDEN("Hidden") }

/**
 * The menu as shown (TV): the user's arrangement with groups standing in for their members, the
 * stops kept in More gathered behind one "More" stop, and hidden ones left out.
 */
object MenuLayout {
    /** Stops that can't go into More: you always need them in the menu. */
    val NEVER_MORE = setOf(MenuStops.SEARCH, MenuStops.HOME, MenuStops.SETTINGS, MenuStops.MORE)

    /** The stops on the More page ([saved], or [defaults] until changed): those that exist and may be there. */
    fun more(saved: List<String>?, defaults: List<String>, available: Set<String>, groups: List<MenuGroup>): List<String> {
        val members = groups.flatMap { it.stops }.toSet()
        return (saved ?: defaults).filter { it in available && it !in NEVER_MORE && it !in members }.distinct()
    }

    /**
     * The menu's stops in order from [arranged] (`MenuStops.arrange`, linear): a group appears where
     * its first member was (or where it was put itself), members and More's stops leave, and "More"
     * goes in just before Settings when More holds anything.
     */
    fun main(arranged: List<String>, groups: List<MenuGroup>, more: List<String>): List<String> {
        val moreSet = more.toSet()
        val out = mutableListOf<String>()
        for (id in arranged) {
            val group = groups.firstOrNull { id in it.stops }
            val shown = if (group != null) MenuStops.group(group.id) else id
            if (shown in moreSet || shown in out || shown == MenuStops.MORE) continue
            out += shown
        }
        if (more.isNotEmpty()) {
            val at = out.indexOf(MenuStops.SETTINGS)
            if (at < 0) out += MenuStops.MORE else out.add(at, MenuStops.MORE)
        }
        return out
    }

    /** Where [id] (a stop or a group) first stands in [arranged]: itself, or a group's first member. */
    private fun indexIn(arranged: List<String>, groups: List<MenuGroup>, id: String): Int {
        val members = groups.firstOrNull { MenuStops.group(it.id) == id }?.stops.orEmpty()
        return arranged.indexOfFirst { it == id || it in members }
    }

    /** [arranged] with every group's own id written in where it shows, so it can be moved like any stop. */
    private fun withGroups(arranged: List<String>, groups: List<MenuGroup>): MutableList<String> {
        val out = arranged.toMutableList()
        groups.map { MenuStops.group(it.id) }.filter { it !in out }.forEach { g ->
            val at = indexIn(out, groups, g)
            if (at >= 0) out.add(at, g)
        }
        return out
    }

    /**
     * The arrangement (`tvMenuStops`) with [id] moved one place up ([delta] −1) or down (+1) among the
     * stops as shown, groups moving as one.
     */
    fun move(arranged: List<String>, groups: List<MenuGroup>, more: List<String>, id: String, delta: Int): List<String> {
        val shown = main(arranged, groups, more).filter { it != MenuStops.MORE }
        val i = shown.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in shown.indices) return arranged
        val other = shown[j]
        val out = withGroups(arranged, groups)
        val members = groups.firstOrNull { MenuStops.group(it.id) == id }?.stops.orEmpty()
        val moving = out.filter { it == id || it in members }
        out.removeAll(moving.toSet())
        val at = indexIn(out, groups, other)
        if (at < 0) return arranged
        val otherEnd = if (delta > 0) out.indexOfLast { it == other || it in (groups.firstOrNull { g -> MenuStops.group(g.id) == other }?.stops.orEmpty()) } + 1 else at
        out.addAll(otherEnd, moving)
        return out
    }

    /**
     * [prefs] with [id] put in the menu, in More, or hidden. [arranged] is the menu as arranged now
     * (`tvMenuStops`, or its defaults) and [more] what's in More now. Search, Home and Settings stay
     * in the menu.
     */
    fun setPlace(prefs: DisplayPrefs, id: String, place: StopPlace, arranged: List<String>, more: List<String>): DisplayPrefs {
        if (id in NEVER_MORE && place != StopPlace.MENU) return prefs
        val members = prefs.tvMenuGroups.firstOrNull { MenuStops.group(it.id) == id }?.stops.orEmpty()
        val withoutMore = more - id
        return when (place) {
            StopPlace.MORE -> prefs.copy(tvMoreStops = withoutMore + id)
            StopPlace.HIDDEN -> prefs.copy(tvMoreStops = withoutMore, tvMenuStops = arranged.filterNot { it == id || it in members })
            StopPlace.MENU -> {
                val list = withGroups(arranged, prefs.tvMenuGroups)
                if (id !in list && members.none { it in list }) {
                    val at = list.indexOf(MenuStops.SETTINGS)
                    if (at < 0) list += id else list.add(at, id)
                }
                prefs.copy(tvMoreStops = withoutMore, tvMenuStops = list)
            }
        }
    }

    /** Where [id] sits now. */
    fun placeOf(id: String, shown: List<String>, more: List<String>): StopPlace = when (id) {
        in more -> StopPlace.MORE
        in shown -> StopPlace.MENU
        else -> StopPlace.HIDDEN
    }
}
