package dev.nitrate.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.storage.DataCache
import dev.nitrate.android.viewmodel.DetailsViewModel
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A details page opens with what it showed last time while the server is still answering. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DetailsCacheTest {
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun cachedDetailsShowUntilFreshOnesArrive() = runBlocking {
        val cache = DataCache(ApplicationProvider.getApplicationContext<Context>())
        val fake = FakeServer()
        val gate = CompletableDeferred<Unit>()
        val server = object : MediaServer by fake {
            override suspend fun getItem(id: String): MediaItem { gate.await(); return MediaItem(id, "Fresh title", ItemKind.MOVIE) }
            override suspend fun similar(itemId: String) = emptyList<MediaItem>()
        }
        val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
        cache.write(account, "details/m9", dev.nitrate.android.viewmodel.DetailsState(item = MediaItem("m9", "Cached title", ItemKind.MOVIE)), dev.nitrate.android.viewmodel.DetailsState.serializer())

        val vm = DetailsViewModel(server, "m9", null, cache)
        withTimeout(5_000) { vm.state.first { it.item != null } }
        assertEquals("Cached title", vm.state.value.item?.name)

        gate.complete(Unit)
        withTimeout(5_000) { vm.state.first { it.item?.name == "Fresh title" } }
        // And the fresh page is what's remembered next time.
        withTimeout(5_000) {
            while (cache.read(account, "details/m9", dev.nitrate.android.viewmodel.DetailsState.serializer())?.item?.name != "Fresh title") kotlinx.coroutines.delay(20)
        }
    }
}
