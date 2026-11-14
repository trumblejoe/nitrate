package dev.nitrate.mobile.ui

/** The app's [dev.nitrate.android.storage.DataCache], when running in the app (not in previews or tests). */
@androidx.compose.runtime.Composable
fun rememberDataCache(): dev.nitrate.android.storage.DataCache? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember { (context.applicationContext as? dev.nitrate.android.AppContainer.Owner)?.container?.dataCache }
}

/**
 * The user's playlists and collections for an Add-to picker: last time's at once (from the cache),
 * then fresh ones from the server.
 */
@androidx.compose.runtime.Composable
fun rememberListChoices(server: dev.nitrate.core.MediaServer, canCollect: Boolean): androidx.compose.runtime.State<dev.nitrate.core.lists.ListChoices?> {
    val cache = rememberDataCache()
    return androidx.compose.runtime.produceState<dev.nitrate.core.lists.ListChoices?>(null, canCollect) {
        val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
        val key = dev.nitrate.core.lists.AddToLists.cacheKey(canCollect)
        cache?.read(account, key, dev.nitrate.core.lists.ListChoices.serializer())?.let { value = it }
        val fresh = dev.nitrate.core.lists.AddToLists.load(server, canCollect)
        value = fresh
        cache?.write(account, key, fresh, dev.nitrate.core.lists.ListChoices.serializer())
    }
}
