package dev.nitrate.core.partners

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guided setup of a new watch group: what, who, the pace, the Group Watch Session, Together. */
class GroupSetupTest {
    private val severance = MediaItem("sev", "Severance", ItemKind.SERIES)
    private val bear = MediaItem("bear", "The Bear", ItemKind.SERIES)

    @Test fun stepsOnlyGoOnWithWhatTheyNeedAndBackStepsBack() {
        var s = GroupSetup()
        assertEquals("Step 1 of 5 · What to watch", s.heading)
        assertFalse(s.canGoOn)
        assertEquals(s, s.next())
        assertNull(s.back())
        s = s.toggle(severance).toggle(bear).toggle(severance).toggle(severance)
        assertEquals(listOf("bear", "sev"), s.titles.map { it.id })
        s = s.next()
        assertEquals(GroupSetup.Step.WHO, s.step)
        assertFalse(s.canGoOn)
        // Several people: ticked ones and typed names, all invited into one group.
        s = s.pick("tommy")
        assertTrue(s.canGoOn)
        s = s.type("suzy, jessica , Suzy")
        s = s.pick("will").pick("will").pick("kim")
        assertEquals(listOf("tommy" to null, "kim" to null, null to "suzy", null to "jessica"), s.invitees)
        // An older server: one person, a typed name and a pick clearing each other.
        var one = GroupSetup(step = GroupSetup.Step.WHO, several = false).pick("tommy").type("suzy")
        assertEquals(listOf(null to "suzy"), one.invitees)
        one = one.pick("tommy")
        assertEquals("", one.typed)
        assertEquals(listOf("tommy" to null), one.invitees)
        s = s.next().next().next()
        assertEquals(GroupSetup.Step.TOGETHER, s.step)
        assertTrue(s.last)
        assertEquals(s, s.next())
        assertEquals(GroupSetup.Step.SESSION, s.back()!!.step)
        s = s.copy(schedule = s.schedule.copy(together = true))
        assertEquals("The Bear and Severance · Binge, together", s.summary)
        assertEquals(GroupSchedule.TOGETHER_ON, s.schedule.togetherNote)
    }

    @Test fun everyoneInvitedTogetherSharesOneBatch() = kotlinx.coroutines.runBlocking {
        val asked = mutableListOf<Pair<String?, String?>>()
        val batches = mutableSetOf<String?>()
        val unused = java.lang.reflect.Proxy.newProxyInstance(dev.nitrate.core.MediaServer::class.java.classLoader, arrayOf(dev.nitrate.core.MediaServer::class.java)) { _, m, _ -> error("not used: ${m.name}") } as dev.nitrate.core.MediaServer
        val server = object : dev.nitrate.core.MediaServer by unused {
            override suspend fun askPartner(toUserId: String?, toName: String?, seriesId: String, seriesName: String, groupId: String?, schedule: GroupSchedule?, titles: List<GroupTitle>?, batch: String?): OutgoingRequest {
                asked += toUserId to toName; batches += batch
                return OutgoingRequest("r${asked.size}", toName ?: toUserId!!, seriesId, seriesName, null, 0)
            }
        }
        val setup = GroupSetup(titles = listOf(GroupTitle("bear", "The Bear"))).pick("tommy").type("suzy")
        assertEquals(2, setup.send(server, batchId = "b1").size)
        assertEquals(listOf("tommy" to null, null to "suzy"), asked)
        assertEquals(setOf<String?>("b1"), batches)
        // One person: no batch.
        batches.clear(); GroupSetup(titles = listOf(GroupTitle("bear", "The Bear"))).pick("tommy").send(server, batchId = "b2")
        assertEquals(setOf<String?>(null), batches)
    }
}
