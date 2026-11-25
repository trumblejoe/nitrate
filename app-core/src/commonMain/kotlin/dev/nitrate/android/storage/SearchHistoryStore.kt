package dev.nitrate.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Recent searches on this device, newest first, one list per account ([account]: the account
 * signed in now, null while none is), so switching accounts never shows another's searches.
 * The list from before it was kept per account (whose it was can't be told) is dropped.
 */
class SearchHistoryStore(private val store: DataStore<Preferences>, private val account: Flow<String?>) {
    private val shared = stringPreferencesKey("recent")

    private fun keyFor(account: String) = stringPreferencesKey("recent/$account")

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: Flow<List<String>> = account.distinctUntilChanged().flatMapLatest { a ->
        if (a == null) flowOf(emptyList())
        else store.data.map { prefs -> prefs[keyFor(a)]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty() }
    }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val a = account.first() ?: return
        store.edit { prefs ->
            prefs.remove(shared)
            val key = keyFor(a)
            val list = prefs[key]?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()
            prefs[key] = (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX).joinToString(SEPARATOR)
        }
    }

    /** Clears the signed-in account's list. */
    suspend fun clear() {
        val a = account.first() ?: return
        store.edit { it.remove(keyFor(a)); it.remove(shared) }
    }

    /** Forgets [account]'s list (signing out of it, or forgetting it on this device). */
    suspend fun forget(account: String) {
        store.edit { it.remove(keyFor(account)); it.remove(shared) }
    }

    private companion object {
        const val SEPARATOR = "\u001f"
        const val MAX = 12
    }
}
