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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** An item to open, from a tapped notification. */
    private val openItem = MutableStateFlow<String?>(null)

    // Theme music never plays while Nitrate is out of view.
    override fun onStart() {
        super.onStart()
        (application as NitrateApp).container.themeMusic.onAppShown()
        (application as NitrateApp).container.onAppShown()
    }

    override fun onStop() {
        (application as NitrateApp).container.themeMusic.onAppHidden()
        // What was just watched moves on in the home-screen widgets.
        val app = applicationContext
        kotlinx.coroutines.MainScope().launch { runCatching { dev.nitrate.mobile.widgets.WatchWidgets.refresh(app) } }
        super.onStop()
    }

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
        intent?.getStringExtra(NewEpisodeNotifier.EXTRA_OPEN_PARTY)?.let { openItem.value = dev.nitrate.mobile.ui.PARTY_PREFIX + it }
        intent?.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_PARTY)
        intent?.getStringExtra(NewEpisodeNotifier.EXTRA_OPEN_SCHEDULE)?.let { openItem.value = dev.nitrate.mobile.ui.SCHEDULE_PREFIX + it }
        intent?.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_SCHEDULE)
        // An invite: Watch groups, where it waits with Accept and Decline.
        if (intent?.getBooleanExtra(NewEpisodeNotifier.EXTRA_OPEN_INVITES, false) == true) openItem.value = dev.nitrate.mobile.ui.OPEN_INVITES
        intent?.removeExtra(NewEpisodeNotifier.EXTRA_OPEN_INVITES)
        // A widget's title: straight into the player, resuming.
        intent?.getStringExtra(dev.nitrate.mobile.widgets.WatchWidgets.EXTRA_PLAY_ITEM)?.let { openItem.value = dev.nitrate.mobile.ui.PLAY_ITEM_PREFIX + it }
        intent?.removeExtra(dev.nitrate.mobile.widgets.WatchWidgets.EXTRA_PLAY_ITEM)
    }
}
