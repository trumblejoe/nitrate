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

/** A franchise hub, ready to show: the playlist, everything in the chosen order and where the user is. */
data class FranchiseHub(
    val playlist: MediaItem,
    val order: WatchOrder,
    val entries: List<FranchiseEntry>,
    val progress: FranchiseProgress,
    val films: Int,
    val shows: Int,
    /** The mood suggested from its titles' tags and genres, used when the user hasn't chosen a theme. */
    val mood: PageTheme,
    /** Whose theme music plays: the playlist's own, else its first titles' with one. */
    val themeMusicFrom: String?,
    /** A picture for the whole franchise: the playlist's backdrop, else its first title's. */
    val backdrop: String?,
    val logo: String?,
)

/** Builds franchise hubs from the user's video playlists, whole shows' episodes fetched side by side. */
object FranchiseHubs {
    /** The user's video playlists, as possible hubs. */
    suspend fun list(server: MediaServer): List<MediaItem> = dev.nitrate.core.diagnostics.withQueryOrigin("Franchises") { Franchises.candidates(server.playlists()) }

    suspend fun load(server: MediaServer, playlistId: String, chosen: WatchOrder?): FranchiseHub = dev.nitrate.core.diagnostics.withQueryOrigin("Franchise hub") {
        coroutineScope {
            val playlist = async { server.getItem(playlistId) }
            val items = server.playlistItems(playlistId)
            val episodes = Franchises.wholeShows(items)
                .map { id -> async { id to runCatching { server.allEpisodes(id) }.getOrDefault(emptyList()) } }
                .awaitAll().toMap()
            val order = chosen ?: WatchOrder.PLAYLIST
            val entries = Franchises.entries(items, episodes, order)
            val p = playlist.await()
            val flat = entries.map { it.item }
            val titleIds = Franchises.titleIds(items)
            val music = runCatching {
                if (server.themeSongs(p.id).isNotEmpty()) p.id else titleIds.take(4).firstOrNull { server.themeSongs(it).isNotEmpty() }
            }.getOrNull()
            FranchiseHub(
                playlist = p,
                order = order,
                entries = entries,
                progress = Franchises.progress(entries),
                films = flat.count { it.kind == ItemKind.MOVIE },
                shows = flat.mapNotNull { it.seriesId }.distinct().size,
                mood = Franchises.suggestedMood(items),
                themeMusicFrom = music,
                backdrop = server.backdropUrl(p, 1920) ?: flat.firstNotNullOfOrNull { server.backdropUrl(it, 1920) },
                logo = server.logoUrl(p),
            )
        }
    }
}
