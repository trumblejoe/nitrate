package dev.nitrate.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.nitrate.android.AppContainer
import dev.nitrate.mobile.platform.DesktopToasts
import dev.nitrate.mobile.platform.DesktopWindow
import dev.nitrate.mobile.ui.MobileApp
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path.Companion.toPath

/** The one container for the app run. */
object DesktopApp {
    val version: DesktopVersion by lazy { DesktopVersion.load() }

    /** An item to open once signed in. */
    val openItem = MutableStateFlow<String?>(null)

    val downloads = DesktopDownloadScheduler { container }

    val platform: DesktopPlatform by lazy {
        val folders = DesktopFolders.system()
        DesktopPlatform(version, folders, bitstream = { dev.nitrate.mobile.ui.DesktopPlayerSettings.shared(folders.data.absolutePath).bitstream })
    }

    val container: AppContainer by lazy { AppContainer(platform, clientName = "Nitrate", version = version.name) }
}

fun main() {
    val playerSettings = dev.nitrate.mobile.ui.DesktopPlayerSettings.shared(DesktopApp.platform.filesDir)
    // Popups and dialogs as windows of their own, so the player's controls can sit over mpv's video
    // when it's a native window inside Compose's: see docs/features/windows.md.
    if (playerSettings.values.value.output == dev.nitrate.mobile.ui.VideoOutput.WINDOW) System.setProperty("compose.layers.type", "WINDOW")
    registerMpv()
    // Windows' own look for the file dialogs.
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            title = if (DesktopApp.version.channel == dev.nitrate.core.update.UpdateChannel.DEV) "Nitrate Dev" else "Nitrate",
            icon = painterResource("nitrate-icon.png"),
            state = state,
            onPreviewKeyEvent = { windowShortcut(it) },
        ) {
            remember(window) { DesktopWindow.frame = window; window.minimumSize = java.awt.Dimension(480, 560) }
            FollowFullscreen(state)
            DesktopContent(DesktopApp.container, DesktopApp.openItem, cacheDir = DesktopApp.platform.cacheDir) { mouseBack(window) }
            LaunchedEffect(Unit) { DesktopApp.downloads.resume() }
        }
    }
}

/** libmpv, when it's beside the app: the player plays through it; without it, the player says what's missing. */
private fun registerMpv() {
    when (val lib = dev.nitrate.desktop.mpv.LibMpvLoader.load()) {
        is dev.nitrate.desktop.mpv.LibMpvLoader.Result.Loaded -> dev.nitrate.mobile.ui.DesktopMpv.factory = { config ->
            val api = dev.nitrate.desktop.mpv.JnaMpvApi.create(lib) ?: error("libmpv couldn't start a player")
            dev.nitrate.desktop.mpv.LibMpvPlayer(api, config, windowId = { com.sun.jna.Native.getComponentID(it) })
        }
        is dev.nitrate.desktop.mpv.LibMpvLoader.Result.Missing -> dev.nitrate.mobile.ui.DesktopMpv.missing = lib.reason
    }
}

/** Window-wide keys: F11 full screen, Esc leaves it first (then goes back, as Compose handles). */
private fun windowShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.F11 -> { DesktopWindow.toggleFullscreen(); true }
        event.key == Key.Escape && DesktopWindow.fullscreen.value -> { DesktopWindow.fullscreen.value = false; true }
        else -> false
    }
}

@Composable
private fun FollowFullscreen(state: WindowState) {
    val full by DesktopWindow.fullscreen.collectAsState()
    LaunchedEffect(full) { state.placement = if (full) WindowPlacement.Fullscreen else WindowPlacement.Floating }
}

/** The mouse's back button goes back like Esc (Compose's back navigation listens for Esc). */
private fun mouseBack(window: java.awt.Window) {
    val target = window.mostRecentFocusOwner ?: window
    val now = System.currentTimeMillis()
    for (id in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED)) {
        target.dispatchEvent(java.awt.event.KeyEvent(target, id, now, 0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED))
    }
}

/**
 * What the window shows: the phone/tablet app (two panes once it's wide), image loading with a
 * disk cache, and a snackbar for the screens' short messages.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopContent(container: AppContainer, openItem: MutableStateFlow<String?>, cacheDir: String, onMouseBack: () -> Unit = {}) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .diskCache { DiskCache.Builder().directory("$cacheDir/images".toPath()).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { DesktopToasts.messages.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { e -> if (e.button == PointerButton.Back) onMouseBack() }) {
        MobileApp(container, openItem)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}
