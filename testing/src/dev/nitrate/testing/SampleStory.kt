package dev.nitrate.testing

import dev.nitrate.core.writing.story.Outline
import dev.nitrate.core.writing.story.Ref
import dev.nitrate.core.writing.story.SceneStatus
import dev.nitrate.core.writing.story.Story

/** A short original screenplay with story data, for the story screens' screenshot tests. */
object SampleStory {
    val text = """
        Title: Night Shift

        INT. DINER - NIGHT [[scene:s1aaaaaa]]

        Rain streaks the window. SAM (40s) wipes the counter.

        SAM
        You're late.

        JO
        Traffic. And the bridge is out.

        EXT. PARKING LOT - NIGHT [[scene:s2bbbbbb]]

        Headlights sweep the puddles. A truck idles.

        DRIVER
        Last delivery.

        INT. DINER - KITCHEN - NIGHT [[scene:s3cccccc]]

        SAM
        Lock the back door.

        JO
        Already did.

        EXT. BRIDGE - DAWN [[scene:s4dddddd]]

        JO walks the broken railing alone.
    """.trimIndent()

    val scenes get() = Outline.scenes(text)

    val story: Story by lazy {
        var s = Story()
        fun put(c: dev.nitrate.core.writing.story.RecordChange, who: String, at: Long) { s = s.apply(c, who, who, at) }
        val sam = Story.detectedId(Story.CHARACTERS, "SAM")
        val jo = Story.detectedId(Story.CHARACTERS, "JO")
        put(Story.setEntry(sam, Story.CHARACTERS, "SAM", "Runs the diner's night shift. Hasn't left town in twenty years."), "user-2", 1_700_000_000_000)
        put(Story.setEntry(jo, Story.CHARACTERS, "JO", "Sam's younger sister, back after a long time away."), "user-1", 1_700_000_100_000)
        put(Story.setList("l-props", "Props", dev.nitrate.core.writing.story.ListKind.CUSTOM, 10), "user-1", 1_700_000_200_000)
        put(Story.setEntry("e-keys", "l-props", "The back-door key", "Sam keeps it on a red string."), "user-1", 1_700_000_300_000)
        put(Story.link("k1", Ref.entry(sam), Ref.entry(jo), "sisters"), "user-1", 1_700_000_400_000)
        put(Story.link("k2", Ref.entry("e-keys"), Ref.scene("s3cccccc"), "used in"), "user-2", 1_700_000_500_000)
        put(Story.sceneMeta("s1aaaaaa", synopsis = "Jo arrives late; old tension between the sisters.", color = 5, status = SceneStatus.DONE), "user-1", 1_700_000_600_000)
        put(Story.sceneMeta("s2bbbbbb", synopsis = "The last delivery of the night.", color = 2, assignee = "user-2" to "sam", status = SceneStatus.DRAFTING), "user-2", 1_700_000_700_000)
        put(Story.sceneMeta("s4dddddd", color = 3, status = SceneStatus.TODO), "user-1", 1_700_000_800_000)
        put(Story.comment("c1", "scene:s1aaaaaa", "Can Jo's line land harder?", "sam"), "user-2", 1_700_000_900_000)
        s
    }
}
