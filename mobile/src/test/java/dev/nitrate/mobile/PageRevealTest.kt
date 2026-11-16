package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.AppContainer
import dev.nitrate.android.storage.CachedPage
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowsProgress
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Home doesn't pop rows in one by one: it changes once at the end, or once more if that's slow. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PageRevealTest {
    private fun row(key: String, title: String = key) = MediaRow(key, title, listOf(MediaItem("$key-1", "$title film", ItemKind.MOVIE)), RowDisplay())

    /** Rows answering at 0.1 s, 0.5 s and 2.5 s (then complete), with fresh titles. */
    private val slowHome = {
        flow {
            delay(100); emit(RowsProgress(listOf(row("a", "A new")), false))
            delay(400); emit(RowsProgress(listOf(row("a", "A new"), row("b", "B new")), false))
            delay(2_000); emit(RowsProgress(listOf(row("a", "A new"), row("b", "B new"), row("c", "C new")), false))
            delay(2_000); emit(RowsProgress(listOf(row("a", "A new"), row("b", "B new"), row("c", "C new"), row("d", "D new")), true))
        }
    }

    private fun titlesAt(vm: RowsViewModel, ms: Long): List<String> {
        // In small steps, letting the cache's disk read (off the main thread) land.
        repeat((ms / 50).toInt()) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS); Thread.sleep(5) }
        return (vm.state.value as? RowsState.Loaded)?.rows?.map { it.title } ?: listOf("loading")
    }

    private fun withContainer(block: (AppContainer) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val c = AppContainer(activity, "test", "0", serverOverride = FakeServer())
            runBlocking { c.displaySettings.import("user-1", emptyMap()) }
            block(c)
        }
    }

    @Test fun firstLoadGathersThenFills() = withContainer { c ->
        val vm = RowsViewModel(PageKeys.HOME, { emptyList() }, c.displaySettings, c.server, loadProgress = slowHome)
        // Two rows answer early, but the page waits to show them together.
        assertEquals(listOf("loading"), titlesAt(vm, 1_000))
        assertEquals(listOf("A new", "B new"), titlesAt(vm, 600))
        // C answers at 2.5 s: held for the end rather than popping in.
        assertEquals(listOf("A new", "B new"), titlesAt(vm, 1_000))
        assertEquals(listOf("A new", "B new", "C new", "D new"), titlesAt(vm, 2_000))
    }

    @Test fun lastTimesPageStaysUntilItCanChangeQuietly() = withContainer { c ->
        val account = c.server.currentAccountKey ?: "${c.server.serverUrl}|${c.server.userId}"
        runBlocking { c.dataCache.write(account, PageKeys.HOME, CachedPage(listOf(row("a"), row("b"), row("c")))) }
        val vm = RowsViewModel(PageKeys.HOME, { emptyList() }, c.displaySettings, c.server, cache = c.dataCache, loadProgress = slowHome)
        // Last time's page, read from the disk without the clock moving; the load starts after it.
        val end = System.currentTimeMillis() + 5_000
        while (vm.state.value !is RowsState.Loaded && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        // It stays untouched while rows answer.
        assertEquals(listOf("a", "b", "c"), titlesAt(vm, 2_000))
        // At 3 s the rows that answered change in place; nothing is added or moved.
        assertEquals(listOf("A new", "B new", "C new"), titlesAt(vm, 1_200))
        // The rest at the end, in one go.
        assertEquals(listOf("A new", "B new", "C new", "D new"), titlesAt(vm, 2_000))
    }
}
