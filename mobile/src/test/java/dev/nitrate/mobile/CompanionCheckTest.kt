package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Asking the server's Companion what it offers: a failed ask never forgets what was known, and one
 * that failed at start is asked again when the app comes back into view (one failure at sign-in
 * used to hide Watch groups and every other Companion feature until the app was closed).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class CompanionCheckTest {
    /** A server whose Companion can't be reached while [down]. */
    private class Flaky : dev.nitrate.core.MediaServer by FakeServer() {
        var down = false
        private val fake = FakeServer()
        override suspend fun companion() = if (down) error("Unable to resolve host") else fake.companion()
    }

    private fun features(c: AppContainer) = c.companion.value?.features.orEmpty()

    @Test fun aFailedAskKeepsWhatWasKnown() = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
        val server = Flaky()
        val c = AppContainer(activity, "test", "0", serverOverride = server)
        runBlocking { c.refreshCompanionForTests() }
        assertTrue(features(c).toString(), "partners" in features(c))
        server.down = true
        runBlocking { c.refreshCompanionForTests() }
        assertTrue("still known after a failed ask: ${features(c)}", "partners" in features(c))
    }.let { }

    @Test fun oneThatFailedAtStartIsAskedAgainWhenTheAppIsShown() = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
        val server = Flaky().apply { down = true }
        val c = AppContainer(activity, "test", "0", serverOverride = server)
        runBlocking { c.refreshCompanionForTests() }
        assertEquals(null, c.companion.value)
        server.down = false
        c.onAppShown()
        ShadowLooper.idleMainLooper()
        assertTrue("asked again on coming back: ${features(c)}", "partners" in features(c))
    }.let { }
}
