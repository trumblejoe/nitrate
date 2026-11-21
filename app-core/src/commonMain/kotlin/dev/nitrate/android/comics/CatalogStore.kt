package dev.nitrate.android.comics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nitrate.core.MediaServer
import dev.nitrate.core.catalogs.CatalogClient
import dev.nitrate.core.catalogs.ComicCatalog
import dev.nitrate.core.catalogs.catalogClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import okio.Path.Companion.toPath

/**
 * The comic catalogs (OPDS, CLU) the server's admin set up in the Companion plugin, reached
 * through the server: it signs in to them, so nothing about a catalog's sign-in is on the device,
 * and it keeps each person's place in the books ([dev.nitrate.core.catalogs.CatalogLink]).
 */
class CatalogStore(private val server: MediaServer, private val oldStore: DataStore<Preferences>, engine: HttpClientEngine) {
    private val http by lazy { HttpClient(engine) { expectSuccess = false } }
    private val clients = mutableMapOf<ComicCatalog, CatalogClient>()
    private var forgotten = false
    private var known = emptyList<ComicCatalog>()

    /** The catalogs the server offers; empty when it has none (or no Companion plugin). */
    suspend fun catalogs(): List<ComicCatalog> {
        forgetDeviceCatalogs()
        return server.comicCatalogs().also { known = it }
    }

    /** [id]'s catalog: as last listed, else asked for again. */
    suspend fun catalog(id: String): ComicCatalog? = known.firstOrNull { it.id == id } ?: catalogs().firstOrNull { it.id == id }

    /** The client for [catalog] (kept while it stays the same, so a found search address is reused). */
    fun client(catalog: ComicCatalog): CatalogClient = clients.getOrPut(catalog) { catalogClient(server.catalogLink(catalog)) }

    /** A cover or picture from a catalog, fetched through the server into [cacheDir]; the local file's path. */
    suspend fun image(catalog: ComicCatalog, url: String, cacheDir: String): String? = runCatching {
        val fs = okio.FileSystem.SYSTEM
        val dir = "$cacheDir/catalog-images".toPath()
        fs.createDirectories(dir)
        val key = catalog.id + "\n" + url
        val file = dir / (key.hashCode().toUInt().toString(16) + "-" + key.length)
        if (!fs.exists(file)) {
            val link = client(catalog).link
            val response = http.get(link.fetchUrl(url)) { header(HttpHeaders.Authorization, link.authorization) }
            if (!response.status.isSuccess()) return@runCatching null
            val bytes = response.readRawBytes()
            fs.write(dir / (file.name + ".part")) { write(bytes) }
            fs.atomicMove(dir / (file.name + ".part"), file)
        }
        file.toString()
    }.getOrNull()

    /**
     * Catalogs used to be added on each device, with their passwords: those are dropped once,
     * now that the server holds them.
     */
    private suspend fun forgetDeviceCatalogs() {
        if (forgotten) return
        forgotten = true
        runCatching {
            oldStore.edit { p ->
                p.remove(stringPreferencesKey("comic_catalogs"))
                p.remove(stringPreferencesKey("comic_positions"))
            }
        }
    }
}
