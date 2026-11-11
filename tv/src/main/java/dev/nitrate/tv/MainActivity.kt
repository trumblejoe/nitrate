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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NitrateApp).container
        if (savedInstanceState == null) take(intent)
        setContent {
            NitrateTheme {
                NitrateNavHost(container, requests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** The remote's search (mic) button opens search, listening. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            requests.value = TvRequest.Search("", voice = true)
            return true
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
