package dev.nitrate.mobile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nitrate.android.storage.SearchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recent searches belong to the account that made them: switching accounts never shows another's. */
class SearchHistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val data by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { folder.root.resolve("search.preferences_pb") }
    }

    @Test
    fun eachAccountHasItsOwnRecentSearches() = runBlocking {
        val account = MutableStateFlow<String?>("server|sam")
        val history = SearchHistoryStore(data, account)
        history.add("hunger games")
        assertEquals(listOf("hunger games"), history.recent.first())
        // Another account on the same device sees nothing of Sam's.
        account.value = "server|alex"
        assertEquals(emptyList<String>(), history.recent.first())
        history.add("batman")
        assertEquals(listOf("batman"), history.recent.first())
        // Back to Sam: Sam's own list, without Alex's.
        account.value = "server|sam"
        assertEquals(listOf("hunger games"), history.recent.first())
        // Signed out (no account): nothing shown.
        account.value = null
        assertEquals(emptyList<String>(), history.recent.first())
        // Signing out of Alex forgets Alex's list.
        history.forget("server|alex")
        account.value = "server|alex"
        assertEquals(emptyList<String>(), history.recent.first())
    }

    @Test
    fun theOldSharedListIsShownToNobody() = runBlocking {
        data.edit { it[stringPreferencesKey("recent")] = "old search" }
        val history = SearchHistoryStore(data, MutableStateFlow("server|sam"))
        assertEquals(emptyList<String>(), history.recent.first())
    }
}
