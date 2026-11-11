package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.profiles by preferencesDataStore(name = "profiles")

/**
 * How accounts are picked on this device: PINs that lock a profile, and whether to ask who's
 * watching when the app opens. Device-only (a PIN guards this device, not the account), never synced.
 */
class ProfileStore(private val context: Context) {
    private fun pinKey(account: String) = stringPreferencesKey("pin:$account")
    private val askKey = booleanPreferencesKey("ask_at_start")

    /** Accounts (by key) that need a PIN. */
    val locked: Flow<Set<String>> = context.profiles.data.map { prefs ->
        prefs.asMap().keys.map { it.name }.filter { it.startsWith("pin:") }.map { it.removePrefix("pin:") }.toSet()
    }

    val askAtStart: Flow<Boolean> = context.profiles.data.map { it[askKey] ?: false }

    suspend fun setAskAtStart(ask: Boolean) {
        context.profiles.edit { it[askKey] = ask }
    }

    suspend fun hasPin(account: String): Boolean = context.profiles.data.first()[pinKey(account)] != null

    suspend fun check(account: String, pin: String): Boolean {
        val stored = context.profiles.data.first()[pinKey(account)] ?: return true
        return stored == hash(account, pin)
    }

    /** Sets, changes or (with null) removes [account]'s PIN. */
    suspend fun setPin(account: String, pin: String?) {
        context.profiles.edit { prefs ->
            if (pin == null) prefs.remove(pinKey(account)) else prefs[pinKey(account)] = hash(account, pin)
        }
    }

    suspend fun forget(account: String) = setPin(account, null)

    private fun hash(account: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("nitrate-pin|$account|$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** 4 to 8 digits. */
        fun isValid(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)
    }
}
