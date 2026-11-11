package dev.nitrate.android.playback

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.net.URL

/**
 * Shows [url]'s image where there's no picture (music): Media3's view only displays artwork
 * embedded in the file, so the cover is fetched here and set as the view's default.
 */
@OptIn(UnstableApi::class)
fun PlayerView.showCover(url: String?) {
    if (getTag(TAG_KEY) == url) return
    setTag(TAG_KEY, url)
    if (url == null) {
        defaultArtwork = null
        return
    }
    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
    Thread {
        val bitmap = runCatching { URL(url).openStream().use(BitmapFactory::decodeStream) }.getOrNull() ?: return@Thread
        post { if (getTag(TAG_KEY) == url) defaultArtwork = BitmapDrawable(resources, bitmap) }
    }.start()
}

private val TAG_KEY = "nitrate-cover".hashCode()
