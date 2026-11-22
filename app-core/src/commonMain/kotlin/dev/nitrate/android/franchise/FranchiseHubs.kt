package dev.nitrate.android.franchise

import dev.nitrate.core.MediaServer
import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.franchise.FranchiseEntry
import dev.nitrate.core.franchise.FranchiseProgress
import dev.nitrate.core.franchise.Franchises
import dev.nitrate.core.franchise.WatchOrder
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A franchise hub, ready to show: the collection, its titles, everything in the chosen order and where the user is. */
data class FranchiseHub(
    val collection: MediaItem,
    val titles: List<MediaItem>,
    val order: WatchOrder,
    val entries: List<FranchiseEntry>,
    val progress: FranchiseProgress,
    /** The mood suggested from its tags and genres, used when the user hasn't chosen a theme. */
    val mood: PageTheme,
    /** Whose theme music plays: the collection's own, else its first title with one. */
    val themeMusicFrom: String?,
    /** A picture for the whole franchise: the collection's backdrop, else its first title's. */
    val backdrop: String?,
    val logo: String?,
)

/** Builds franchise hubs from the server: the user's collections, each title's episodes fetched side by side. */
object FranchiseHubs {
    /** The user's collections, as possible hubs (whether each has two titles is known once opened). */
    suspend fun list(server: MediaServer): List<MediaItem> = dev.nitrate.core.diagnostics.withQueryOrigin("Franchises") { server.collections() }

    suspend fun load(server: MediaServer, collectionId: String, chosen: WatchOrder?): FranchiseHub = dev.nitrate.core.diagnostics.withQueryOrigin("Franchise hub") {
        coroutineScope {
            val collection = async { server.getItem(collectionId) }
            val titles = server.collectionItems(collectionId).filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
            val episodes = titles.filter { it.kind == ItemKind.SERIES }
                .map { show -> async { show.id to runCatching { server.allEpisodes(show.id) }.getOrDefault(emptyList()) } }
                .awaitAll().toMap()
            val order = chosen ?: Franchises.defaultOrder(titles)
            val entries = Franchises.entries(titles, episodes, order)
            val c = collection.await()
            val music = runCatching { if (server.themeSongs(c.id).isNotEmpty()) c.id else titles.take(4).firstOrNull { server.themeSongs(it.id).isNotEmpty() }?.id }.getOrNull()
            FranchiseHub(
                collection = c,
                titles = titles,
                order = order,
                entries = entries,
                progress = Franchises.progress(entries),
                mood = Franchises.suggestedMood(titles),
                themeMusicFrom = music,
                backdrop = server.backdropUrl(c, 1920) ?: titles.firstNotNullOfOrNull { server.backdropUrl(it, 1920) },
                logo = server.logoUrl(c),
            )
        }
    }
}
