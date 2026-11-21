package dev.nitrate.mobile.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import dev.nitrate.android.comics.OpenedBook
import dev.nitrate.core.books.Epub
import dev.nitrate.core.books.EpubPackage
import dev.nitrate.core.books.EpubPlace
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/** The book taken out of its file once, beside it, and its package read. */
private fun unpack(path: String): Pair<EpubPackage, String> {
    val fs = FileSystem.SYSTEM
    val file = path.toPath()
    val dir = (file.parent ?: "/".toPath()) / "epub"
    val done = dir / ".unpacked"
    val zip = fs.openZip(file)
    if (!fs.exists(done)) {
        if (fs.exists(dir)) fs.deleteRecursively(dir)
        fs.createDirectories(dir)
        zip.listRecursively("/".toPath()).filter { zip.metadataOrNull(it)?.isRegularFile == true }.forEach { entry ->
            val rel = entry.toString().trimStart('/')
            if (rel.split('/').any { it == ".." }) return@forEach
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

private const val STYLE = "(function(){var s=document.createElement('style');s.textContent=" +
    "'html,body{background:#121212 !important;color:#e8e8e8 !important}" +
    "body{font-size:112%;line-height:1.6;max-width:40em;margin:0 auto !important;padding:1.4em 1.2em 4em !important;-webkit-text-size-adjust:100%}" +
    "a{color:#9ecbff}img,svg{max-width:100% !important;height:auto !important}p,div,span,li{color:#e8e8e8 !important;background:transparent !important}';" +
    "(document.head||document.documentElement).appendChild(s);})();"

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EpubScreen(book: OpenedBook.Epub, onExit: () -> Unit) {
    val opened by produceState<Result<Pair<EpubPackage, String>>?>(null, book.path) { value = withContext(Dispatchers.IO) { runCatching { unpack(book.path) } } }
    PlatformBackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color(0xFF121212)).windowInsetsPadding(WindowInsets.safeDrawing)) {
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

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun Chapters(book: OpenedBook.Epub, pkg: EpubPackage, root: String, onExit: () -> Unit) {
    val start = remember { Epub.decode(book.locator) ?: Epub.place(pkg, book.progression) }
    var chapter by remember { mutableIntStateOf(start.chapter.coerceIn(0, pkg.chapters.lastIndex)) }
    var fraction by remember { mutableDoubleStateOf(start.fraction) }
    val scope = rememberCoroutineScope()
    val web = remember {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply {
            opaque = false
            backgroundColor = UIColor.blackColor
        }
    }
    fun save() {
        val place = EpubPlace(chapter, fraction)
        scope.launch { book.save(Epub.encode(place), Epub.progression(pkg, place)) }
    }

    // Each chapter: loaded from the unpacked book, styled to read, and scrolled to where the reader was.
    LaunchedEffect(chapter) {
        val target = if (chapter == start.chapter && fraction == start.fraction) start.fraction else fraction
        web.loadFileURL(NSURL.fileURLWithPath("$root/${pkg.chapters[chapter].path}"), allowingReadAccessToURL = NSURL.fileURLWithPath(root))
        delay(100)
        while (web.loading) delay(80)
        web.evaluateJavaScript(STYLE + "window.scrollTo(0,(document.documentElement.scrollHeight-window.innerHeight)*$target);", null)
        save()
        // Where the reader is down the chapter, every couple of seconds.
        while (true) {
            delay(2_000)
            web.evaluateJavaScript("window.scrollY/Math.max(1,document.documentElement.scrollHeight-window.innerHeight)") { value, _ ->
                val f = (value as? NSNumber)?.doubleValue ?: return@evaluateJavaScript
                if (kotlin.math.abs(f - fraction) > 0.01) { fraction = f.coerceIn(0.0, 1.0); save() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { save(); onExit() }) { Text("Done") }
            Text(pkg.title ?: book.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        UIKitView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
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
