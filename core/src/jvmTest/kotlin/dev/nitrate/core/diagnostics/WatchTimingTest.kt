package dev.nitrate.core.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/** What the watch-change timing says, in words. */
class WatchTimingTest {
    private fun report(pressMs: List<Double>, listeners: List<ListenerSummary>, listenersMs: Double) = WatchTimingReport(
        supported = true, running = false, endsAtMs = 0,
        presses = pressMs.map { TimedPress(0, true, it) },
        saves = pressMs.map { TimedSave(0, "Film", "TogglePlayed", listenersMs, emptyList()) },
        listeners = listeners,
    )

    @Test fun namesASlowPlugin() {
        val f = WatchTiming.findings(report(listOf(1900.0, 2100.0, 2000.0), listOf(ListenerSummary("Trakt (ServerEntryPoint)", 3, 1800.0, 2000.0), ListenerSummary("Jellyfin (UserDataChangeNotifier)", 3, 0.2, 0.4)), 1800.0))
        assertTrue(f.toString(), f.any { it.startsWith("Watched/Unwatched took about 2.0 s") })
        assertTrue(f.toString(), f.any { it.startsWith("Trakt (ServerEntryPoint) takes about 1.8 s of every save") })
        assertTrue(f.toString(), f.none { it.startsWith("Jellyfin (UserDataChangeNotifier)") })
    }

    @Test fun blamesTheDatabaseWhenNoListenerIsSlow() {
        val f = WatchTiming.findings(report(listOf(1900.0), listOf(ListenerSummary("Jellyfin (UserDataChangeNotifier)", 1, 0.3, 0.3)), 2.0))
        assertTrue(f.toString(), f.any { it.contains("Jellyfin saving to its database") })
    }

    @Test fun saysToPressWhileItRuns() {
        val f = WatchTiming.findings(WatchTimingReport(true, true, 1, emptyList(), emptyList(), emptyList()))
        assertTrue(f.single().startsWith("Nothing yet: press Watched"))
    }
}
