package dev.nitrate.android.storage

/** Small strings read at once (no waiting), such as last time's library list. */
interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
