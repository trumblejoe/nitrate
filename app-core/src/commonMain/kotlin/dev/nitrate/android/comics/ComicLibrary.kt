package dev.nitrate.android.comics

import dev.nitrate.core.MediaServer
import dev.nitrate.core.comics.ComicInfo
import dev.nitrate.core.comics.ComicPages
import dev.nitrate.core.comics.ComicProgress
import dev.nitrate.core.model.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** A comic that can't be opened here, with why in words. */
class ComicException(message: String) : Exception(message)

/** An open comic: its pages in order and its ComicInfo, each page taken out of the archive once. */
class ComicBook internal constructor(
    val item: MediaItem,
    /** The archive's page entries, in reading order. */
    val pages: List<String>,
    val info: ComicInfo?,
    private val zip: FileSystem,
    private val dir: Path,
) {
    val pageCount get() = pages.size

    /** Where the user left off (0 when finished or never opened). */
    val startPage: Int get() = if (item.user.played) 0 else ComicProgress.page(item.user.positionMs, pageCount)

    /** The local file of page [index], taken out of the archive the first time it's asked for. */
    suspend fun page(index: Int): String = withContext(Dispatchers.IO) {
        val entry = pages[index]
        val ext = entry.substringAfterLast('.', "img").lowercase()
        val out = dir / "page-$index.$ext"
        if (!FileSystem.SYSTEM.exists(out)) {
            val tmp = dir / "page-$index.part"
            zip.source(entry.toPath()).use { src -> FileSystem.SYSTEM.sink(tmp).buffer().use { it.writeAll(src) } }
            FileSystem.SYSTEM.atomicMove(tmp, out)
        }
        out.toString()
    }
}

/**
 * Comics read from the server, kept on the device: a book's file is fetched once (in one go, so
 * the server's drive is busy once per book, not once per page) into a cache that keeps the books
 * opened most recently within [maxBytes], and its pages are taken out of it as they're read.
 * Reading position goes to Jellyfin as the book's user data ([ComicProgress]).
 */
class ComicLibrary(
    private val server: MediaServer,
    cacheDir: String,
    private val engine: HttpClientEngine,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Long,
) {
    private val root = "$cacheDir/comics".toPath()
    private val http by lazy { HttpClient(engine) }
    private val fs = FileSystem.SYSTEM

    /**
     * Opens [item]: from the device when it's there, else fetched from the server first
     * ([onProgress] 0–1 while that happens). CBZ only for now; a CBR or PDF says what it needs.
     */
    suspend fun open(item: MediaItem, onProgress: (Float) -> Unit = {}): ComicBook = withContext(Dispatchers.IO) {
        val dir = root / item.id
        val file = dir / "book"
        fs.createDirectories(dir)
        if (!fs.exists(file)) fetch(server.fileUrl(item.id), dir, file, onProgress)
        // When it was last opened, for keeping the most recent ones.
        fs.write(dir / "opened") { writeUtf8(now().toString()) }
        when (kind(file)) {
            Kind.ZIP -> Unit
            Kind.RAR -> throw ComicException("This comic is a CBR (RAR) file. Nitrate opens CBZ files for now; CBR comes with the Nitrate Companion plugin's page streaming, or convert it to CBZ (CLU can convert a whole library).")
            Kind.PDF -> throw ComicException("PDF comics open in a later update.")
            Kind.OTHER -> throw ComicException("This file isn't a comic archive Nitrate can open.")
        }
        val zip = fs.openZip(file)
        val entries = zip.listRecursively("/".toPath()).filter { zip.metadataOrNull(it)?.isRegularFile == true }.map { it.toString() }.toList()
        val pages = ComicPages.order(entries)
        if (pages.isEmpty()) throw ComicException("There are no pages in this comic's file.")
        val info = entries.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
            ?.let { e -> runCatching { ComicInfo.parse(zip.read(e.toPath()) { readUtf8() }) }.getOrNull() }
        prune(keep = item.id)
        ComicBook(item, pages, info, zip, dir)
    }

    /** Saves where the user is: the page, and read once they reach the last one (a finished book stays read). */
    suspend fun saveProgress(book: ComicBook, page: Int) {
        val finished = ComicProgress.finished(page, book.pageCount)
        runCatching { server.saveUserData(book.item.id, if (finished) 0 else ComicProgress.positionMs(page), finished || book.item.user.played, now()) }
    }

    /** How much the kept comics take. */
    fun cachedBytes(): Long = if (!fs.exists(root)) 0 else fs.listRecursively(root).sumOf { fs.metadataOrNull(it)?.size ?: 0 }

    /** Forgets every kept comic. */
    fun clear() {
        if (fs.exists(root)) fs.deleteRecursively(root)
    }

    private suspend fun fetch(url: String, dir: Path, file: Path, onProgress: (Float) -> Unit) {
        val part = dir / "book.part"
        http.prepareGet(url) { header(HttpHeaders.Authorization, server.authorizationHeader()) }.execute { response ->
            if (!response.status.isSuccess()) throw ComicException("The server couldn't send this comic (${response.status.value}).")
            val total = response.contentLength()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            fs.sink(part).buffer().use { sink ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    sink.write(buffer, 0, n)
                    written += n
                    if (total != null && total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        fs.atomicMove(part, file)
    }

    private enum class Kind { ZIP, RAR, PDF, OTHER }

    private fun kind(file: Path): Kind {
        val head = fs.read(file) { readByteArray(minOf(4L, fs.metadata(file).size ?: 0)) }
        return when {
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Kind.ZIP
            head.decodeToString() == "Rar!" -> Kind.RAR
            head.decodeToString() == "%PDF" -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    /** Keeps the kept comics within [maxBytes]: the ones opened longest ago go first ([keep] never). */
    private fun prune(keep: String) {
        if (!fs.exists(root)) return
        val books = fs.list(root).filter { fs.metadataOrNull(it)?.isDirectory == true }.map { dir ->
            val size = fs.listRecursively(dir).sumOf { fs.metadataOrNull(it)?.size ?: 0 }
            val opened = runCatching { fs.read(dir / "opened") { readUtf8().trim().toLong() } }.getOrDefault(0L)
            Triple(dir, size, opened)
        }
        var total = books.sumOf { it.second }
        for ((dir, size, _) in books.sortedBy { it.third }) {
            if (total <= maxBytes) break
            if (dir.name == keep) continue
            fs.deleteRecursively(dir)
            total -= size
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 500L * 1024 * 1024
    }
}
