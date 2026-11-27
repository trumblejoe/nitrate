package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadSizing
import dev.nitrate.core.downloads.PickKind
import dev.nitrate.core.downloads.Trip
import dev.nitrate.core.downloads.TripDevice
import dev.nitrate.core.downloads.TripPick
import dev.nitrate.core.downloads.TripPlanner
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "Sat 20 Dec": trips run in whole days (midnight UTC, as the phone's date picker gives them). */
private fun day(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

private fun dates(trip: Trip) = "${day(trip.leaveMs)} – ${day(trip.returnMs)} · ${trip.days} ${if (trip.days == 1) "day" else "days"}"

private val ROOMS: List<Long?> = listOf(null, 4_000_000_000, 8_000_000_000, 16_000_000_000, 32_000_000_000, 64_000_000_000, 128_000_000_000)
private val QUALITIES: List<DownloadQuality?> = listOf(null) + DownloadQuality.entries
private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

/**
 * Settings → Your watching → Packing for a trip, on the TV: plan here with the remote; the phones,
 * tablets and laptop on the trip download their share (the TV itself streams with the travel
 * quality settings). The list of trips, with each device's share.
 */
@Composable
fun TripsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val list by container.trips.trips().collectAsState(emptyList())
    val first = remember { FocusRequester() }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) { runCatching { container.settingsSync.syncNow() }; runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Packing for a trip", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Plan it here; each phone, tablet or laptop on the trip downloads its share at home before you leave. The TV doesn't download: away from home it streams at the quality set in Picture & sound.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onOpen("") }, modifier = Modifier.focusRequester(first)) { Text("Plan a trip") }
            }
        }
        items(list, key = { it.id }) { trip ->
            ListItem(
                selected = false,
                onClick = { onOpen(trip.id) },
                headlineContent = { Text(trip.name) },
                supportingContent = {
                    Text(dates(trip) + " · " + (if (now >= trip.returnMs) "over" else if (now >= trip.leaveMs) "under way" else "${TripPlanner.days(now, trip.leaveMs)} days to go") +
                        " · on " + trip.devices.joinToString { it.name }.ifEmpty { "no device yet" })
                },
            )
        }
    }
}

