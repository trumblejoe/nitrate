package dev.nitrate.core.lists

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@kotlinx.serialization.Serializable
enum class ListKind(val label: String, val plural: String) { PLAYLIST("playlist", "Playlists"), COLLECTION("collection", "Collections") }

/** Somewhere an item can be added: an existing playlist or collection, or a new one ([id] null) to create. */
@kotlinx.serialization.Serializable
data class ListTarget(val kind: ListKind, val id: String?, val name: String) {
    val isNew get() = id == null
    /** Stable key for selection (new lists by name). */
    val key get() = "${kind.name}:${id ?: "new:$name"}"
}

/** How adding to one target went: [error] is null when it worked. */
data class ListResult(val target: ListTarget, val error: String?)

/** The user's playlists and collections, as targets, sorted by name. */
@kotlinx.serialization.Serializable
data class ListChoices(val playlists: List<ListTarget>, val collections: List<ListTarget>)

/**
 * Adding an item to several playlists and collections at once: loading the choices (both lists
 * together, so the picker doesn't jump while one arrives), and carrying out a reviewed selection.
 */
object AddToLists {
    /** Where the last choices are kept (per account) so the picker opens with them. */
    fun cacheKey(canCollect: Boolean) = "lists/choices/$canCollect"

    suspend fun load(server: MediaServer, canCollect: Boolean): ListChoices = coroutineScope {
        val playlists = async { runCatching { server.playlists() }.getOrDefault(emptyList()) }
        val collections = async { if (canCollect) runCatching { server.collections() }.getOrDefault(emptyList()) else emptyList() }
        fun List<MediaItem>.targets(kind: ListKind) = map { ListTarget(kind, it.id, it.name.orEmpty()) }.sortedBy { it.name.lowercase() }
        ListChoices(playlists.await().targets(ListKind.PLAYLIST), collections.await().targets(ListKind.COLLECTION))
    }

    /** Adds [item] to every target in turn (creating new ones), reporting each outcome; one failure doesn't stop the rest. */
    suspend fun apply(server: MediaServer, item: MediaItem, targets: List<ListTarget>, audio: Boolean): List<ListResult> = targets.map { t ->
        val outcome = try {
            when {
                t.kind == ListKind.PLAYLIST && t.isNew -> server.createPlaylist(t.name.trim(), listOf(item.id), audio)
                t.kind == ListKind.PLAYLIST -> server.addToPlaylist(t.id!!, listOf(item.id))
                t.isNew -> server.createCollection(t.name.trim(), listOf(item.id))
                else -> server.addToCollection(t.id!!, listOf(item.id))
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "failed"
        }
        ListResult(t, outcome)
    }

    /** One line saying where the item goes, e.g. "2 playlists and 1 collection". */
    fun summary(targets: List<ListTarget>): String = ListKind.entries.mapNotNull { k ->
        val n = targets.count { it.kind == k }
        if (n == 0) null else "$n ${k.label}${if (n == 1) "" else "s"}"
    }.joinToString(" and ")
}
