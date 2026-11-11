package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.nitrate.android.AppContainer
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.ConnectScreen
import dev.nitrate.mobile.ui.DetailsScreen
import dev.nitrate.mobile.ui.LoginScreen
import dev.nitrate.mobile.ui.MainScreen
import dev.nitrate.mobile.ui.NitrateTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main phone/tablet screens against [FakeServer] so layouts can be checked without a
 * device. PNGs land in build/outputs/roborazzi. Images are blank placeholders (no network).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, content: @Composable (AppActions) -> Unit) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer())
        compose.setContent {
            NitrateTheme {
                // Same root as the app: background and text colours come from this Surface.
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    content(AppActions(container, nav) { _, _ -> })
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun connect() = shoot("phone-connect") { ConnectScreen(it) { _, _ -> } }

    @Test fun login() = shoot("phone-login") { LoginScreen(it, "http://192.168.1.20:8096", "Home") {} }

    @Test fun home() = shoot("phone-home") { MainScreen(it) {} }

    @Test fun details() = shoot("phone-series") { DetailsScreen(it, "series-1", null) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun tabletHome() = shoot("tablet-home") { MainScreen(it) {} }

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun lightDetails() = shoot("phone-movie-light") { DetailsScreen(it, "m1", null) }
}
