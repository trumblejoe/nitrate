package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/**
 * It kept buffering: "Lower the quality" in the action corner, taking focus ([takeFocus]) so one OK
 * plays on from here at [toBps] (this title only); Back puts it aside.
 */
@Composable
fun LowerQualityBanner(toBps: Int, takeFocus: Boolean, onLower: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(toBps, takeFocus) { if (takeFocus) runCatching { focus.requestFocus() } }
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6101216)).padding(start = 24.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("It keeps buffering", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.width(18.dp))
        Button(onClick = onLower, modifier = Modifier.focusRequester(focus)) {
            Text("Lower the quality (${dev.nitrate.core.playback.formatBitrate(toBps)})")
        }
    }
}
