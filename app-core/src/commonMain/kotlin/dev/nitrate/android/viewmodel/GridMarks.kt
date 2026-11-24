package dev.nitrate.android.viewmodel

import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState

/**
 * Watch-state changes put onto a library page's loaded titles where they are (Browse, the library
 * grids), with nothing reloaded and nothing moved: a title marked watched on an Unwatched tab stays,
 * with its new mark, until the page is opened again.
 */
object GridMarks {
    private val HOLDERS = setOf(ItemKind.SERIES, ItemKind.SEASON)

    /**
     * The new state of each title in [shown] that [change] touched: as the server sent it, else
     * asked for (only those). A show or season whose episode changed is asked for too, when the
     * server didn't send it alongside.
     */
    suspend fun of(server: MediaServer, shown: List<MediaItem>, change: ServerEvent.UserDataChanged): Map<String, UserState> {
        if (shown.isEmpty() || change.itemIds.isEmpty()) return emptyMap()
        val byId = shown.associateBy { it.id }
        val sent = change.states.filterKeys { it in byId }
        val ask = change.itemIds.filter { it in byId && it !in change.states }.toMutableSet()
        // An episode not on this page may be in a show or season that is.
        val elsewhere = change.itemIds.filter { it !in byId }
        if (elsewhere.isNotEmpty() && shown.any { it.kind in HOLDERS }) {
            runCatching { server.parentIds(elsewhere) }.getOrDefault(emptyMap()).values.flatten()
                .filter { it in byId && it !in change.states }.forEach { ask += it }
        }
        val asked = if (ask.isEmpty()) emptyMap() else runCatching { server.userStates(ask.toList()) }.getOrDefault(emptyMap())
        return sent + asked
    }

    /** [items] showing [states]; the same list when none of them changed. */
    fun apply(items: List<MediaItem>, states: Map<String, UserState>): List<MediaItem> =
        if (states.isEmpty() || items.none { it.id in states }) items
        else items.map { item -> states[item.id]?.takeIf { it != item.user }?.let { item.copy(user = it) } ?: item }
}
