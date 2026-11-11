package dev.nitrate.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.mobile.ui.MobileApp
import dev.nitrate.mobile.ui.NitrateTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** An item to open, from a tapped notification. */
    private val openItem = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NitrateApp).container
        if (savedInstanceState == null) take(intent)
        setContent {
            NitrateTheme {
                MobileApp(container, openItem)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        intent?.getStringExtra(NewEpisodeNotifier.EXTRA_OPEN_ITEM)?.let { openItem.value = it }
        intent?.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_ITEM)
    }
}
