package dev.nitrate.mobile

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NewEpisodeNotifierTest {
    private fun episode(id: String, series: String, n: Int, added: Long) =
        MediaItem(id, "Episode $n", ItemKind.EPISODE, seriesId = series, seriesName = "The Night Reel", seasonNumber = 2, indexNumber = n, dateAddedMs = added)

    @Test
    fun `posts once per followed show, after the first check`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val server = FakeServer().apply { recent = listOf(episode("e4", "series-1", 4, 1_000)) }
        val notifier = NewEpisodeNotifier(context, server)
        notifier.setEnabled(true)
        val manager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

        // First look: notes what's there, says nothing.
        notifier.check()
        assertEquals(0, manager.allNotifications.size)

        // Two new episodes of a followed show, one of a show not followed.
        server.recent = listOf(episode("e6", "series-1", 6, 3_000), episode("e5", "series-1", 5, 2_000), episode("x1", "other", 1, 2_500)) + server.recent
        notifier.check()
        val posted = manager.allNotifications.single()
        assertEquals("2 new episodes of The Night Reel", posted.extras.getString("android.title"))
        assertEquals("S2E5 · Episode 5, S2E6 · Episode 6", posted.extras.getCharSequence("android.text").toString())

        // Nothing new: nothing more.
        notifier.check()
        assertEquals(1, manager.allNotifications.size)
        notifier.setEnabled(false)
    }
}
