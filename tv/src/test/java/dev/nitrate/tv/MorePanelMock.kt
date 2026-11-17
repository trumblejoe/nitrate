package dev.nitrate.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.ui.player.TitleCard
import dev.nitrate.tv.ui.player.TitleCardView

/**
 * A mock-up (tests only) of the proposed More panel: the video stays visible above, a panel across
 * the bottom with tabs, one tab's content at a time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MorePanelMock(backdrop: String?, tab: String, cards: List<TitleCard>) {
    val tabs = listOf("Episodes", "Chapters", "Cast", "Extras", "Settings")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The video, still playing.
        if (backdrop != null) coil3.compose.AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2101317), Color(0xFA101317))))
                .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tabs.forEach { t ->
                    val on = t == tab
                    Text(
                        t, style = MaterialTheme.typography.titleMedium,
                        color = if (on) Color.Black else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.background(if (on) Color.White else Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Box(Modifier.weight(1f))
                Text("◀ ▶ tabs   ·   Back closes", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
            }
            if (tab == "Settings") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(200.dp)) {
                    @Composable fun Group(title: String, chips: List<String>, on: String) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(end = 16.dp).fillMaxWidth(0.14f))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { c -> FilterChip(selected = c == on, onClick = {}) { Text(c) } } }
                        }
                    }
                    Group("Speed", listOf("0.75×", "Normal", "1.25×", "1.5×"), "Normal")
                    Group("Sound", listOf("Normal", "Clearer dialogue", "Night"), "Clearer dialogue")
                    Group("Sleep timer", listOf("Off", "15 min", "30 min", "60 min", "End of this"), "Off")
                    Group("More", listOf("Second subtitles…", "Subtitle timing…", "Audio sync…", "Find subtitles…", "Watch together…"), "")
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp), modifier = Modifier.height(200.dp)) {
                    items(cards.size) { i -> TitleCardView(cards[i], onClick = {}, modifier = Modifier) }
                }
            }
        }
    }
}
