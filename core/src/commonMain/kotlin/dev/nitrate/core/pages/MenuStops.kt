package dev.nitrate.core.pages

/**
 * The stops in the TV's main menu, by id, and how the user arranges them. A menu is a list of
 * slots: for bars and rails just the order; for the dial, slot `ring * 4 + spoke` (spokes up,
 * right, down, left) and a null is an empty spot.
 */
object MenuStops {
    const val SEARCH = "search"
    const val HOME = "home"
    const val PAGES = "pages"
    const val TOGETHER = "together"
    const val CHANNELS = "channels"

    /** Comic catalogs (OPDS, CLU): not in the menu until added in Menu stops. */
    const val COMICS = "comics"
    const val SETTINGS = "settings"
    const val PROFILES = "profiles"

    fun library(id: String) = "library:$id"

    /** The stops Easy mode keeps: Home, Search, the libraries, Settings and switching user. */
    fun essential(id: String): Boolean = id in setOf(HOME, SEARCH, SETTINGS, PROFILES) || id.startsWith("library:")
    fun page(id: String) = "page:$id"

    /** Spots per ring on the dial. */
    const val SPOKES = 4

    /** The menu before the user changes it: Search, Home, each library, pinned pages, then the rest. */
    fun defaults(libraryIds: List<String>, pinnedPageIds: List<String>): List<String> =
        listOf(SEARCH, HOME) + libraryIds.map(::library) + pinnedPageIds.map(::page) + listOf(CHANNELS, PAGES, TOGETHER, SETTINGS, PROFILES)

    /**
     * What to show: the saved slots (or [defaults]) without stops that no longer exist (a removed
     * library), trailing empty spots trimmed. Settings is always somewhere, so the menu can't be
     * arranged into a corner with no way back to these options.
     */
    fun arrange(saved: List<String?>?, available: Set<String>, defaults: List<String>): List<String?> {
        // The defaults close up around stops this server doesn't have (Channels without the Companion plugin).
        val kept = (saved ?: defaults.filter { it in available }).map { id -> id?.takeIf { it in available } }.dropLastWhile { it == null }
        return if (SETTINGS in kept || SETTINGS !in available) kept else kept + SETTINGS
    }

    /**
     * Puts [id] in slot [index]. If it was already elsewhere, whatever was in [index] takes its old
     * place, so choosing a stop for a spot moves it rather than copying it.
     */
    fun place(slots: List<String?>, index: Int, id: String): List<String?> {
        val list = slots.toMutableList()
        while (list.size <= index) list.add(null)
        val from = list.indexOf(id)
        if (from >= 0) list[from] = list[index]
        list[index] = id
        return list.dropLastWhile { it == null }
    }

    fun remove(slots: List<String?>, index: Int): List<String?> =
        slots.mapIndexed { i, id -> if (i == index) null else id }.dropLastWhile { it == null }

    /** For bars and rails: the stops in order, gaps closed. */
    fun linear(slots: List<String?>): List<String> = slots.filterNotNull()
}
