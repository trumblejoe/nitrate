package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.mobile.platform.VideoSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The video surface keeps a styled (ASS/SSA) track's own look, and only that track's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubtitleViewStyleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun embeddedStyles(view: SubtitleView): Boolean =
        SubtitleView::class.java.getDeclaredField("applyEmbeddedStyles").apply { isAccessible = true }.getBoolean(view)

    @Test fun followsTheSelectedTrack() {
        val styled = mutableStateOf(false)
        compose.setContent {
            VideoSurface(ExoPlayer.Builder(compose.activity).build(), onControlsVisible = {}, modifier = Modifier.fillMaxSize(), styledSubtitle = styled.value)
        }
        compose.waitForIdle()
        val subtitles = findPlayerView(compose.activity.window.decorView)!!.subtitleView!!
        assertEquals(false, embeddedStyles(subtitles))
        styled.value = true
        compose.waitForIdle()
        assertEquals(true, embeddedStyles(subtitles))
    }

    private fun findPlayerView(v: android.view.View): PlayerView? = when (v) {
        is PlayerView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findPlayerView(v.getChildAt(it)) }
        else -> null
    }
}
