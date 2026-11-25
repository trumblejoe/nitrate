package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.android.storage.CachedPage
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.rows.ChangeArea
import dev.nitrate.core.rows.ChangeCheck
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowsProgress
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A page opened again keeps the rows nothing has changed for since last time, asking only for the rest. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class RowChangesPageTest {
    private fun row(key: String, title: String = key) = MediaRow(key, title, listOf(MediaItem("$key-1", "$title film", ItemKind.MOVIE)), RowDisplay())

    private val asked = mutableListOf<Set<String>>()
    private val fake = FakeServer()
    // Only the user's watch state changed between them.
    private val t1 = dev.nitrate.core.rows.RowChanges.token("e", 1, 1, 1, 1, 0)
    private val t2 = dev.nitrate.core.rows.RowChanges.token("e", 1, 1, 1, 2, 0)
    private val playlistReads = mutableListOf<Int>()
    private val server = object : MediaServer by fake {
        override suspend fun changeCheck(since: String?) = ChangeCheck(t2, dev.nitrate.core.rows.RowChanges.compare(since, t2))
        override suspend fun withFreshMarks(rows: List<MediaRow>) = rows.map { it.copy(title = it.title + " (marks)") }
        override suspend fun playlistRow(playlistId: String, limit: Int): List<MediaItem> { playlistReads += limit; return listOf(MediaItem("p-1", "From the playlist", ItemKind.MOVIE)) }
    }
    private val loader: (Set<String>) -> kotlinx.coroutines.flow.Flow<RowsProgress> = { hidden ->
        asked += hidden
        flowOf(RowsProgress(listOf(row("home/continue_watching", "Continue new"), row("home/latest/m", "Latest new")).filter { it.key !in hidden }, true))
    }

    private fun settle(vm: RowsViewModel, until: (List<String>) -> Boolean): List<String> {
        // Generous: the first test in a fresh test run starts slowly.
        val end = System.currentTimeMillis() + 20_000
        var titles = emptyList<String>()
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(); Thread.sleep(10)
            titles = (vm.state.value as? RowsState.Loaded)?.rows?.map { it.title }.orEmpty()
            if (until(titles)) break
        }
        return titles
    }

    private fun withContainer(block: (AppContainer) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val c = AppContainer(activity, "test", "0", serverOverride = fake)
            runBlocking { c.displaySettings.import("user-1", emptyMap()) }
            block(c)
        }
    }

    @Test fun onlyWhatChangedIsAskedFor() = withContainer { c ->
        val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
        runBlocking { c.dataCache.write(account, PageKeys.HOME, CachedPage(listOf(row("home/latest/m", "Latest"), row("home/continue_watching", "Continue")), stamps = t1)) }
        val vm = RowsViewModel(PageKeys.HOME, { emptyList() }, c.displaySettings, server, cache = c.dataCache, loadProgressSkipping = loader)
        val titles = settle(vm) { "Continue new" in it }
        // Only the user's watching changed: "Latest in…" is kept (its marks read afresh) and never asked for.
        assertEquals(listOf("Latest (marks)", "Continue new"), titles)
        assertTrue("home/latest/m" in asked.single())
        // The new counters are kept with the page for next time.
        val saved = runBlocking { c.dataCache.read(account, PageKeys.HOME) }
        assertEquals(t2, saved?.stamps)
    }

    @Test fun pullingToRefreshLoadsEverything() = withContainer { c ->
        val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
        runBlocking { c.dataCache.write(account, PageKeys.HOME, CachedPage(listOf(row("home/latest/m", "Latest")), stamps = t1)) }
        val vm = RowsViewModel(PageKeys.HOME, { emptyList() }, c.displaySettings, server, cache = c.dataCache, loadProgressSkipping = loader)
        settle(vm) { "Continue new" in it }
        asked.clear()
        vm.refresh()
        val titles = settle(vm) { "Latest new" in it }
        // The loader's own order, as any whole load.
        assertEquals(listOf("Continue new", "Latest new"), titles)
        assertTrue("home/latest/m" !in asked.single())
    }

    @Test fun customRowsNothingChangedForAreKept() = withContainer { c ->
        val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
        val page = dev.nitrate.core.pages.PageKeys.custom("mine")
        val entry = dev.nitrate.core.pages.RowEntry("e1", dev.nitrate.core.pages.RowSource.Playlist("pl-1", "Marathon"))
        runBlocking { c.displaySettings.saveLayout(server.userId!!, page, dev.nitrate.core.pages.PageLayout(listOf(entry))) }
        // Not kept from before: read, but only the row's first page.
        val first = RowsViewModel(page, { emptyList() }, c.displaySettings, server, cache = c.dataCache)
        assertEquals(listOf("Marathon"), settle(first) { "Marathon" in it })
        assertEquals(listOf(24), playlistReads)
        // Kept with the counters it was read under; watched since (no playlist changed): kept, marks read afresh.
        val saved = runBlocking { c.dataCache.read(account, page) }!!
        runBlocking { c.dataCache.write(account, page, saved.copy(customStamps = saved.customStamps.mapValues { it.value.replace(t2, t1) })) }
        playlistReads.clear()
        val again = RowsViewModel(page, { emptyList() }, c.displaySettings, server, cache = c.dataCache)
        assertEquals(listOf("Marathon (marks)"), settle(again) { "Marathon (marks)" in it })
        assertEquals(emptyList<Int>(), playlistReads)
    }
}
