package dev.nitrate.android.comics

import dev.nitrate.core.MediaServer
import dev.nitrate.core.comics.ComicInfo
import dev.nitrate.core.comics.ComicListing
import dev.nitrate.core.comics.ComicPages
import dev.nitrate.core.comics.ComicProgress
import dev.nitrate.core.comics.ImageSize
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** A comic that can't be opened here, with why in words. */
class ComicException(message: String) : Exception(message)

/** Where a book's pages come from: written into [out] the first time each is asked for. */
fun interface PageSource {
    suspend fun fetch(index: Int, out: Path)
}

/**
 * Renders a PDF's pages (each platform's own: Android's PdfRenderer, Apple's PDFKit drawing).
 * Pages come out as pictures [widthPx] wide, so the readers treat a PDF like any comic.
 */
interface PdfPages {
    /** The document's page count and each page's size in points; null when it can't be read (locked, damaged). */
    fun open(path: String): List<Pair<Float, Float>>?

    /** Draws page [index] [widthPx] wide (on white) into [outPath] as a JPEG. */
    fun render(path: String, index: Int, widthPx: Int, outPath: String)
}

/** A book ready to read: a comic for the page reader, or an EPUB file for the book reader. */
sealed interface OpenedBook {
    class Comic(val book: ComicBook) : OpenedBook

    /**
     * An EPUB on the device at [path]: start at [locator] (the reader's own position, JSON, from
     * this device) else at [progression] (0–1, from the server); [save] keeps both as it's read.
     */
    class Epub(val path: String, val title: String, val locator: String?, val progression: Double, val save: suspend (locator: String, progression: Double) -> Unit) : OpenedBook
}

/** Opens EPUB books in the platform's book reader (Android: Readium). */
interface EpubReaderHost {
    /** Shows [book]; returns once the reader is on screen (it saves as the user reads). */
    suspend fun open(book: OpenedBook.Epub)
}

/** Where the user is in catalog books, kept on the device (by catalog and book). */
interface CatalogPositions {
    suspend fun get(catalogId: String, bookId: String): Int?
    suspend fun set(catalogId: String, bookId: String, page: Int)
}

/** An open comic: its pages in order and its ComicInfo, each page fetched or taken out once. */
class ComicBook internal constructor(
    /** The Jellyfin item (null for books from elsewhere, which give [title] and [saveProgress] themselves). */
    val item: MediaItem?,
    val title: String,
    /** Each page's name, in reading order (its extension says what kind of picture it is). */
    val pages: List<String>,
    val info: ComicInfo?,
    /** Pages that are spreads on one picture: shown alone in two-page view. */
    val widePages: Set<Int>,
    /** Where to start (0 when finished or never opened). */
    val startPage: Int,
    private val source: PageSource,
    private val dir: Path,
    private val onProgress: suspend (page: Int, finished: Boolean) -> Unit,
) {
    val pageCount get() = pages.size
    private val locks = List(pages.size) { Mutex() }

    /** The local file of page [index], fetched (or taken out) the first time it's asked for. */
    suspend fun page(index: Int): String = withContext(Dispatchers.IO) {
        val out = file(index)
        if (!FileSystem.SYSTEM.exists(out)) locks[index].withLock {
            if (!FileSystem.SYSTEM.exists(out)) {
                val tmp = dir / "page-$index.part"
                source.fetch(index, tmp)
                FileSystem.SYSTEM.atomicMove(tmp, out)
            }
        }
        out.toString()
    }

    /** True when page [index] is already on the device. */
    fun hasPage(index: Int) = FileSystem.SYSTEM.exists(file(index))

    /**
     * Fetches every page not yet here, from [from] on and then the ones before it, two at a time:
     * the whole book in one burst, so the server's drive is busy once, not every few minutes while
     * someone reads slowly. Failures are left for the page to try again when it's shown.
     */
    suspend fun fetchAll(from: Int = 0) = coroutineScope {
        val gate = Semaphore(2)
        val order = (from.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) until pageCount) + (0 until from.coerceAtMost(pageCount))
        order.filterNot(::hasPage).map { i -> async { gate.withPermit { runCatching { page(i) } } } }.awaitAll()
        Unit
    }

    /** Saves where the reader is: the page, and read once they reach the last one. */
    suspend fun saveProgress(page: Int) {
        runCatching { onProgress(page, ComicProgress.finished(page, pageCount)) }
    }

    private fun file(index: Int): Path {
        val ext = pages[index].substringAfterLast('.', "jpg").lowercase().takeIf { it.length in 2..4 } ?: "jpg"
        return dir / "page-$index.$ext"
    }
}

