package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameStatsTest {
    @Test fun framesAreCountedByWhatWasOpen() {
        val f = FrameStats()
        // At 23.976 Hz one refresh is 41.7 ms.
        val refresh = 1000.0 / 23.976
        repeat(8) { f.record("nothing open", 12.0, refresh) }
        f.record("More open", 30.0, refresh)
        f.record("More open", 55.0, refresh)
        f.record("More open", 260.0, refresh)
        assertEquals(
            listOf(
                "With nothing open: 8 frames, 0 late (over one refresh, 41.7 ms), 0 over 100 ms, typical 12 ms, worst 12 ms",
                "With More open: 3 frames, 2 late (over one refresh, 41.7 ms), 1 over 100 ms, typical 55 ms, worst 260 ms",
            ),
            f.lines(),
        )
    }
}
