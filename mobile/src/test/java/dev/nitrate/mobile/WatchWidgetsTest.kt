package dev.nitrate.mobile

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.widgets.WidgetCard
import dev.nitrate.core.widgets.WidgetKind
import dev.nitrate.mobile.widgets.Content
import dev.nitrate.mobile.widgets.Shown
import dev.nitrate.mobile.widgets.WatchWidgets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WatchWidgetsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun titlesListAndEachPlaysItself() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 220.dp))
        val cards = listOf(
            WidgetCard("e1", "Gate Weave", "S2 · E5", 0.4f, "Gate Weave, season 2, episode 5"),
            WidgetCard("m1", "The Night Reel", "2019", null, "The Night Reel, 2019"),
        )
        provideComposable { Content(context, WidgetKind.CONTINUE, Shown(cards.map { it to null }, null)) }
        onNode(hasText("Continue watching")).assertExists()
        onNode(hasText("Gate Weave")).assertExists()
        onNode(hasText("S2 · E5")).assertExists()
        // Never the episode's own name: a home screen shouldn't spoil.
        onAllNodes(hasText("Secret")).assertCountEquals(0)
        onNode(hasStartActivityClickAction(WatchWidgets.playIntent(context, "m1"))).assertExists()
    }

    @Test fun emptySaysWhy() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(200.dp, 120.dp))
        provideComposable { Content(context, WidgetKind.NEXT_UP, Shown(emptyList(), "Open Nitrate to sign in")) }
        onNode(hasText("Next up")).assertExists()
        onNode(hasText("Open Nitrate to sign in")).assertExists()
    }
}
