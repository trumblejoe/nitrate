package dev.nitrate.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.nitrate.core.model.SessionState
import platform.UIKit.UIViewController

/** Where the app is: a stack of screens, the last one showing. */
internal sealed interface Screen {
    data object Connect : Screen
    data object Home : Screen
    data class Details(val itemId: String) : Screen
    data class Player(val itemId: String, val startMs: Long) : Screen
}

/** What the Swift app hosts: the whole app as one view controller. */
fun MainViewController(): UIViewController = ComposeUIViewController { NitrateApp(IosContainer.shared) }

internal val Accent = Color(0xFFE8A33D)

@Composable
internal fun NitrateApp(container: IosContainer) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.crossfade(true).build()
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF1B1206),
            background = Color(0xFF0C0E13),
            surface = Color(0xFF151821),
            surfaceVariant = Color(0xFF1E2230),
        ),
    ) {
        val stack = remember { mutableStateListOf<Screen>() }
        val nav = remember {
            object : Nav {
                override fun go(screen: Screen) { stack.add(screen) }
                override fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                override fun reset(screen: Screen) { stack.clear(); stack.add(screen) }
            }
        }
        LaunchedEffect(Unit) {
            val state = runCatching { container.server.restoreSession() }.getOrDefault(SessionState.NONE)
            // Unreachable still opens Home: it shows what it can and retries.
            nav.reset(if (state == SessionState.NONE || state == SessionState.EXPIRED) Screen.Connect else Screen.Home)
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val screen = stack.lastOrNull()) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                Screen.Connect -> ConnectScreen(container) { nav.reset(Screen.Home) }
                Screen.Home -> HomeScreen(container, nav, onSignedOut = { nav.reset(Screen.Connect) })
                is Screen.Details -> DetailsScreen(container, nav, screen.itemId)
                is Screen.Player -> PlayerScreen(container, screen.itemId, screen.startMs, onClose = nav::back)
            }
        }
    }
}

internal interface Nav {
    fun go(screen: Screen)
    fun back()
    fun reset(screen: Screen)
}