/** One trip's plan on the TV: name and dates, the devices carrying it, what to pack, top-ups. */
@Composable
fun TripScreen(container: AppContainer, tripId: String, onDone: () -> Unit) {
    val trips = container.trips
    val scope = rememberCoroutineScope()
    val stored by trips.trips().collectAsState(null)
    val packers by trips.packers().collectAsState(emptyList())
    val now = remember { System.currentTimeMillis() }
    var draft by remember { mutableStateOf<Trip?>(null) }
    LaunchedEffect(stored) {
        if (draft != null) return@LaunchedEffect
        val list = stored ?: return@LaunchedEffect
        val today = now - now.mod(TripPlanner.DAY_MS)
        draft = list.firstOrNull { it.id == tripId } ?: Trip(
            id = java.util.UUID.randomUUID().toString().replace("-", "").take(12), name = "",
            leaveMs = today + 7 * TripPlanner.DAY_MS, returnMs = today + 14 * TripPlanner.DAY_MS, createdMs = now,
        )
    }
    val trip = draft ?: return
    val isNew = stored?.none { it.id == trip.id } != false
    var dateFor by remember { mutableStateOf<String?>(null) }
    var pickOpen by remember { mutableStateOf<TripPick?>(null) }
    var adding by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun change(t: Trip) { draft = t }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isNew) "Plan a trip" else trip.name, style = MaterialTheme.typography.headlineMedium)
                TvTextField(trip.name, { change(trip.copy(name = it)) }, placeholder = "Name (e.g. Lake house)", modifier = Modifier.width(480.dp))
                Text(TripPlanner.summary(trip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch { trips.save(trip.copy(name = trip.name.ifBlank { "Trip" })); onDone() } }, modifier = Modifier.focusRequester(first)) { Text("Save") }
                    if (!isNew) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        }
        item { ListItem(selected = false, onClick = { dateFor = "leave" }, headlineContent = { Text("Leaving") }, trailingContent = { Text(day(trip.leaveMs)) }) }
        item { ListItem(selected = false, onClick = { dateFor = "return" }, headlineContent = { Text("Back") }, trailingContent = { Text(day(trip.returnMs)) }) }

        item { Text("Who carries it", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
        if (packers.isEmpty()) item {
            Text("Open Nitrate once on each phone, tablet or laptop that's coming, and it shows here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        packers.forEach { packer ->
            val device = trip.device(packer.deviceId)
            item(key = "p-${packer.deviceId}") {
                ListItem(
                    selected = false,
                    onClick = {
                        change(trip.copy(devices = if (device == null) trip.devices + TripDevice(packer.deviceId, packer.name) else trip.devices.filterNot { it.deviceId == packer.deviceId }))
                    },
                    headlineContent = { Text(packer.name) },
                    supportingContent = {
                        val free = packer.freeBytes?.let { "${DownloadSizing.formatBytes(it)} free" } ?: "free space not known yet"
                        val share = device?.let { TripPlanner.share(trip, it, packer.freeBytes, computer = packer.kind == "computer") }
                        Text("${packer.kind.replaceFirstChar { it.uppercase() }} · $free" + (share?.let { "\n" + it.describe() } ?: ""))
                    },
                    trailingContent = { Switch(checked = device != null, onCheckedChange = null) },
                )
            }
            if (device != null) {
                item(key = "q-${packer.deviceId}") {
                    ListItem(
                        selected = false, onClick = { change(trip.copy(devices = trip.devices.map { if (it.deviceId == device.deviceId) it.copy(quality = QUALITIES.after(it.quality)) else it })) },
                        headlineContent = { Text("Quality on ${packer.name}") }, supportingContent = { Text("Auto takes the best that fits its room") },
                        trailingContent = { Text(device.quality?.label ?: "Auto") }, modifier = Modifier.padding(start = 32.dp),
                    )
                }
                item(key = "r-${packer.deviceId}") {
                    ListItem(
                        selected = false, onClick = { change(trip.copy(devices = trip.devices.map { if (it.deviceId == device.deviceId) it.copy(roomBytes = ROOMS.after(it.roomBytes)) else it })) },
                        headlineContent = { Text("Room on ${packer.name}") }, supportingContent = { Text("How much of its space the trip may use") },
                        trailingContent = { Text(device.roomBytes?.let { DownloadSizing.formatBytes(it) } ?: "All free space") }, modifier = Modifier.padding(start = 32.dp),
                    )
                }
            }
        }

        item {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What to pack", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !filling, onClick = {
                    filling = true
                    scope.launch {
                        val plan = runCatching { trips.plan(trip.name, trip.leaveMs, trip.returnMs, trip.devices) }.getOrNull()
                        if (plan != null) change(trip.copy(hoursPerDay = plan.hoursPerDay, picks = trip.picks.filterNot { it.suggested } + plan.picks.filter { p -> trip.picks.none { it.id == p.id } }))
                        filling = false
                    }
                }) { Text(if (filling) "Filling…" else "Fill it for me") }
                OutlinedButton(onClick = { adding = true }) { Text("Add…") }
            }
        }
        items(trip.picks, key = { "k-${it.id}" }) { pick ->
            ListItem(
                selected = false, onClick = { pickOpen = pick },
                headlineContent = { Text(pick.name) },
                supportingContent = {
                    val on = pick.devices?.let { ids -> trip.devices.filter { it.deviceId in ids }.joinToString { it.name } }
                    Text(TripPlanner.pickWords(pick) + (on?.let { " · only on $it" } ?: "") + if (pick.suggested) " · suggested" else "")
                },
            )
        }
        item {
            ListItem(
                selected = false, onClick = { change(trip.copy(topUpOnWifi = !trip.topUpOnWifi)) },
                headlineContent = { Text("Top up on hotel Wi-Fi") },
                supportingContent = { Text("During the trip, on Wi-Fi that isn't mobile data, swap shows' watched episodes for the next ones") },
                trailingContent = { Switch(checked = trip.topUpOnWifi, onCheckedChange = null) },
            )
        }
    }

    dateFor?.let { which ->
        val today = now - now.mod(TripPlanner.DAY_MS)
        val from = if (which == "leave") today else trip.leaveMs + TripPlanner.DAY_MS
        val current = if (which == "leave") trip.leaveMs else trip.returnMs
        val focus = remember { FocusRequester() }
        TvDialog(onDismiss = { dateFor = null }) {
            Text(if (which == "leave") "Leaving" else "Back", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.height(560.dp)) {
                items((0 until 180).map { from + it * TripPlanner.DAY_MS }) { d ->
                    ListItem(
                        selected = d == current,
                        onClick = {
                            change(if (which == "leave") trip.copy(leaveMs = d, returnMs = maxOf(trip.returnMs, d + TripPlanner.DAY_MS)) else trip.copy(returnMs = d))
                            dateFor = null
                        },
                        headlineContent = { Text(day(d)) },
                        modifier = if (d == current || (current < from && d == from)) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    pickOpen?.let { open ->
        val pick = trip.picks.firstOrNull { it.id == open.id } ?: run { pickOpen = null; return }
        val focus = remember { FocusRequester() }
        fun edit(p: TripPick) = change(trip.copy(picks = trip.picks.map { if (it.id == p.id) p else it }))
        TvDialog(onDismiss = { pickOpen = null }) {
            Text(pick.name, style = MaterialTheme.typography.titleLarge)
            Text(TripPlanner.pickWords(pick), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pick.kind == PickKind.SHOW) {
                ListItem(selected = false, onClick = { edit(pick.copy(episodes = (pick.episodes ?: 1) + 1)) }, headlineContent = { Text("One more episode") }, modifier = Modifier.focusRequester(focus))
                ListItem(selected = false, onClick = { edit(pick.copy(episodes = ((pick.episodes ?: 1) - 1).coerceAtLeast(1))) }, headlineContent = { Text("One fewer") })
            }
            if (trip.devices.size > 1) trip.devices.forEach { d ->
                val on = pick.devices?.contains(d.deviceId) ?: true
                ListItem(
                    selected = false,
                    onClick = {
                        val now = pick.devices ?: trip.devices.map { it.deviceId }
                        val next = if (on) now - d.deviceId else now + d.deviceId
                        edit(pick.copy(devices = next.takeIf { it.toSet() != trip.devices.map { x -> x.deviceId }.toSet() }))
                    },
                    headlineContent = { Text("On ${d.name}") },
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                    modifier = if (pick.kind != PickKind.SHOW && d == trip.devices.first()) Modifier.focusRequester(focus) else Modifier,
                )
            }
            ListItem(
                selected = false, onClick = { change(trip.copy(picks = trip.picks.filterNot { it.id == pick.id })); pickOpen = null },
                headlineContent = { Text("Take it out") },
                modifier = if (pick.kind != PickKind.SHOW && trip.devices.size <= 1) Modifier.focusRequester(focus) else Modifier,
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    if (adding) AddToTripPanel(container, onDismiss = { adding = false }) { item ->
        scope.launch {
            val pick = trips.pickFor(item)
            draft = draft?.let { t -> if (t.picks.any { it.id == pick.id }) t else t.copy(picks = t.picks + pick) }
        }
    }
    if (confirmDelete) TvDialog(onDismiss = { confirmDelete = false }) {
        val focus = remember { FocusRequester() }
        Text("Delete ${trip.name}?", style = MaterialTheme.typography.titleLarge)
        Text("The plan goes from every device. What's already downloaded stays until it's cleared on each.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { confirmDelete = false }, modifier = Modifier.focusRequester(focus)) { Text("Cancel") }
            Button(onClick = { scope.launch { trips.delete(trip.id); onDone() } }) { Text("Delete") }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** Find a film, show or collection to pack, typed with the remote's keyboard. */
@Composable
private fun AddToTripPanel(container: AppContainer, onDismiss: () -> Unit, onPick: (MediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by produceState<List<MediaItem>>(emptyList(), query) {
        if (query.length < 2) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = runCatching { container.server.search(query) }.getOrNull()?.let { it.shows + it.movies + it.collections }.orEmpty().take(20)
    }
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss, width = 640) {
        Text("Add to the trip", style = MaterialTheme.typography.titleLarge)
        TvTextField(query, { query = it }, placeholder = "Find a film, show or collection", modifier = Modifier.fillMaxWidth().focusRequester(focus))
        results.forEach { item ->
            ListItem(
                selected = false, onClick = { onPick(item); onDismiss() },
                headlineContent = { Text(item.name) },
                supportingContent = {
                    Text(when (item.kind) {
                        ItemKind.SERIES -> "Show: its next episodes"
                        ItemKind.BOX_SET -> "Collection"
                        else -> listOfNotNull("Film", item.productionYear?.toString()).joinToString(" · ")
                    })
                },
            )
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
