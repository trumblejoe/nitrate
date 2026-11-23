package dev.nitrate.mobile.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.android.comics.OpenedBook
import dev.nitrate.core.books.Epub
import dev.nitrate.core.books.EpubPackage
import dev.nitrate.core.books.EpubPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** The book taken out of its file once, beside it, and its package read (as on iOS). */
private fun unpack(path: String): Pair<EpubPackage, String> {
    val fs = FileSystem.SYSTEM
    val file = path.toPath()
    val dir = (file.parent ?: "/".toPath()) / "epub"
    val done = dir / ".unpacked"
    if (!fs.exists(done)) {
        val zip = fs.openZip(file)
        if (fs.exists(dir)) fs.deleteRecursively(dir)
        fs.createDirectories(dir)
        zip.listRecursively("/".toPath()).filter { zip.metadataOrNull(it)?.isRegularFile == true }.forEach { entry ->
            val rel = entry.toString().trimStart('/', '\\')
            if (rel.split('/', '\\').any { it == ".." }) return@forEach
            val out = dir / rel
            out.parent?.let(fs::createDirectories)
            zip.source(entry).use { src -> fs.sink(out).buffer().use { it.writeAll(src) } }
        }
        fs.write(done) { writeUtf8("1") }
    }
    val opfPath = Epub.packagePath(fs.read(dir / "META-INF" / "container.xml") { readUtf8() }) ?: error("This EPUB has no package file.")
    val book = Epub.parsePackage(fs.read(dir / opfPath) { readUtf8() }, opfPath) { p -> fs.metadataOrNull(dir / p)?.size ?: 1 }
    if (book.chapters.isEmpty()) error("This EPUB has no chapters.")
    return book to dir.toString()
}

private val BLOCK_END = Regex("(?i)</(p|div|h[1-6]|li|blockquote|section|tr)>|<br\\s*/?>")
private val TAGS = Regex("(?s)<[^>]*>")
private val HEAD = Regex("(?is)<(head|script|style)[^>]*>.*?</\\1>")
private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ", "&#160;" to " ", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“")

/** A chapter's words as paragraphs: the desktop has no web view, so the book is read as text. */
internal fun chapterParagraphs(xhtml: String): List<String> {
    var text = xhtml.replace(HEAD, "").replace(BLOCK_END, "\n\n").replace(TAGS, "")
    ENTITIES.forEach { (k, v) -> text = text.replace(k, v) }
    text = Regex("&#(x?)([0-9a-fA-F]+);").replace(text) { m ->
        val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
        code?.let { String(Character.toChars(it)) } ?: m.value
    }
    return text.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
}

/** Nitrate's own EPUB reader on the desktop: chapter by chapter as text, saving where the reader is. */
@Composable
actual fun EpubScreen(book: OpenedBook.Epub, onExit: () -> Unit) {
    val opened by produceState<Result<Pair<EpubPackage, String>>?>(null, book.path) { value = withContext(Dispatchers.IO) { runCatching { unpack(book.path) } } }
    PlatformBackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        val result = opened
        when {
            result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            result.isFailure -> Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(result.exceptionOrNull()?.message ?: "This EPUB can't be opened.", color = Color.White)
                TextButton(onClick = onExit) { Text("Back") }
            }
            else -> {
                val (pkg, root) = result.getOrThrow()
                Chapters(book, pkg, root, onExit)
            }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun Chapters(book: OpenedBook.Epub, pkg: EpubPackage, root: String, onExit: () -> Unit) {
    val start = remember { Epub.decode(book.locator) ?: Epub.place(pkg, book.progression) }
    var chapter by remember { mutableIntStateOf(start.chapter.coerceIn(0, pkg.chapters.lastIndex)) }
    var fraction by remember { mutableDoubleStateOf(start.fraction) }
    val scope = rememberCoroutineScope()
    fun save() {
        val place = EpubPlace(chapter, fraction)
        scope.launch { book.save(Epub.encode(place), Epub.progression(pkg, place)) }
    }
    val paragraphs by produceState(emptyList<String>(), chapter) {
        value = withContext(Dispatchers.IO) {
            runCatching { chapterParagraphs(FileSystem.SYSTEM.read("$root/${pkg.chapters[chapter].path}".toPath()) { readUtf8() }) }.getOrDefault(emptyList())
        }
    }
    val scroll = rememberScrollState()
    // Back to where the reader was in this chapter once its words are laid out, then follow along.
    LaunchedEffect(paragraphs) {
        if (paragraphs.isEmpty()) return@LaunchedEffect
        val max = snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo((max * fraction).toInt())
    }
    LaunchedEffect(chapter) {
        snapshotFlow { scroll.value to scroll.maxValue }.debounce(1_500).collect { (v, max) ->
            if (max > 0) { fraction = (v.toDouble() / max).coerceIn(0.0, 1.0); save() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { save(); onExit() }) { Text("Done") }
            Text(pkg.title ?: book.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                paragraphs.forEach { Text(it, color = Color(0xFFE8E8E8), style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = chapter > 0, onClick = { fraction = 0.0; chapter-- }) { Text("‹ Previous") }
            Text(
                "Chapter ${chapter + 1} of ${pkg.chapters.size} · ${(Epub.progression(pkg, EpubPlace(chapter, fraction)) * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1,
            )
            TextButton(enabled = chapter < pkg.chapters.lastIndex, onClick = { fraction = 0.0; chapter++ }) { Text("Next ›") }
        }
    }
}
