package dev.nitrate.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.pages.DeviceKind
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.DisplayProfiles
import dev.nitrate.core.pages.TvMenuStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DisplayProfilesStoreTest {
    private val user = "profiles-user"
    private fun store() = DisplaySettingsStore(ApplicationProvider.getApplicationContext<Context>(), DeviceKind.TV, "Living room TV")
        .also { runBlocking { it.import(user, emptyMap()); it.useProfile(user, "tv") } }

    @Test
    fun aChangeOnABuiltInProfileStartsANamedOneAndNeverChangesTheDefault() = runBlocking {
        val s = store()
        assertEquals("tv", s.activeProfile(user).first())
        s.savePrefs(user, DisplayPrefs(tvMenu = TvMenuStyle.RADIAL))
        val active = s.activeProfile(user).first()
        assertFalse(DisplayProfiles.isBuiltIn(active))
        assertEquals(listOf("Living room TV"), s.profiles(user).first().map { it.name })
        assertTrue(s.export(user).keys.none { it.startsWith("p:tv:") })
        // Back on the built-in TV profile: still the defaults.
        s.useProfile(user, "tv")
        assertEquals(TvMenuStyle.LEFT, s.prefs(user).first().tvMenu)
        s.useProfile(user, active)
        assertEquals(TvMenuStyle.RADIAL, s.prefs(user).first().tvMenu)
    }

    @Test
    fun spoilerHidingIsTheSameInEveryProfileAndDoesNotStartOne() = runBlocking {
        val s = store()
        s.savePrefs(user, DisplayPrefs(hideSpoilers = true))
        assertEquals("tv", s.activeProfile(user).first())
        val other = s.createProfile(user, "Bedroom", from = "phone")
        assertEquals(other, s.activeProfile(user).first())
        assertTrue(s.prefs(user).first().hideSpoilers)
    }

    @Test
    fun newProfileCopiesItsStartingPointAndDeletingFallsBack() = runBlocking {
        val s = store()
        val a = s.createProfile(user, "Lounge", from = "tv")
        s.savePrefs(user, DisplayPrefs(tvMenu = TvMenuStyle.TOP))
        val b = s.createProfile(user, "Lounge copy", from = a)
        assertEquals(TvMenuStyle.TOP, s.prefs(user).first().tvMenu)
        s.deleteProfile(user, b)
        assertEquals("tv", s.activeProfile(user).first())
        assertEquals(listOf("Lounge"), s.profiles(user).first().map { it.name })
    }

    @Test
    fun oldSettingsBecomeMySettings() = runBlocking {
        val s = store()
        s.import(user, mapOf("prefs" to """{"hideSpoilers":true,"tvMenu":"TOP"}""", "pages" to "[]"))
        s.useProfile(user, "tv")
        assertTrue(s.migrateLocal(user))
        val keys = s.export(user).keys
        assertTrue("p:migrated:prefs" in keys && "pages" in keys && "shared" in keys)
        // A device that hasn't chosen yet uses My settings.
        val fresh = DisplaySettingsStore(ApplicationProvider.getApplicationContext<Context>(), DeviceKind.PHONE, "Pixel")
        assertEquals("tv", fresh.activeProfile(user).first()) // this device chose TV above
        assertTrue(s.prefs(user).first().hideSpoilers)
    }
}