/**
 * Comics read from the server, kept on the device within [maxBytes] (the books opened most
 * recently stay). With the Companion plugin a book is served page by page: the first page shows at
 * once and the rest follow in one burst ([ComicBook.fetchAll]); CBR, CB7 and CBT are unpacked on
 * the server. Without it the book's own file is fetched in one go (CBZ, or a PDF the device
 * renders). Reading position goes to Jellyfin as the book's user data ([ComicProgress]).
 */
class ComicLibrary(
    private val server: MediaServer,
    cacheDir: String,
    private val engine: HttpClientEngine,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val pdf: PdfPages? = null,
    private val now: () -> Long,
) {
    private val root = "$cacheDir/comics".toPath()
    private val http by lazy { HttpClient(engine) }
    private val fs = FileSystem.SYSTEM
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Opens [item]: page by page from the plugin when it serves the book, else from its file (on
     * the device already, or fetched first: [onProgress] 0–1 meanwhile).
     */
    suspend fun open(item: MediaItem, onProgress: (Float) -> Unit = {}): ComicBook =
        (prepare(item, onProgress) as? OpenedBook.Comic)?.book ?: throw ComicException("This book is an EPUB, read in the book reader.")

    /** Opens [item] as a comic, or fetches it for the EPUB reader when it's an EPUB. */
    suspend fun prepare(item: MediaItem, onProgress: (Float) -> Unit = {}): OpenedBook = withContext(Dispatchers.IO) {
        val dir = root / item.id
        fs.createDirectories(dir)
        // When it was last opened, for keeping the most recent ones.
        fs.write(dir / "opened") { writeUtf8(now().toString()) }
        val file = dir / "book"
        val kept = dir / "listing.json"
        // The plugin's list of pages; the one kept from last time when the server can't be reached,
        // so a book fetched in full reads offline.
        val listing = if (fs.exists(file)) null else runCatching { server.comicListing(item.id) }
            .onSuccess { l -> if (l != null) runCatching { fs.write(kept) { writeUtf8(json.encodeToString(ComicListing.serializer(), l)) } } }
            .getOrElse { runCatching { json.decodeFromString(ComicListing.serializer(), fs.read(kept) { readUtf8() }) }.getOrNull() }
        val meta = jellyfin(item)
        val book = when {
            listing != null && listing.paged -> OpenedBook.Comic(streamed(meta, listing, dir) { i -> server.comicPageUrl(item.id, i) })
            else -> {
                if (!fs.exists(file)) fetch(server.fileUrl(item.id), server.authorizationHeader(), dir, file, onProgress)
                if (kind(file) == Kind.EPUB) epub(file, dir, meta.title, epubJellyfin(item)) else OpenedBook.Comic(fromFile(meta, file, dir))
            }
        }
        prune(keep = item.id)
        book
    }

    /** Where an EPUB's reader is kept in Jellyfin: the share read as the position (1,000,000 = the end), read at the end. */
    private fun epubJellyfin(item: MediaItem) = EpubProgress(
        start = if (item.user.played) 0.0 else (item.user.positionMs / EPUB_SCALE.toDouble()).coerceIn(0.0, 1.0),
        save = { progression -> 
            val finished = progression >= EPUB_FINISHED
            server.saveUserData(item.id, if (finished) 0 else (progression * EPUB_SCALE).toLong(), finished || item.user.played, now())
        },
    )

    private class EpubProgress(val start: Double, val save: suspend (Double) -> Unit)

    private fun epub(file: Path, dir: Path, title: String, progress: EpubProgress): OpenedBook.Epub {
        val locatorFile = dir / "locator.json"
        val locator = runCatching { fs.read(locatorFile) { readUtf8() } }.getOrNull()
        return OpenedBook.Epub(file.toString(), title, locator, progress.start) { json, progression ->
            runCatching { fs.write(locatorFile) { writeUtf8(json) } }
            runCatching { progress.save(progression) }
        }
    }

    /**
     * Opens a book from a comic catalog (OPDS or CLU): page by page when the catalog streams pages,
     * else its file fetched once. Where the user is stays on the device ([positions]) and goes back
     * to the catalog when it keeps reading positions (CLU).
     */
    suspend fun openCatalog(
        client: dev.nitrate.core.catalogs.CatalogClient,
        book: dev.nitrate.core.catalogs.CatalogBook,
        positions: CatalogPositions,
        onProgress: (Float) -> Unit = {},
    ): ComicBook = (prepareCatalog(client, book, positions, onProgress) as? OpenedBook.Comic)?.book
        ?: throw ComicException("This book is an EPUB, read in the book reader.")

    /** [openCatalog], or the file for the EPUB reader when the book is an EPUB. */
    suspend fun prepareCatalog(
        client: dev.nitrate.core.catalogs.CatalogClient,
        book: dev.nitrate.core.catalogs.CatalogBook,
        positions: CatalogPositions,
        onProgress: (Float) -> Unit = {},
    ): OpenedBook = withContext(Dispatchers.IO) {
        val key = "cat-" + client.catalog.id + "-" + safeName(book.id)
        val dir = root / key
        fs.createDirectories(dir)
        fs.write(dir / "opened") { writeUtf8(now().toString()) }
        val saved = positions.get(client.catalog.id, book.id)
        val meta = Meta(
            null, book.title,
            start = { count -> (saved ?: book.lastRead ?: 0).let { if (count > 0 && it >= count - 1) 0 else it.coerceIn(0, (count - 1).coerceAtLeast(0)) } },
            save = { page, _ ->
                positions.set(client.catalog.id, book.id, page)
                runCatching { client.saveProgress(book, page, pageCountOf(dir)) }
            },
        )
        val file = dir / "book"
        val template = book.pageTemplate
        val count = book.pageCount
        val result = when {
            template != null && count != null && count > 0 -> {
                runCatching { fs.write(dir / "count") { writeUtf8(count.toString()) } }
                val link = dev.nitrate.core.opds.OpdsLink(dev.nitrate.core.opds.Opds.PSE_STREAM, template, null)
                OpenedBook.Comic(
                    ComicBook(
                        null, book.title, (0 until count).map { "page-$it.jpg" }, null, emptySet(), meta.start(count),
                        { i, out -> fetch(dev.nitrate.core.opds.Opds.pageUrl(link, i, STREAM_WIDTH_PX), client.authorization, dir, out) {} },
                        dir, meta.save,
                    ),
                )
            }
            book.fileUrl != null -> {
                if (!fs.exists(file)) fetch(book.fileUrl!!, client.authorization, dir, file, onProgress)
                if (kind(file) == Kind.EPUB) {
                    // EPUB position on the device, as thousandths of the book.
                    epub(file, dir, book.title, EpubProgress((saved ?: 0) / 1000.0) { p -> positions.set(client.catalog.id, book.id, (p * 1000).toInt()) })
                } else OpenedBook.Comic(fromFile(meta, file, dir).also { b -> runCatching { fs.write(dir / "count") { writeUtf8(b.pageCount.toString()) } } })
            }
            else -> throw ComicException("The catalog doesn't offer this book for reading.")
        }
        prune(keep = key)
        result
    }

    private fun pageCountOf(dir: Path) = runCatching { fs.read(dir / "count") { readUtf8().trim().toInt() } }.getOrDefault(0)

    private fun safeName(id: String) = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").takeLast(80) + "-" + id.hashCode().toUInt().toString(16)

    /** Saves where the user is in [book]: the page, and read once they reach the last one (a finished book stays read). */
    suspend fun saveProgress(book: ComicBook, page: Int) = book.saveProgress(page)

    /** How much the kept comics take. */
    fun cachedBytes(): Long = if (!fs.exists(root)) 0 else fs.listRecursively(root).sumOf { fs.metadataOrNull(it)?.size ?: 0 }

    /** Forgets every kept comic. */
    fun clear() {
        if (fs.exists(root)) fs.deleteRecursively(root)
    }

    /** Who a book is and where its reading position lives. */
    private class Meta(val item: MediaItem?, val title: String, val start: (count: Int) -> Int, val save: suspend (page: Int, finished: Boolean) -> Unit)

    private fun jellyfin(item: MediaItem) = Meta(
        item, item.name,
        start = { count -> if (item.user.played) 0 else ComicProgress.page(item.user.positionMs, count) },
        save = { page, finished -> server.saveUserData(item.id, if (finished) 0 else ComicProgress.positionMs(page), finished || item.user.played, now()) },
    )

    private fun streamed(meta: Meta, listing: ComicListing, dir: Path, pageUrl: (Int) -> String): ComicBook {
        val info = listing.comicInfo?.let { runCatching { ComicInfo.parse(it) }.getOrNull() }
        return ComicBook(
            meta.item, meta.title, listing.pages.map { it.name }, info, listing.widePages + info?.doublePages.orEmpty(),
            meta.start(listing.pages.size), { i, out -> fetch(pageUrl(i), server.authorizationHeader(), dir, out) {} }, dir, meta.save,
        )
    }

    private suspend fun fromFile(meta: Meta, file: Path, dir: Path): ComicBook = when (kind(file)) {
        Kind.ZIP -> {
            val zip = fs.openZip(file)
            val entries = zip.listRecursively("/".toPath()).filter { zip.metadataOrNull(it)?.isRegularFile == true }.map { it.toString() }.toList()
            val pages = ComicPages.order(entries)
            if (pages.isEmpty()) throw ComicException("There are no pages in this comic's file.")
            val info = entries.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
                ?.let { e -> runCatching { ComicInfo.parse(zip.read(e.toPath()) { readUtf8() }) }.getOrNull() }
            val wide = wideIn(dir) {
                pages.indices.filter { i -> ImageSize.isWide(zip.read(pages[i].toPath()) { ImageSize.of(readByteArray(minOf(ImageSize.READ_BYTES.toLong(), zip.metadata(pages[i].toPath()).size ?: 0))) }) }.toSet()
            }
            ComicBook(
                meta.item, info?.title?.takeIf { meta.item == null } ?: meta.title, pages, info, wide + info?.doublePages.orEmpty(), meta.start(pages.size),
                { i, out -> zip.source(pages[i].toPath()).use { src -> fs.sink(out).buffer().use { it.writeAll(src) } } },
                dir, meta.save,
            )
        }
        Kind.PDF -> {
            val renderer = pdf ?: throw ComicException("PDF comics can't be opened on this device.")
            val sizes = renderer.open(file.toString()) ?: throw ComicException("This PDF can't be opened (it may be locked with a password).")
            if (sizes.isEmpty()) throw ComicException("There are no pages in this PDF.")
            ComicBook(
                meta.item, meta.title, sizes.indices.map { "page-$it.jpg" }, null,
                sizes.indices.filter { sizes[it].first > sizes[it].second * 1.1f }.toSet(), meta.start(sizes.size),
                { i, out -> renderer.render(file.toString(), i, PDF_WIDTH_PX, out.toString()) }, dir, meta.save,
            )
        }
        Kind.EPUB -> throw ComicException("This book is an EPUB, read in the book reader.")
        Kind.RAR -> throw ComicException("This comic is a CBR (RAR) file. Install the Nitrate Companion plugin on the server to read CBR, CB7 and CBT, or convert it to CBZ (CLU can convert a whole library).")
        Kind.OTHER -> throw ComicException("This file isn't a comic Nitrate can open.")
    }

    /** Which pages are wide, worked out once per book and kept beside it. */
    private fun wideIn(dir: Path, find: () -> Set<Int>): Set<Int> {
        val kept = dir / "wide"
        runCatching { return fs.read(kept) { readUtf8() }.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet() }
        val wide = runCatching(find).getOrDefault(emptySet())
        runCatching { fs.write(kept) { writeUtf8(wide.joinToString(",")) } }
        return wide
    }

    private suspend fun fetch(url: String, auth: String?, dir: Path, file: Path, onProgress: (Float) -> Unit) {
        val part = (dir / (file.name + ".dl"))
        http.prepareGet(url) { auth?.let { header(HttpHeaders.Authorization, it) } }.execute { response ->
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

    private enum class Kind { ZIP, EPUB, RAR, PDF, OTHER }

    private fun kind(file: Path): Kind {
        val head = fs.read(file) { readByteArray(minOf(4L, fs.metadata(file).size ?: 0)) }
        return when {
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                // An EPUB is a zip whose "mimetype" entry says so.
                if (runCatching { fs.openZip(file).read("mimetype".toPath()) { readUtf8().trim() } }.getOrNull() == "application/epub+zip") Kind.EPUB else Kind.ZIP
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

        /** How wide PDF pages are drawn: sharp on a 4K TV's half and a tablet alike. */
        const val PDF_WIDTH_PX = 1800

        /** An EPUB's position in Jellyfin: the share read, as this many milliseconds for the whole book. */
        const val EPUB_SCALE = 1_000_000L

        /** Read this far, an EPUB counts as finished (the last page is rarely a full one). */
        const val EPUB_FINISHED = 0.995

        /** How wide streamed catalog pages are asked for. */
        const val STREAM_WIDTH_PX = 2000
    }
}
