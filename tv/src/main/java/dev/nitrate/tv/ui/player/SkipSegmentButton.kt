package dev.nitrate.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.SegmentKind

/** The words on the skip button for a segment, or null for kinds the TV doesn't offer to skip. */
fun skipLabel(segment: MediaSegment): String? = when (segment.kind) {
    SegmentKind.INTRO -> "Skip intro"
    SegmentKind.RECAP -> "Skip recap"
    else -> null
}

/**
 * "Skip intro" / "Skip recap" over the video. It takes focus when it appears with nothing else in
 * use ([takeFocus]), so one press of OK skips; otherwise it's reachable like the other buttons.
 */
@Composable
fun SkipSegmentButton(segment: MediaSegment, takeFocus: Boolean, onSkip: () -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val label = skipLabel(segment) ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(segment, takeFocus) { if (takeFocus) runCatching { focus.requestFocus() } }
    Button(onClick = onSkip, modifier = modifier.focusRequester(focus).onFocusChanged { onFocus(it.isFocused) }) { Text(label) }
}
