package dev.nitrate.android.comics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nitrate.core.catalogs.CatalogClient
import dev.nitrate.core.catalogs.ComicCatalog
import dev.nitrate.core.catalogs.catalogClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import okio.Path.Companion.toPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The comic catalogs added on this device (OPDS, CLU) and where the user is in their books. Kept
 * on the device only: never synced or in device backups, since a catalog can hold a password or
 * token (stored beside the plugin settings, under keys backups don't read).
 */
class CatalogStore(private val store: DataStore<Preferences>, engine: HttpClientEngine) : CatalogPositions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val catalogsKey = stringPreferencesKey("comic_catalogs")
    private val positionsKey = stringPreferencesKey("comic_positions")
    private val list = ListSerializer(ComicCatalog.serializer())
    private val map = MapSerializer(String.serializer(), Int.serializer())
    private val http by lazy { HttpClient(engine) { expectSuccess = false } }
    private val clients = mutableMapOf<ComicCatalog, CatalogClient>()

    val catalogs: Flow<List<ComicCatalog>> = store.data.map { p -> p[catalogsKey]?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty() }

    suspend fun catalog(id: String): ComicCatalog? = catalogs.first().firstOrNull { it.id == id }

    /** Adds [catalog], or replaces the one with its id. */
    suspend fun save(catalog: ComicCatalog) = store.edit { p ->
        val current = p[catalogsKey]?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()
        val next = if (current.any { it.id == catalog.id }) current.map { if (it.id == catalog.id) catalog else it } else current + catalog
        p[catalogsKey] = json.encodeToString(list, next)
    }

    /** Forgets [id] and where the user was in its books. */
    suspend fun remove(id: String) = store.edit { p ->
        val current = p[catalogsKey]?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()
        p[catalogsKey] = json.encodeToString(list, current.filter { it.id != id })
        val positions = p[positionsKey]?.let { runCatching { json.decodeFromString(map, it) }.getOrNull() }.orEmpty()
        p[positionsKey] = json.encodeToString(map, positions.filterKeys { !it.startsWith("$id|") })
    }

    /** The client for [catalog] (kept while its settings stay the same, so a found search address is reused). */
    fun client(catalog: ComicCatalog): CatalogClient = clients.getOrPut(catalog) { catalogClient(catalog, http) }

    override suspend fun get(catalogId: String, bookId: String): Int? =
        store.data.first()[positionsKey]?.let { runCatching { json.decodeFromString(map, it) }.getOrNull() }?.get("$catalogId|$bookId")

    override suspend fun set(catalogId: String, bookId: String, page: Int) {
        store.edit { p ->
            val positions = p[positionsKey]?.let { runCatching { json.decodeFromString(map, it) }.getOrNull() }.orEmpty()
            // The most recent 2,000 books are remembered.
            val next = (positions - "$catalogId|$bookId") + ("$catalogId|$bookId" to page)
            p[positionsKey] = json.encodeToString(map, if (next.size > 2_000) next.entries.drop(next.size - 2_000).associate { it.key to it.value } else next)
        }
    }

    /** A cover or picture from a catalog, fetched with its sign-in details into [cacheDir]; the local file's path. */
    suspend fun image(catalog: ComicCatalog, url: String, cacheDir: String): String? = runCatching {
        val fs = okio.FileSystem.SYSTEM
        val dir = "$cacheDir/catalog-images".toPath()
        fs.createDirectories(dir)
        val file = dir / (url.hashCode().toUInt().toString(16) + "-" + url.length)
        if (!fs.exists(file)) {
            val auth = client(catalog).authorization
            val response = http.get(url) { auth?.let { header(io.ktor.http.HttpHeaders.Authorization, it) } }
            if (!response.status.isSuccess()) return@runCatching null
            val bytes = response.readRawBytes()
            fs.write(dir / (file.name + ".part")) { write(bytes) }
            fs.atomicMove(dir / (file.name + ".part"), file)
        }
        file.toString()
    }.getOrNull()
}
