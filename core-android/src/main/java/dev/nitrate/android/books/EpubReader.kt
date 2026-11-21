package dev.nitrate.android.books

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import dev.nitrate.android.comics.ComicException
import dev.nitrate.android.comics.EpubReaderHost
import dev.nitrate.android.comics.OpenedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * EPUB books on Android, read with Readium (BSD-3-Clause): the book is opened here, then shown in
 * [EpubReaderActivity], paged left and right (swipes, taps at the edges, or the remote's ◀▶), with
 * where the reader is saved as they go.
 */
class AndroidEpubReader(private val context: Context, private val tv: Boolean) : EpubReaderHost {
    override suspend fun open(book: OpenedBook.Epub) {
        val session = withContext(Dispatchers.IO) { prepare(book) }
        EpubReaderActivity.session = session
        context.startActivity(Intent(context, EpubReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
    }

    private suspend fun prepare(book: OpenedBook.Epub): EpubReaderActivity.Session {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val asset = retriever.retrieve(File(book.path)).getOrElse { throw ComicException("This EPUB can't be opened.") }
        val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse { throw ComicException("This EPUB can't be opened (it may be protected).") }
        val start = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: book.progression.takeIf { it > 0.0 }?.let { publication.locateProgression(it) }
        return EpubReaderActivity.Session(publication, start, book.title, tv, book.save)
    }
}

/** Shows an EPUB opened by [AndroidEpubReader] (one at a time). */
class EpubReaderActivity : AppCompatActivity() {
    class Session(
        val publication: Publication,
        val start: Locator?,
        val title: String,
        val tv: Boolean,
        val save: suspend (locator: String, progression: Double) -> Unit,
    ) {
        val factory = EpubNavigatorFactory(publication)
        var last: Locator? = null
    }

    companion object {
        /** The book to show; gone after the process is restarted, when the reader just closes. */
        @Volatile var session: Session? = null
        private const val TAG = "epub"
    }

    private var navigator: EpubNavigatorFragment? = null
    private lateinit var label: TextView

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val s = session
        if (s == null) {
            super.onCreate(null)
            finish()
            return
        }
        // Readium's fragment needs its factory before the activity restores any fragment.
        supportFragmentManager.fragmentFactory = s.factory.createFragmentFactory(
            initialLocator = s.last ?: s.start,
            initialPreferences = preferences(s),
        )
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(if (s.tv) Color.BLACK else Color.parseColor("#121212")) }
        val container = FragmentContainerView(this).apply { id = View.generateViewId() }
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        label = TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = if (s.tv) 16f else 12f
            setPadding(24, 8, 24, 12)
            text = s.title
        }
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(container.id, EpubNavigatorFragment::class.java, Bundle(), TAG).commitNow()
        }
        val nav = supportFragmentManager.findFragmentByTag(TAG) as? EpubNavigatorFragment ?: return finish()
        navigator = nav
        lifecycleScope.launch {
            nav.currentLocator.collect { l ->
                s.last = l
                val percent = ((l.locations.totalProgression ?: 0.0) * 100).toInt()
                label.text = "${s.title} · $percent%"
            }
        }
        // Where the reader is goes back a moment after they stop turning.
        lifecycleScope.launch { nav.currentLocator.debounce(1_500).collect { save(s, it) } }
    }

    private fun preferences(s: Session) = if (s.tv) {
        // Read from across the room: larger type, light on black.
        EpubPreferences(theme = Theme.DARK, fontSize = 1.5, publisherStyles = false, lineHeight = 1.5)
    } else EpubPreferences(theme = Theme.DARK)

    private fun save(s: Session, l: Locator) {
        val progression = l.locations.totalProgression ?: return
        // Not tied to the screen: saving on the way out must finish after it closes.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch { runCatching { s.save(l.toJSON().toString(), progression) } }
    }

    override fun onPause() {
        super.onPause()
        val s = session ?: return
        s.last?.let { save(s, it) }
    }

    /** The remote's ◀▶ (and page keys, fast-forward and rewind) turn pages. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val nav = navigator ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_DOWN -> { nav.goForward(animated = true); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_UP -> { nav.goBackward(animated = true); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
