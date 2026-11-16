package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.mobile.platform.PlayerGestures
import dev.nitrate.mobile.platform.Scrub
import dev.nitrate.mobile.platform.VideoSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Brightness and volume swipes start only when meant: not from a flick, an edge, or when turned off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xxhdpi", application = android.app.Application::class)
class SwipeGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val swipes = mutableListOf<Pair<Boolean, Float>>()
    private var enabled = true

    private fun show() {
        val gestures = object : PlayerGestures {
            override val swipes: Boolean get() = enabled
            override fun onSeek(deltaMs: Long) {}
            override fun onTogglePlay() {}
            override fun onSwipe(isBrightness: Boolean, delta: Float) { this@SwipeGestureTest.swipes += isBrightness to delta }
            override fun onSwipeEnd() {}
            override fun onZoom(fill: Boolean) {}
            override fun onScrub(scrub: Scrub?) {}
        }
        compose.setContent { VideoSurface(ExoPlayer.Builder(compose.activity).build(), onControlsVisible = {}, modifier = Modifier.fillMaxSize(), gestures = gestures) }
        compose.waitForIdle()
    }

    /** A drag from [from] to [to] (fractions of the screen) taking [ms]. */
    private fun drag(from: Offset, to: Offset, ms: Long) = compose.onRoot().performTouchInput {
        val a = Offset(width * from.x, height * from.y)
        val b = Offset(width * to.x, height * to.y)
        down(a)
        val steps = 20
        for (i in 1..steps) { advanceEventTime(ms / steps); moveTo(a + (b - a) * (i / steps.toFloat())) }
        up()
    }

    @Test fun slowSwipesOnEachSide() {
        show()
        drag(Offset(0.8f, 0.7f), Offset(0.8f, 0.35f), 1500)
        assertTrue("volume on the right", swipes.isNotEmpty() && swipes.all { !it.first })
        assertTrue("upward raises it", swipes.sumOf { it.second.toDouble() } > 0.2)
        swipes.clear()
        drag(Offset(0.2f, 0.35f), Offset(0.2f, 0.7f), 1500)
        assertTrue("brightness on the left, lowered", swipes.isNotEmpty() && swipes.all { it.first } && swipes.sumOf { it.second.toDouble() } < -0.2)
    }

    @Test fun accidentsDoNothing() {
        show()
        drag(Offset(0.8f, 0.7f), Offset(0.8f, 0.3f), 0) // a quick flick
        assertEquals("flick", emptyList<Pair<Boolean, Float>>(), swipes)
        drag(Offset(0.5f, 0.97f), Offset(0.5f, 0.5f), 1500) // up from the bottom bar
        assertEquals("bottom", emptyList<Pair<Boolean, Float>>(), swipes)
        drag(Offset(0.8f, 0.5f), Offset(0.3f, 0.6f), 1500) // sideways
        assertEquals("sideways", emptyList<Pair<Boolean, Float>>(), swipes)
    }

    @Test fun turnedOff() {
        enabled = false
        show()
        drag(Offset(0.8f, 0.7f), Offset(0.8f, 0.35f), 1500)
        assertEquals(emptyList<Pair<Boolean, Float>>(), swipes)
    }
}
