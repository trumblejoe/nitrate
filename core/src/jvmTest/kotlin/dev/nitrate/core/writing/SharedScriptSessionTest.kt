package dev.nitrate.core.writing

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SharedScriptSessionTest {

    /** The Companion plugin's rules, in memory: changes only on the latest version, long polling. */
    private class FakeScripts(initial: String) {
        var text = initial
        val edits = mutableListOf<ScriptEdit>()
        val version get() = edits.size.toLong()
        val changed = MutableStateFlow(0L)
        var offline = false
        /** Takes the next change but "loses" the answer (as when the connection drops). */
        var loseNextAnswer = false
        var keep = Int.MAX_VALUE
        val presence = mutableMapOf<String, ScriptPresence>()

        private val unused: MediaServer = java.lang.reflect.Proxy.newProxyInstance(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) { _, m, _ ->
            error("not used: ${m.name}")
        } as MediaServer

        fun serverFor(user: String): MediaServer = object : MediaServer by unused {
            override suspend fun sendScriptEdit(id: String, editId: String, baseVersion: Long, op: TextOperation, cursor: Int?): ScriptEditResult {
                if (offline) throw java.io.IOException("offline")
                edits.firstOrNull { it.editId == editId }?.let { return ScriptEditResult(true, it.version) }
                if (baseVersion != version) return ScriptEditResult(false, version)
                text = op.apply(text)
                edits += ScriptEdit(version + 1, editId, user, user, op)
                cursor?.let { presence[user] = ScriptPresence(user, user, it, version) }
                changed.value++
                if (loseNextAnswer) { loseNextAnswer = false; throw java.io.IOException("connection dropped") }
                return ScriptEditResult(true, version)
            }

            override suspend fun scriptEdits(id: String, after: Long, seen: Long?, waitMs: Int): ScriptEditsPage {
                if (offline) throw java.io.IOException("offline")
                val oldest = edits.size - minOf(edits.size, keep)
                if (after < version && after < oldest) throw ScriptTooFarBehind()
                if (after == version && waitMs > 0 && (seen == null || seen >= changed.value)) {
                    val start = changed.value
                    withTimeoutOrNull(waitMs.toLong()) { changed.first { it != start } }
                }
                if (offline) throw java.io.IOException("offline")
                return ScriptEditsPage(edits.filter { it.version > after }, version, presence.values.filter { it.userId != user }, changed.value)
            }

            override suspend fun sendScriptPresence(id: String, cursor: Int, version: Long) {
                presence[user] = ScriptPresence(user, user, cursor, version)
                changed.value++
            }

            override suspend fun sharedScript(id: String): SharedScriptDoc =
                SharedScriptDoc(SharedScriptInfo(id, "t", UserRef("o", "o"), emptyList(), version, 0), text, version)
        }
    }

    private var ids = 0
    private fun TestScope.session(fake: FakeScripts, user: String, state: SharedScriptState = SharedScriptState(fake.version, fake.text, fake.text), saved: MutableList<SharedScriptState> = mutableListOf(), conflicts: MutableList<String> = mutableListOf()) =
        SharedScriptSession(fake.serverFor(user), backgroundScope, "s1", state, { saved += it }, { conflicts += it }) { "$user-${ids++}" }

    private fun SharedScriptSession.type(newText: String) = edit(newText, newText.length)

    @Test fun twoWritersTypingAtOnceEndUpWithTheSameScript() = runTest {
        val fake = FakeScripts("INT. DINER - NIGHT\n\nSam waits.")
        val a = session(fake, "ann").also { it.start() }
        val b = session(fake, "bob").also { it.start() }
        runCurrent()
        a.type("INT. DINER - NIGHT\n\nSam waits. Rain on the glass.")
        b.type("INT. DINER - LATE NIGHT\n\nSam waits.")
        advanceTimeBy(5_000)
        runCurrent()
        val expected = "INT. DINER - LATE NIGHT\n\nSam waits. Rain on the glass."
        assertEquals(expected, fake.text)
        assertEquals(expected, a.text.value)
        assertEquals(expected, b.text.value)
        assertFalse(a.pending.value || b.pending.value)
        a.close(); b.close()
    }

    @Test fun manyRandomEditsFromThreeWritersConverge() {
        for (seed in 1..12) randomWriters(seed)
    }

    private fun randomWriters(seed: Int) = runTest {
        val r = Random(seed)
        val fake = FakeScripts("The quick brown fox.")
        val writers = listOf("ann", "bob", "cy").map { session(fake, it).also { s -> s.start() } }
        runCurrent()
        repeat(150) {
            val w = writers[r.nextInt(writers.size)]
            val t = w.text.value
            val at = r.nextInt(t.length + 1)
            val next = if (r.nextBoolean() || t.isEmpty()) t.substring(0, at) + "xyz,. \n".random(r) + t.substring(at)
            else t.removeRange(at.coerceAtMost(t.length - 1), (at + 1 + r.nextInt(3)).coerceAtMost(t.length))
            w.type(next)
            advanceTimeBy(r.nextLong(0, 700))
        }
        advanceTimeBy(30_000)
        runCurrent()
        writers.forEach { assertEquals(fake.text, it.text.value) }
        writers.forEach { it.close() }
    }

    @Test fun changesMadeOfflineAreMergedWhenBack() = runTest {
        val fake = FakeScripts("one")
        val a = session(fake, "ann").also { it.start() }
        val b = session(fake, "bob").also { it.start() }
        runCurrent()
        fake.offline = true
        a.type("one two")
        advanceTimeBy(3_000)
        assertEquals(SharedScriptSession.Status.OFFLINE, a.status.value)
        assertTrue(a.pending.value)
        fake.offline = false
        // Bob was "elsewhere", writing on a server that's back first.
        b.type("zero one")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("zero one two", fake.text)
        assertEquals(fake.text, a.text.value)
        assertEquals(fake.text, b.text.value)
        a.close(); b.close()
    }

    @Test fun aChangeWhoseAnswerWasLostIsNeverAppliedTwice() = runTest {
        val fake = FakeScripts("abc")
        val saved = mutableListOf<SharedScriptState>()
        val a = session(fake, "ann", saved = saved).also { it.start() }
        runCurrent()
        fake.loseNextAnswer = true
        a.type("abcd")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("abcd", fake.text)
        assertEquals("abcd", a.text.value)
        assertEquals(1, fake.edits.size)
        a.close()
    }

    @Test fun aRestartPicksUpWhatWasntSentAndWhatWasSentButNotConfirmed() = runTest {
        val fake = FakeScripts("abc")
        val saved = mutableListOf<SharedScriptState>()
        val a = session(fake, "ann", saved = saved).also { it.start() }
        runCurrent()
        // The change reaches the server but the app closes before hearing back...
        fake.loseNextAnswer = true
        a.type("abcd")
        advanceTimeBy(SharedScriptSession.SEND_DELAY_MS + 10)
        runCurrent()
        a.close()
        // ...and more was typed offline before closing.
        // The state kept while the change was out (the app could close at any moment after it).
        val lastState = saved.last { it.sentId != null }.let { it.copy(text = it.text + "e") }
        assertTrue(lastState.sentId != null)
        val again = session(fake, "ann", state = lastState).also { it.start() }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("abcde", fake.text)
        assertEquals("abcde", again.text.value)
        assertEquals(2, fake.edits.size)
        again.close()
    }

    @Test fun othersCursorsFollowTheText() = runTest {
        val fake = FakeScripts("hello world")
        val a = session(fake, "ann").also { it.start() }
        val b = session(fake, "bob").also { it.start() }
        runCurrent()
        b.moveCursor(6) // before "world"
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(OtherCursor("bob", "bob", 6)), a.others.value)
        a.edit(">> hello world", 3)
        assertEquals(9, a.others.value.single().cursor) // moved with the text right away
        advanceTimeBy(5_000)
        runCurrent()
        // Confirmed on the server: bob's cursor (still sent at the old version) is placed through ann's change.
        assertFalse(a.pending.value)
        assertEquals(9, a.others.value.single().cursor)
        a.close(); b.close()
    }

    @Test fun fallingTooFarBehindReloadsAndKeepsUnsentChangesAsACopy() = runTest {
        val fake = FakeScripts("start")
        val conflicts = mutableListOf<String>()
        val a = session(fake, "ann", conflicts = conflicts)
        // Others made lots of changes the server no longer keeps, while ann typed offline.
        val other = fake.serverFor("bob")
        repeat(5) { i -> other.sendScriptEdit("s1", "b$i", i.toLong(), TextOperation.of(TextOperation.Retain(5 + i), TextOperation.Insert("!")), null) }
        fake.keep = 2
        a.type("start, mine")
        a.start()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("start!!!!!", a.text.value)
        assertEquals(listOf("start, mine"), conflicts)
        a.close()
    }

    @Test fun stateRoundTrips() {
        val op = TextOperation.of(TextOperation.Retain(2), TextOperation.Insert("x\"y"), TextOperation.Delete(1))
        assertEquals(op, SharedScriptSession.decode(SharedScriptSession.encode(op)))
    }
}
