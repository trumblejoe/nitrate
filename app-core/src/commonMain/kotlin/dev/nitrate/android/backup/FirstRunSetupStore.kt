package dev.nitrate.android.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The first-run setup (core `setup/FirstRunSetup`): whether this device finished it (device-only,
 * kept beside the backup state, never synced or backed up) and whether it's showing now. While it
 * shows, the separate prompts it replaces (backup offer, speakers, update offers) wait.
 */
class FirstRunSetupStore(private val store: DataStore<Preferences>) {
    private val doneKey = booleanPreferencesKey("first_run_setup_done")
    private val _active = MutableStateFlow(false)

    /** The setup is showing (or about to). */
    val active: StateFlow<Boolean> = _active

    suspend fun isDone(): Boolean = store.data.first()[doneKey] == true

    /**
     * After a sign-in: starts the setup when it applies (core `FirstRunSetup.shouldRun`). A device that
     * was set up for this account before the setup existed is marked done without seeing it.
     */
    suspend fun onSignedIn(accountSettledBefore: Boolean) {
        val done = isDone()
        if (!done && accountSettledBefore) markDone()
        if (dev.nitrate.core.setup.FirstRunSetup.shouldRun(done, accountSettledBefore)) _active.value = true
    }

    /** Settings → "Set up this device again". */
    fun rerun() { _active.value = true }

    /** Finished or left: the other prompts may show again. */
    suspend fun finish() {
        markDone()
        _active.value = false
    }

    private suspend fun markDone() { store.edit { it[doneKey] = true } }
}
