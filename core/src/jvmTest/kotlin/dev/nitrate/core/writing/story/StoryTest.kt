package dev.nitrate.core.writing.story

import dev.nitrate.core.writing.TextOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTest {
    private val script = """
        Title: Night Shift

        INT. DINER - NIGHT [[scene:aaaa1111]]

        SAM
        You're late.

        JO (V.O.)
        Traffic.

        EXT. PARKING LOT - NIGHT [[scene:bbbb2222]]

        Rain.

        INT. DINER - LATER [[scene:cccc3333]]

        @McCLANE
        Coffee.
    """.trimIndent()

    @Test fun scenesComeFromHeadingsWithTheirPeopleAndPlaces() {
        val scenes = Outline.scenes(script)
        assertEquals(listOf("aaaa1111", "bbbb2222", "cccc3333"), scenes.map { it.id })
        assertEquals("INT. DINER - NIGHT", scenes[0].heading)
        assertEquals(setOf("SAM", "JO"), scenes[0].characters)
        assertEquals(setOf("McCLANE"), scenes[2].characters)
        assertEquals(listOf("DINER", "PARKING LOT", "DINER"), scenes.map { it.location })
        assertEquals(script.indexOf("INT. DINER - NIGHT"), scenes[0].start)
        assertEquals(scenes[1].start, scenes[0].end)
        assertEquals(script.length, scenes[2].end)
        assertEquals("BEACH HOUSE", Outline.locationOf(".BEACH HOUSE - DAWN"))
        assertEquals("CAR", Outline.locationOf("INT./EXT. CAR - MOVING"))
    }

    @Test fun headingsGetMarkersOnceAndDoublesAreDropped() {
        val text = "INT. ROOM - DAY\n\nAction.\n\nEXT. YARD - DAY [[scene:x1x1x1]] [[scene:y2y2y2]]\n"
        var n = 0
        val op = Outline.addIds(text) { "id${n++}aaaa" }!!
        val done = op.apply(text)
        assertEquals("INT. ROOM - DAY [[scene:id0aaaa]]\n\nAction.\n\nEXT. YARD - DAY [[scene:x1x1x1]]\n", done)
        assertNull(Outline.addIds(done))
        assertEquals("INT. ROOM - DAY\n\nAction.\n\nEXT. YARD - DAY\n", Outline.stripMarkers(done))
    }

    @Test fun movingAScenCardMovesItsText() {
        val toEnd = Outline.move(script, "aaaa1111", 3)!!.apply(script)
        assertEquals(listOf("bbbb2222", "cccc3333", "aaaa1111"), Outline.scenes(toEnd).map { it.id })
        assertTrue(toEnd, toEnd.contains("Coffee.\n\nINT. DINER - NIGHT [[scene:aaaa1111]]\n\nSAM\nYou're late."))
        val toStart = Outline.move(script, "cccc3333", 0)!!.apply(script)
        assertEquals(listOf("cccc3333", "aaaa1111", "bbbb2222"), Outline.scenes(toStart).map { it.id })
        assertTrue(toStart.startsWith("Title: Night Shift\n\nINT. DINER - LATER [[scene:cccc3333]]\n\n@McCLANE\nCoffee.\n\nINT. DINER - NIGHT"))
        // Every scene's own text survives the move.
        Outline.scenes(script).forEach { s ->
            val body = script.substring(s.start, s.end).trimEnd()
            assertTrue(toStart.contains(body))
            assertTrue(toEnd.contains(body))
        }
        assertNull(Outline.move(script, "aaaa1111", 1))
    }

    @Test fun addingASceneAddsAHeading() {
        val after = Outline.add(script, "int. kitchen - day", "aaaa1111", "dddd4444").apply(script)
        val scenes = Outline.scenes(after)
        assertEquals(listOf("aaaa1111", "dddd4444", "bbbb2222", "cccc3333"), scenes.map { it.id })
        assertEquals("INT. KITCHEN - DAY", scenes[1].heading)
        val atEnd = Outline.add(script, "beach", null, "eeee5555").apply(script)
        assertTrue(atEnd, atEnd.endsWith("Coffee.\n\n.BEACH [[scene:eeee5555]]\n"))
        assertEquals("BEACH", Outline.scenes(atEnd).last().location)
    }

    @Test fun listsShowDetectedNamesUntilSomeoneEditsThem() {
        val scenes = Outline.scenes(script)
        var story = Story()
        val chars = story.lists().first { it.kind == ListKind.CHARACTERS }
        assertEquals(listOf("JO", "McCLANE", "SAM"), story.listItems(chars, scenes).map { it.name })
        assertTrue(story.listItems(chars, scenes).all { it.record == null })
        // Editing a detected name makes it real, with the same id on every device.
        val samId = Story.detectedId(chars.id, "SAM")
        story = story.apply(Story.setEntry(samId, chars.id, "SAM", "A night-shift cook, 40s."), "u1", "ann", 100)
        val sam = story.listItems(chars, scenes).first { it.name == "SAM" }
        assertEquals(samId, sam.id)
        assertEquals("A night-shift cook, 40s.", sam.notes)
        assertEquals(("u1" to "ann"), sam.record!!.updatedBy to sam.record!!.updatedByName)
        assertEquals(1, story.listItems(chars, scenes).count { it.name == "SAM" })
        assertEquals(listOf(0), story.scenesOf(sam, chars, scenes).map { it.index })
        val places = story.lists().first { it.kind == ListKind.LOCATIONS }
        assertEquals(listOf("DINER", "PARKING LOT"), story.listItems(places, scenes).map { it.name })
    }

    @Test fun changesRememberWhoAndWhenAndFieldsMergeOneByOne() {
        var story = Story()
        story = story.apply(Story.sceneMeta("aaaa1111", synopsis = "Sam waits."), "u1", "ann", 10)
        story = story.apply(Story.sceneMeta("aaaa1111", assignee = "u2" to "bob", status = SceneStatus.DRAFTING), "u2", "bob", 20)
        val meta = story.sceneMeta("aaaa1111")
        assertEquals("Sam waits.", meta.synopsis)
        assertEquals("bob", meta.assigneeName)
        assertEquals(SceneStatus.DRAFTING, meta.status)
        assertEquals("u1", meta.record!!.createdBy)
        assertEquals(20L, meta.record!!.updatedAt)
        story = story.apply(Story.sceneMeta("aaaa1111", assignee = "" to "", clearStatus = true), "u1", "ann", 30)
        assertNull(story.sceneMeta("aaaa1111").assigneeId)
        assertNull(story.sceneMeta("aaaa1111").status)
    }

    @Test fun commentsAndReplies() {
        var story = Story()
        story = story.apply(Story.comment("c1", "scene:aaaa1111", "Too long?", "ann", quote = "You're late."), "u1", "ann", 10)
        story = story.apply(Story.comment("c2", "scene:aaaa1111", "Agreed", "bob", parentId = "c1"), "u2", "bob", 20)
        story = story.apply(Story.resolve("c1", true), "u2", "bob", 30)
        val list = story.comments("scene:aaaa1111")
        assertEquals(listOf("c1", "c2"), list.map { it.id })
        assertTrue(list[0].resolved)
        assertEquals("You're late.", list[0].quote)
        assertEquals("c1", list[1].parentId)
        assertEquals("bob", list[1].authorName)
    }

    @Test fun theMapJoinsPeopleAndPlacesToScenesAndFilters() {
        val scenes = Outline.scenes(script)
        var story = Story()
        val sam = Story.detectedId(Story.CHARACTERS, "SAM")
        val jo = Story.detectedId(Story.CHARACTERS, "JO")
        story = story.apply(Story.link("l1", Ref.entry(sam), Ref.entry(jo), "siblings"), "u1", "ann", 1)
        story = story.apply(Story.link("l2", Ref.entry(jo), Ref.scene("bbbb2222"), "mentioned"), "u1", "ann", 2)
        val map = StoryGraph.build(story, scenes)
        assertEquals(3 + 3 + 2, map.nodes.size) // scenes, characters, locations
        val kinds = map.edges.groupingBy { it.kind }.eachCount()
        assertEquals(mapOf(EdgeKind.APPEARS to 3, EdgeKind.SET_IN to 3, EdgeKind.RELATES to 1, EdgeKind.LINKED to 1), kinds)
        assertEquals("siblings", map.edges.first { it.kind == EdgeKind.RELATES }.label)

        val onlyPeople = StoryGraph.filter(map, MapFilter(edgeKinds = setOf(EdgeKind.RELATES), showScenes = false, lists = setOf(Story.CHARACTERS), hideLoose = true))
        assertEquals(setOf("SAM", "JO"), onlyPeople.nodes.map { it.label }.toSet())
        assertEquals(1, onlyPeople.edges.size)

        val aroundJo = StoryGraph.filter(map, MapFilter(focus = Ref.entry(jo)))
        assertEquals(setOf("JO", "SAM", "INT. DINER - NIGHT", "EXT. PARKING LOT - NIGHT"), aroundJo.nodes.map { it.label }.toSet())

        val layout = StoryGraph.layout(map)
        assertEquals(map.nodes.size, layout.size)
        assertTrue(layout.values.all { (x, y) -> x in 0f..1f && y in 0f..1f })
        assertEquals(layout, StoryGraph.layout(map)) // always the same
        val first = layout.getValue(Ref.scene("aaaa1111"))
        val last = layout.getValue(Ref.scene("cccc3333"))
        assertTrue(first.first < last.first)
        assertEquals(Ref.scene("bbbb2222"), StoryGraph.nearest(Ref.scene("aaaa1111"), 1, 0, layout.filterKeys { it.kind == Ref.Kind.SCENE }))
    }

    @Test fun attributionFollowsTheTextAndMovesKeepTheirWriters() {
        val base = "Jo arrives from the rain.\n\nSam waits at the counter.\n\n"
        var a = Attribution.start(base.length, "ann")
        var text = base
        fun edit(op: TextOperation, who: String) {
            a = a.apply(op, who, text)
            text = op.apply(text)
            assertEquals(text.length, a.length)
        }
        edit(TextOperation.diff(text, text.replace("the counter", "the long counter")), "bob")
        assertEquals("bob", a.at(text.indexOf("long")))
        assertEquals("ann", a.at(0))
        // Bob moves Sam's block to the top (as moving a scene card does): still Ann's words, and his.
        val block = "Sam waits at the long counter.\n\n"
        val start = text.indexOf(block)
        edit(TextOperation.of(TextOperation.Insert(block), TextOperation.Retain(start), TextOperation.Delete(block.length)), "bob")
        assertEquals("Sam waits at the long counter.\n\nJo arrives from the rain.\n\n", text)
        assertEquals("ann", a.at(0))
        assertEquals("bob", a.at(text.indexOf("long")))
        assertEquals(mapOf("ann" to text.length - 5, "bob" to 5), a.shares())
        // Short repeats are typing, not moves.
        edit(TextOperation.diff(text, text.replace("Jo", "JoJo")), "cy")
        assertEquals("cy", a.at(text.indexOf("JoJo") + 2))
    }

    @Test fun activityGroupsBursts() {
        fun act(v: Long, who: String, at: Long, scene: String) =
            Activity(who, who, at, at, Ref.scene(scene), scene, Activity.Kind.WROTE, 1, v, v)
        val grouped = Activities.group(listOf(act(1, "ann", 0, "s1"), act(2, "ann", 60_000, "s1"), act(3, "bob", 70_000, "s1"), act(4, "ann", 80_000, "s2"), act(5, "ann", 3_600_000, "s2")))
        assertEquals(listOf(5L to 5L, 4L to 4L, 3L to 3L, 1L to 2L), grouped.map { it.fromVersion to it.toVersion })
        assertEquals(2, grouped.last().count)
    }
}
