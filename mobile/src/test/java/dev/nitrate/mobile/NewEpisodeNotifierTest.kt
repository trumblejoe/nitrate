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
        val notifier = dev.nitrate.android.notifications.AndroidNewEpisodeNotifier(context, server)
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

    @Test
    fun `a watch party reminder takes one look and says the time as it stands`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val party = dev.nitrate.core.partners.WatchParty("g2", now + 10 * 60_000, now + 15 * 60_000, partLabel = "S1:E4", offsetMinutes = 0)
        val server = FakeServer().apply { watchPartyState = party }
        val notifier = dev.nitrate.android.notifications.AndroidNewEpisodeNotifier(context, server)
        val manager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()

        notifier.remindParty("g2", "The Night Reel", "Tommy & Suzy")
        val posted = manager.allNotifications.single()
        assertEquals("The Night Reel", posted.extras.getString("android.title"))
        assertEquals("Group Watch Session with Tommy & Suzy · ${party.clock} · S1:E4", posted.extras.getCharSequence("android.text").toString())
        assertEquals(listOf("Join", "+15 min", "Not tonight"), posted.actions.map { it.title.toString() })

        // Pushed back an hour since the wake-up was set, or off tonight: nothing now.
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        server.watchPartyState = party.copy(startsAtMs = now + 75 * 60_000)
        notifier.remindParty("g2", "The Night Reel", "Tommy & Suzy")
        server.watchPartyState = party.copy(skipped = true)
        notifier.remindParty("g2", "The Night Reel", "Tommy & Suzy")
        assertEquals(0, manager.allNotifications.size)

        // Switched off: no look at all.
        notifier.setPartyRemindersEnabled(false)
        server.watchPartyState = party
        notifier.remindParty("g2", "The Night Reel", "Tommy & Suzy")
        assertEquals(0, manager.allNotifications.size)
        notifier.setPartyRemindersEnabled(true)
    }

    @Test
    fun `a schedule change to answer is posted once with Yes and No, never for your own`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        system.cancelAll()
        val manager = shadowOf(system)
        val change = dev.nitrate.core.partners.ScheduleChange(
            "c1", "Sam", dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY), listOf("Sam"), emptyList(), voted = false, atMs = 0,
        )
        val group = dev.nitrate.core.partners.WatchGroup("g1", "s1", "The Night Reel", listOf(dev.nitrate.core.writing.UserRef("u2", "Sam")), 0, pending = change)
        val server = FakeServer().apply {
            partnerInfo = dev.nitrate.core.partners.PartnersInfo(listOf(group), emptyList(), emptyList())
        }
        val notifier = dev.nitrate.android.notifications.AndroidNewEpisodeNotifier(context, server)
        notifier.setPartnerRequestsEnabled(true)

        notifier.checkInvites()
        val posted = manager.allNotifications.single()
        assertEquals("Change the schedule?", posted.extras.getString("android.title"))
        assertEquals("Sam asks to change the schedule of The Night Reel to Thursdays 7pm. Agree?", posted.extras.getCharSequence("android.text").toString())
        assertEquals(listOf("Yes", "No"), posted.actions.map { it.title.toString() })
        assertEquals("partner_requests", posted.channelId)

        // Once per change: a later check says nothing more.
        system.cancelAll()
        notifier.checkInvites()
        assertEquals(0, manager.allNotifications.size)

        // The user's own ask (the asker counts as answered), or one already answered: nothing.
        server.partnerInfo = dev.nitrate.core.partners.PartnersInfo(listOf(group.copy(pending = change.copy(id = "c2", voted = true))), emptyList(), emptyList())
        notifier.checkInvites()
        assertEquals(0, manager.allNotifications.size)

        // Switched off: nothing either.
        notifier.setPartnerRequestsEnabled(false)
        server.partnerInfo = dev.nitrate.core.partners.PartnersInfo(listOf(group.copy(pending = change.copy(id = "c3"))), emptyList(), emptyList())
        notifier.checkInvites()
        assertEquals(0, manager.allNotifications.size)
        notifier.setPartnerRequestsEnabled(true)
    }
}
