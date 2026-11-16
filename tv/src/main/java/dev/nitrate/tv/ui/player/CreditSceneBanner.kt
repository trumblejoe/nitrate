package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.playback.CreditSkipper
import kotlinx.coroutines.delay

/**
 * A scene during or after the credits: "Skipped the credits" with "Watch credits" (undo), the
 * scene offered with "Skip to it", or a heads-up to stay. The button takes focus so one press of
 * OK acts; it goes away by itself after a while (or with Back).
 */
@Composable
fun CreditSceneBanner(action: CreditSkipper.Action, onAct: (CreditSkipper.Action) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val button = when (action) {
        is CreditSkipper.Action.Skip -> "Watch credits"
        is CreditSkipper.Action.Offer -> "Skip to it"
        is CreditSkipper.Action.HeadsUp -> null
    }
    LaunchedEffect(action) {
        if (button != null) runCatching { focus.requestFocus() }
        delay(if (button == null) 6_000 else 10_000)
        onDone()
    }
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(start = 24.dp, end = if (button != null) 10.dp else 24.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(CreditSkipper.message(action), style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (button != null) {
            Spacer(Modifier.width(18.dp))
            Button(onClick = { onAct(action); onDone() }, modifier = Modifier.focusRequester(focus)) { Text(button) }
        }
    }
}
