package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadSizing
import dev.nitrate.core.downloads.Packer
import dev.nitrate.core.downloads.PickKind
import dev.nitrate.core.downloads.Trip
import dev.nitrate.core.downloads.TripDevice
import dev.nitrate.core.downloads.TripPick
import dev.nitrate.core.downloads.TripPlanner
import dev.nitrate.core.model.MediaItem
import dev.nitrate.mobile.platform.DateStyle
import dev.nitrate.mobile.platform.formatDate
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object TripsRoute

/** One trip's plan; [tripId] empty for a new one. */
@Serializable data class TripRoute(val tripId: String = "")

/**
 * Packing for trips: each trip with how this device's share is going, trips that are over with
 * the choice of what to clear, and "Plan a trip".
 */
@Composable
fun TripsScreen(actions: AppActions) {
    val trips = actions.container.trips
    val list by trips.trips().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    // Read again when the list changes: trips over with downloads here, not settled yet.
    val over by produceState(emptyList<Trip>(), list) { value = runCatching { trips.over() }.getOrDefault(emptyList()) }
    var settled by remember { mutableStateOf(setOf<String>()) }
    val now = remember { dev.nitrate.android.nowMs() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Row(Modifier.padding(start = 4.dp, end = ScreenPadding, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Packing for a trip", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Text(
                        "Plan here or on the TV; each phone, tablet or laptop on the trip downloads its share at home before you leave.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(over.filter { it.id !in settled }, key = { "over-${it.id}" }) { trip ->
            val records = trips.recordsHere(trip.id)
            val watched = TripPlanner.toClear(records, trip.id, watchedOnly = true).size
            val all = TripPlanner.toClear(records, trip.id, watchedOnly = false).size
            Card(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Back from ${trip.name}?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${records.size} titles were packed on this device. What you started and haven't finished stays either way.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { trips.settle(trip.id, watchedOnly = true); settled = settled + trip.id } }) { Text("Remove watched ($watched)") }
                        OutlinedButton(onClick = { scope.launch { trips.settle(trip.id, watchedOnly = false); settled = settled + trip.id } }) { Text("Remove all ($all)") }
                        TextButton(onClick = { scope.launch { trips.settle(trip.id, watchedOnly = false, keep = true); settled = settled + trip.id } }) { Text("Keep them") }
                    }
                }
            }
        }
        if (list.isEmpty()) item {
            Text(
                "No trips planned. A plan fills itself from the shows you're in the middle of and films you've started, for as much as you usually watch.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ScreenPadding),
            )
        }
        items(list, key = { it.id }) { trip ->
            val ready = trips.readinessHere(trip)
            ListItem(
                modifier = Modifier.pressable({ actions.nav.navigate(TripRoute(trip.id)) }),
                headlineContent = { Text(trip.name, fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Column {
                        Text(tripDates(trip) + " · " + if (now >= trip.returnMs) "over" else if (now >= trip.leaveMs) "under way" else "${TripPlanner.days(now, trip.leaveMs)} days to go")
                        Text("On " + trip.devices.joinToString { it.name }.ifEmpty { "no device yet" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ready?.let { Text(it.describe(now, "this ${trips.here?.kind ?: "device"}"), color = if (it.late) MaterialTheme.colorScheme.error else accentColor()) }
                    }
                },
            )
        }
        item {
            Button(onClick = { actions.nav.navigate(TripRoute()) }, modifier = Modifier.padding(ScreenPadding)) { Text("Plan a trip") }
        }
    }
}

internal fun tripDates(trip: Trip): String =
    "${formatDate(trip.leaveMs, DateStyle.MEDIUM)} – ${formatDate(trip.returnMs, DateStyle.MEDIUM)} · ${trip.days} ${if (trip.days == 1) "day" else "days"}"

/** Room choices for a device: all its free space (less a margin), or a set amount. */
internal val ROOMS: List<Long?> = listOf(null, 4_000_000_000, 8_000_000_000, 16_000_000_000, 32_000_000_000, 64_000_000_000, 128_000_000_000)

internal fun roomWords(room: Long?) = room?.let { DownloadSizing.formatBytes(it) } ?: "All free space"

/** Quality choices for a device: Auto (the best that fits), then each quality. */
internal val TRIP_QUALITIES: List<DownloadQuality?> = listOf(null) + DownloadQuality.entries

internal fun qualityWords(q: DownloadQuality?) = q?.label ?: "Auto"

internal fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

/**
 * One trip's plan: name and dates, which devices carry it (each with its room and quality, and
 * what its share comes to), what to pack (filled from what the user watches, or added by hand), top-ups
 * on hotel Wi-Fi, and how this device's packing is going.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(actions: AppActions, tripId: String) {
    val trips = actions.container.trips
    val scope = rememberCoroutineScope()
    val stored by trips.trips().collectAsState(null)
    val packers by trips.packers().collectAsState(emptyList())
    val here = trips.here
    val now = remember { dev.nitrate.android.nowMs() }
    var draft by remember { mutableStateOf<Trip?>(null) }
    LaunchedEffect(stored) {
        if (draft != null) return@LaunchedEffect
        val list = stored ?: return@LaunchedEffect
        draft = list.firstOrNull { it.id == tripId } ?: Trip(
            id = kotlin.uuid.Uuid.random().toHexString().take(12), name = "",
            leaveMs = startOfDay(now) + TripPlanner.DAY_MS * 7, returnMs = startOfDay(now) + TripPlanner.DAY_MS * 14,
            devices = listOfNotNull(here?.let { TripDevice(it.deviceId, it.name) }), createdMs = now,
        )
    }
    val trip = draft ?: return Loading()
    val isNew = stored?.none { it.id == trip.id } != false
    var filling by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun change(t: Trip) { draft = t }
    fun save(then: () -> Unit = {}) = scope.launch { trips.save(trip.copy(name = trip.name.ifBlank { "Trip" })); then() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Row(Modifier.padding(start = 4.dp, end = ScreenPadding, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(if (isNew) "Plan a trip" else trip.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Button(onClick = { save { actions.nav.popBackStack() } }) { Text("Save") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(trip.name, { change(trip.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "leave" }) { Text("Leaving " + formatDate(trip.leaveMs, DateStyle.MEDIUM)) }
                    OutlinedButton(onClick = { picking = "return" }) { Text("Back " + formatDate(trip.returnMs, DateStyle.MEDIUM)) }
                }
                Text(TripPlanner.summary(trip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { SectionTitle("Who carries it", Modifier.padding(top = 16.dp)) }
        if (packers.isEmpty()) item {
            Text(
                "Open Nitrate once on each phone, tablet or laptop that's coming, and it shows here. TVs don't download.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        items(packers, key = { "p-${it.deviceId}" }) { packer ->
            val device = trip.device(packer.deviceId)
            DeviceLine(packer, device, trip, isHere = packer.deviceId == here?.deviceId,
                onToggle = { on ->
                    change(trip.copy(devices = if (on) trip.devices + TripDevice(packer.deviceId, packer.name) else trip.devices.filterNot { it.deviceId == packer.deviceId }))
                },
                onChange = { d -> change(trip.copy(devices = trip.devices.map { if (it.deviceId == d.deviceId) d else it })) },
            )
        }

        item {
            Row(Modifier.padding(start = ScreenPadding, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("What to pack", Modifier.weight(1f))
                TextButton(enabled = !filling, onClick = {
                    filling = true
                    scope.launch {
                        val plan = runCatching { trips.plan(trip.name, trip.leaveMs, trip.returnMs, trip.devices) }.getOrNull()
                        // Suggestions replace earlier suggestions; what was added by hand stays.
                        if (plan != null) change(trip.copy(hoursPerDay = plan.hoursPerDay, picks = trip.picks.filterNot { it.suggested } + plan.picks.filter { p -> trip.picks.none { it.id == p.id } }))
                        filling = false
                    }
                }) { Text(if (filling) "Filling…" else "Fill it for me") }
                TextButton(onClick = { adding = true }) { Text("Add…") }
            }
        }
        if (trip.picks.isEmpty()) item {
            Text(
                "Nothing yet. \"Fill it for me\" picks the next episodes of what you're watching and films you've started, for about ${TripPlanner.viewingWords((trip.hoursPerDay * trip.days * 3_600_000).toLong())}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        items(trip.picks, key = { "k-${it.id}" }) { pick ->
            PickLine(pick, trip, onChange = { p -> change(trip.copy(picks = trip.picks.map { if (it.id == p.id) p else it })) }, onRemove = { change(trip.copy(picks = trip.picks - pick)) })
        }

        item {
            ListItem(
                modifier = Modifier.toggleable(trip.topUpOnWifi, role = Role.Switch) { change(trip.copy(topUpOnWifi = it)) },
                headlineContent = { Text("Top up on hotel Wi-Fi") },
                supportingContent = { Text("During the trip, on Wi-Fi that isn't mobile data, swap shows' watched episodes for the next ones") },
                trailingContent = { Switch(checked = trip.topUpOnWifi, onCheckedChange = null) },
            )
        }

        trips.readinessHere(trip)?.let { r ->
            item { SectionTitle("On this ${here?.kind ?: "device"}", Modifier.padding(top = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.describe(now, "this ${here?.kind ?: "device"}"), color = if (r.late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (now < trip.leaveMs) OutlinedButton(onClick = { save { actions.scope.launch { runCatching { trips.pack() } } } }) { Text("Save and pack now") }
                }
            }
        }

        if (!isNew) item {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                Text("Delete this trip", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    picking?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = if (which == "leave") trip.leaveMs else trip.returnMs)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = startOfDay(ms)
                        change(
                            if (which == "leave") trip.copy(leaveMs = day, returnMs = maxOf(trip.returnMs, day + TripPlanner.DAY_MS))
                            else trip.copy(returnMs = maxOf(day, trip.leaveMs + TripPlanner.DAY_MS)),
                        )
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (adding) AddToTripSheet(actions, onDismiss = { adding = false }) { item ->
        scope.launch {
            val pick = trips.pickFor(item)
            draft = draft?.let { t -> if (t.picks.any { it.id == pick.id }) t else t.copy(picks = t.picks + pick) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${trip.name}?") },
        text = { Text("The plan goes from every device. What's already downloaded stays until you clear it.") },
        confirmButton = { TextButton(onClick = { scope.launch { trips.delete(trip.id); actions.nav.popBackStack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Midnight (UTC) of the day [ms] falls on: trips run in whole days, as the date picker gives them. */
internal fun startOfDay(ms: Long) = ms - ms.mod(TripPlanner.DAY_MS)

@Composable
private fun DeviceLine(packer: Packer, device: TripDevice?, trip: Trip, isHere: Boolean, onToggle: (Boolean) -> Unit, onChange: (TripDevice) -> Unit) {
    Column {
        ListItem(
            modifier = Modifier.toggleable(device != null, role = Role.Checkbox, onValueChange = onToggle),
            leadingContent = { Checkbox(checked = device != null, onCheckedChange = null) },
            headlineContent = { Text(packer.name + if (isHere) " (this ${packer.kind})" else "") },
            supportingContent = {
                val free = packer.freeBytes?.let { "${DownloadSizing.formatBytes(it)} free" } ?: "free space not known yet"
                Column {
                    Text("${packer.kind.replaceFirstChar { it.uppercase() }} · $free")
                    device?.let { d ->
                        val share = TripPlanner.share(trip, d, packer.freeBytes, computer = packer.kind == "computer")
                        Text(share.describe(), color = if (share.fits) accentColor() else MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        device?.let { d ->
            Row(Modifier.padding(start = 72.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = d.quality != null, onClick = { onChange(d.copy(quality = TRIP_QUALITIES.after(d.quality))) }, label = { Text("Quality: ${qualityWords(d.quality)}") })
                FilterChip(selected = d.roomBytes != null, onClick = { onChange(d.copy(roomBytes = ROOMS.after(d.roomBytes))) }, label = { Text("Room: ${roomWords(d.roomBytes)}") })
            }
        }
    }
}

@Composable
private fun PickLine(pick: TripPick, trip: Trip, onChange: (TripPick) -> Unit, onRemove: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(pick.name) },
            supportingContent = { Text(TripPlanner.pickWords(pick) + if (pick.suggested) " · suggested" else "") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pick.kind == PickKind.SHOW) {
                        TextButton(onClick = { onChange(pick.copy(episodes = ((pick.episodes ?: 1) - 1).coerceAtLeast(1))) }) { Text("−") }
                        Text("${pick.episodes ?: 1}", modifier = Modifier.width(24.dp))
                        TextButton(onClick = { onChange(pick.copy(episodes = (pick.episodes ?: 1) + 1)) }) { Text("+") }
                    }
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Take ${pick.name} out") }
                }
            },
        )
        // With more than one device: which of them carry it.
        if (trip.devices.size > 1) Row(Modifier.padding(start = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trip.devices.forEach { d ->
                val on = pick.devices?.contains(d.deviceId) ?: true
                FilterChip(selected = on, onClick = {
                    val now = pick.devices ?: trip.devices.map { it.deviceId }
                    val next = if (on) now - d.deviceId else now + d.deviceId
                    onChange(pick.copy(devices = next.takeIf { it.toSet() != trip.devices.map { x -> x.deviceId }.toSet() }))
                }, label = { Text(d.name) })
            }
        }
    }
}

/** Find a film, show, collection or episode to pack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToTripSheet(actions: AppActions, onDismiss: () -> Unit, onPick: (MediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by produceState<dev.nitrate.core.model.SearchResults?>(null, query) {
        if (query.length < 2) { value = null; return@produceState }
        kotlinx.coroutines.delay(300)
        value = runCatching { actions.server.search(query) }.getOrNull()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find a film, show or collection") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val r = results
            val found = r?.let { it.shows + it.movies + it.collections + it.episodes }.orEmpty().take(30)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(found, key = { it.id }) { item ->
                    val safe = spoilerSafe(item)
                    ListItem(
                        modifier = Modifier.pressable({ onPick(item); onDismiss() }),
                        headlineContent = { Text(if (item.isEpisode) "${item.seriesName ?: ""} · S${item.seasonNumber ?: "?"}:E${item.indexNumber ?: "?"}" else safe.name) },
                        supportingContent = {
                            Text(when (item.kind) {
                                dev.nitrate.core.model.ItemKind.SERIES -> "Show: its next episodes"
                                dev.nitrate.core.model.ItemKind.BOX_SET -> "Collection"
                                dev.nitrate.core.model.ItemKind.EPISODE -> "Episode"
                                else -> listOfNotNull("Film", item.productionYear?.toString()).joinToString(" · ")
                            })
                        },
                    )
                }
            }
        }
    }
}

/** "Pack for a trip" from a details page: which trip (not over yet) to add [item] to, or plan a new one. */
@Composable
fun PackForTripSheet(actions: AppActions, item: MediaItem, onDismiss: () -> Unit) {
    val trips = actions.container.trips
    val now = remember { dev.nitrate.android.nowMs() }
    val list by trips.trips().collectAsState(emptyList())
    ChoiceSheet("Pack ${spoilerSafe(item).displayTitle} for…", onDismiss) { choice ->
        list.filter { it.returnMs > now }.forEach { trip ->
            choice(trip.name, tripDates(trip)) { actions.scope.launch { runCatching { trips.addTo(trip, item) } } }
        }
        choice("Plan a new trip", "Then add it with Add…") { actions.nav.navigate(TripRoute()) }
    }
}
