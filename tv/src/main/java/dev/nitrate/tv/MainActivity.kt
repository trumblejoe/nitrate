package dev.nitrate.tv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.nitrate.tv.channels.TvLinks
import dev.nitrate.tv.ui.NitrateNavHost
import dev.nitrate.tv.ui.TvRequest
import dev.nitrate.tv.ui.theme.NitrateTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Something the home screen, voice search or the remote asked for; the UI takes it once signed in. */
    private val requests = MutableStateFlow<TvRequest?>(null)

    // Theme music never plays while Nitrate is out of view.
    override fun onStart() {
        super.onStart()
        dev.nitrate.tv.ui.ambient.TvIdle.shown()
        (application as NitrateApp).container.themeMusic.onAppShown()
        (application as NitrateApp).container.onAppShown()
    }

    override fun onStop() {
        dev.nitrate.tv.ui.ambient.TvIdle.hidden()
        (application as NitrateApp).container.themeMusic.onAppHidden()
        // A film's refresh rate kept for the menus ends with Nitrate: the TV's usual rate when it's back.
        dev.nitrate.tv.ui.player.resetRefreshRate(this)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NitrateApp).container
        if (savedInstanceState == null) take(intent)
        setContent {
            NitrateTheme {
                // Screens deep in a page (the Customize panel) save settings through the container.
                androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.pages.LocalTvContainer provides container) {
                    NitrateNavHost(container, requests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** Every press counts as the TV being used (the ambient screensaver waits for none). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        dev.nitrate.tv.ui.ambient.TvIdle.press()
        return super.dispatchKeyEvent(event)
    }

    /** The remote's search (mic) button opens search, listening. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            requests.value = TvRequest.Search("", voice = true)
            return true
        }
        // Media keys nothing on screen used (no video open): they work the music, from any page.
        val listening = (application as NitrateApp).container.listening
        if (listening != null && listening.state.value.active) when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { listening.toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { listening.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { listening.previous(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun take(intent: Intent?) {
        intent ?: return
        val request = when {
            intent.action == Intent.ACTION_SEARCH -> TvRequest.Search(intent.getStringExtra(SearchManager.QUERY).orEmpty(), voice = false)
            else -> TvLinks.parse(intent)?.let { (action, id) ->
                when {
                    action == "item" && id != null -> TvRequest.Open(id)
                    action == "play" && id != null -> TvRequest.Play(id)
                    else -> null
                }
            }
        }
        if (request != null) requests.value = request
    }
}
