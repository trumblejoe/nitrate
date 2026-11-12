package dev.nitrate.tv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem

/**
 * The account's libraries for the menu: the last list seen straight away (so the menu is whole
 * the moment it opens), then the server's current one, which is remembered for next time.
 */
@Composable
fun rememberLibraries(container: AppContainer): List<MediaItem> {
    val server = container.server
    val libraries by produceState(container.libraryCache.cached(server), server.serverUrl, server.userId) {
        runCatching { container.libraryCache.refresh(server) }.onSuccess { value = it }
    }
    return libraries
}
