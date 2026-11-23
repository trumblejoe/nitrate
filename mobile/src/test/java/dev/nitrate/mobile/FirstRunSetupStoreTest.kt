package dev.nitrate.mobile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.nitrate.android.backup.FirstRunSetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FirstRunSetupStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = FirstRunSetupStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { folder.root.resolve("backup.preferences_pb") },
    )

    @Test
    fun aFirstSignInShowsTheSetupAndFinishingItKeepsItAway() = runBlocking {
        val s = store()
        s.onSignedIn(accountSettledBefore = false)
        assertTrue(s.active.value)
        s.finish()
        assertFalse(s.active.value)
        assertTrue(s.isDone())
        // Signing in again (another account, a restart): never again by itself.
        s.onSignedIn(accountSettledBefore = false)
        assertFalse(s.active.value)
    }

    @Test
    fun anInstallThatAnsweredTheBackupOfferNeverSeesIt() = runBlocking {
        val s = store()
        s.onSignedIn(accountSettledBefore = true)
        assertFalse(s.active.value)
        assertTrue(s.isDone())
    }

    @Test
    fun settingsRunsItAgain() = runBlocking {
        val s = store()
        s.onSignedIn(accountSettledBefore = true)
        s.rerun()
        assertTrue(s.active.value)
        s.finish()
        assertFalse(s.active.value)
    }
}
