package dev.nitrate.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.ByteString.Companion.encodeUtf8


/**
 * How accounts are picked on this device: PINs that lock a profile, and whether to ask who's
 * watching when the app opens. Device-only (a PIN guards this device, not the account), never synced.
 */
class ProfileStore(private val store: DataStore<Preferences>) {
    private fun pinKey(account: String) = stringPreferencesKey("pin:$account")
    private val askKey = booleanPreferencesKey("ask_at_start")

    /** Accounts (by key) that need a PIN. */
    val locked: Flow<Set<String>> = store.data.map { prefs ->
        prefs.asMap().keys.map { it.name }.filter { it.startsWith("pin:") }.map { it.removePrefix("pin:") }.toSet()
    }

    val askAtStart: Flow<Boolean> = store.data.map { it[askKey] ?: false }

    suspend fun setAskAtStart(ask: Boolean) {
        store.edit { it[askKey] = ask }
    }

    suspend fun hasPin(account: String): Boolean = store.data.first()[pinKey(account)] != null

    suspend fun check(account: String, pin: String): Boolean {
        val stored = store.data.first()[pinKey(account)] ?: return true
        return stored == hash(account, pin)
    }

    /** Sets, changes or (with null) removes [account]'s PIN. */
    suspend fun setPin(account: String, pin: String?) {
        store.edit { prefs ->
            if (pin == null) prefs.remove(pinKey(account)) else prefs[pinKey(account)] = hash(account, pin)
        }
    }

    suspend fun forget(account: String) = setPin(account, null)

    private fun hash(account: String, pin: String): String =
        "nitrate-pin|$account|$pin".encodeUtf8().sha256().hex()

    companion object {
        /** 4 to 8 digits. */
        fun isValid(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)
    }
}
