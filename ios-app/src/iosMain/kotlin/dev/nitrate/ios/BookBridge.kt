package dev.nitrate.ios

import dev.nitrate.android.comics.EpubReaderHost
import dev.nitrate.android.comics.OpenedBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Swift app implements over Readium's Swift toolkit: an EPUB reader presented full screen.
 * Nothing implements it yet: the Swift side is drafted in iosApp/Drafts/ReadiumReader.swift and not
 * built (see its TODO), so [IosPlatform.epubReader] stays null and books open in Nitrate's own
 * `EpubScreen` as before.
 */
interface NitrateBookReader {
    /**
     * Shows the book at [path] from [locator] (Readium's locator JSON saved last time on this device),
     * else from [progression] (0–1). [place] is told each settled place (locator JSON, 0–1).
     */
    fun open(path: String, title: String, locator: String?, progression: Double, place: (locator: String, progression: Double) -> Unit)
}

internal object BookReaders {
    var reader: NitrateBookReader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun host(): EpubReaderHost? = reader?.let { r ->
        object : EpubReaderHost {
            override suspend fun open(book: OpenedBook.Epub) = withContext(Dispatchers.Main) {
                r.open(book.path, book.title, book.locator, book.progression) { locator, progression ->
                    scope.launch { runCatching { book.save(locator, progression) } }
                }
            }
        }
    }
}

/** Called once by the Swift app at start, when it ships Readium: EPUBs open in [reader]. */
fun useBookReader(reader: NitrateBookReader) {
    BookReaders.reader = reader
}
