package dev.nitrate.core.cache

/** Somewhere on the device to keep small text (JSON) between app runs; may forget anything. */
interface TextCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String) {}
}
