package dev.nitrate.mobile

import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.nitrate.android.AppContainer
import dev.nitrate.testing.FakeServer
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.GestureFeedbackView
import dev.nitrate.mobile.ui.ConnectScreen
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadRecord
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.downloads.DownloadsData
import dev.nitrate.core.downloads.MemoryDownloadStore
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.UserState
import dev.nitrate.mobile.ui.DetailsScreen
import dev.nitrate.mobile.ui.SettingsTab
import dev.nitrate.mobile.ui.DownloadsScreen
import dev.nitrate.mobile.ui.SearchScreen
import dev.nitrate.mobile.ui.ManageItemScreen
import dev.nitrate.mobile.ui.ServerAdminScreen
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.pages.AccentMode
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.pages.PageLayout
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.RowEntry
import dev.nitrate.core.pages.RowSource
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.mobile.ui.PageEditor
import dev.nitrate.mobile.ui.ProvideDisplayPrefs
import dev.nitrate.mobile.ui.RowBuilderDialog
import dev.nitrate.mobile.ui.SettingsScreen
import dev.nitrate.mobile.ui.SettingsPageScreen
import dev.nitrate.mobile.ui.SettingsPage
import dev.nitrate.mobile.ui.PlayerLayout
import dev.nitrate.mobile.ui.WritingScreen
import dev.nitrate.mobile.ui.TransferDialog
import dev.nitrate.mobile.ui.GestureFeedback
import dev.nitrate.mobile.ui.GestureFeedbackView
import dev.nitrate.mobile.ui.TrickplayPreview
import dev.nitrate.mobile.ui.UpNextCard
import dev.nitrate.mobile.ui.CreditSceneBanner
import dev.nitrate.core.model.TrickplayInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import kotlinx.coroutines.Dispatchers
import dev.nitrate.mobile.ui.LoginScreen
import dev.nitrate.mobile.ui.MainScreen
import dev.nitrate.mobile.ui.NitrateTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.longClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main phone/tablet screens against [FakeServer] so layouts can be checked without a
 * device. PNGs land in build/outputs/roborazzi. Images are blank placeholders (no network).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(
        name: String,
        downloads: DownloadsData = DownloadsData(),
        setup: suspend (AppContainer) -> Unit = {},
        /** Done on the settled screen before the picture (a scroll, say). */
        act: () -> Unit = {},
        /** Picture every window (a sheet or dialog [act] left open), not only the page's. */
        wholeScreen: Boolean = false,
        content: @Composable (AppActions) -> Unit,
    ) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer(), downloadStoreOverride = MemoryDownloadStore(downloads))
        kotlinx.coroutines.runBlocking {
            // Stored settings outlive a test in the same process: start each one clean.
            container.displaySettings.import("user-1", emptyMap())
            setup(container)
        }
        // Load artwork without background threads so it's on screen when the picture is taken.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity)
                .coroutineContext(Dispatchers.Unconfined)
                .fetcherCoroutineContext(Dispatchers.Unconfined)
                .decoderCoroutineContext(Dispatchers.Unconfined)
                // Robolectric has no ImageDecoder; BitmapFactory works.
                .imageDecoderEnabled(false)
                .build(),
        )
        compose.setContent {
            NitrateTheme {
                // Same root as the app: background and text colours come from this Surface.
                ProvideDisplayPrefs(container, "user-1") {
                    NitrateTheme {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val nav = rememberNavController()
                            content(AppActions(container, nav, play = { _, _ -> }))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        // Artwork colours are worked out off the main thread; let them land, then settle animations.
        Thread.sleep(400)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        if (wholeScreen) com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        else compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        // Everything that can be tapped says what it is.
        val silent = dev.nitrate.testing.A11y.silent(compose)
        check(silent.isEmpty()) { "$name: a screen reader would say nothing for:\n" + silent.joinToString("\n") }
        // Leaving any group stops its clock-sync loop, which would otherwise keep the next test busy.
        compose.runOnUiThread { container.syncPlay.handle(dev.nitrate.core.events.ServerEvent.SyncPlayGroupUpdate("", dev.nitrate.core.syncplay.SyncPlayUpdate.Left)) }
        shot = container
    }

    /** The container of the last [shoot], to check what it stored. */
    private var shot: AppContainer? = null

    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun swipeLevel() = shoot("phone-swipe-level") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF203040))) {
            GestureFeedbackView(dev.nitrate.mobile.ui.GestureFeedback.Level(brightness = false, level = 0.73f))
        }
    }

    @Test fun mediaInfo() = shoot("phone-media-info") {
        val streams = listOf(
            dev.nitrate.core.model.MediaStream(0, dev.nitrate.core.model.StreamKind.VIDEO, codec = "hevc", profile = "Main 10", width = 3840, height = 2160, bitDepth = 10, rangeType = "HDR10"),
            dev.nitrate.core.model.MediaStream(1, dev.nitrate.core.model.StreamKind.AUDIO, codec = "eac3", language = "jpn", title = "Japanese", channels = 6, channelLayout = "5.1"),
            dev.nitrate.core.model.MediaStream(2, dev.nitrate.core.model.StreamKind.SUBTITLE, codec = "ass", language = "eng", title = "Full", isDefault = true),
        )
        val source = dev.nitrate.core.model.MediaSource("s", "mkv", 24_000_000, streams, size = 6_100_000_000, defaultAudioIndex = 1, defaultSubtitleIndex = 2, defaultsKnown = true)
        dev.nitrate.mobile.ui.MediaInfoDialog(dev.nitrate.core.playback.MediaInfo.render(dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE), source)) {}
    }

    @Test fun downloadTracks() = shoot("phone-download-tracks", setup = { c ->
        c.downloads.updateSettings { it.copy(quality = dev.nitrate.core.downloads.DownloadQuality.MEDIUM) }
        val film = dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE)
        c.pickTrack(film, c.trackSetup(film), dev.nitrate.core.model.StreamKind.SUBTITLE, 3)
    }) { dev.nitrate.mobile.ui.DownloadDialog(it, listOf(dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE, runtimeMs = 6_960_000)), "Nitrate") {} }

    @Test fun serverSettings() = shoot("phone-server-settings") { dev.nitrate.mobile.ui.ServerSettingsDialog(it) {} }

    @Test fun connect() = shoot("phone-connect") { ConnectScreen(it) { _, _ -> } }

    @Test fun chat() = shoot("phone-chat") { actions ->
        val chat = androidx.compose.runtime.remember {
            dev.nitrate.core.companion.GroupChat(actions.server, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) { "user-1" }.also { it.follow("g1") }
        }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) {
            androidx.compose.material3.Text("Chat · Movie night", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            dev.nitrate.mobile.ui.ChatContent(chat, positionMs = { 0L })
        }
    }

    /** The group panel where only the chat is involved (an older server without chat histories): the group's notes up to 16:40 (not the one at 25:00), reactions. */
    @Test fun groupChat() = shoot("phone-group-chat", setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "notes"
    }) { actions ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { actions.container.timelineChat(scope, live = false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { chat.start("s1e2", 1_000_000) { true } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            dev.nitrate.mobile.ui.GroupSheet(null, chat, "The Night Reel", positionMs = { 1_000_000 }, onTurnChat = { chat.turn(it, 1_000_000) }, onDismiss = {})
        }
    }

    /**
     * A playback's chat with chat histories (and favourites), started from a group's card: the panel
     * on a new chat (nothing earlier loaded), with the title's quick replies.
     */
    private fun chatsPanel(name: String, page: String? = null) = shoot(name, setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += listOf("notes", "chats", "sessions", "chatfavourites")
        c.refreshCompanionForTests()
    }) { actions ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { actions.container.timelineChat(scope, live = false) }
        val picks = androidx.compose.runtime.produceState(dev.nitrate.core.companion.QuickPicks.PLAIN) {
            value = actions.container.quickPicks(actions.server.getItem("s1e2"))
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            chat.start("s1e2", 1_000_000) { true }
            chat.focus(listOf("g1"))
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            dev.nitrate.mobile.ui.GroupSheet(
                withChip(actions, with = dev.nitrate.core.partners.WatchingWith.Group("g1")), chat, positionMs = { 1_000_000 }, onDismiss = {}, initialPage = page,
                picks = picks.value,
            )
        }
    }

    /**
     * The group panel: who it counts for ("Counting for you and Will · Change", "Watching on your
     * own"), the chat (a new one) with the title's own emojis first and its characters' names and
     * short lines under them, Start a Group Watch Session.
     */
    @Test fun groupPanel() = chatsPanel("phone-group-panel")

    /** Chats: Group chat on or off, New chat, and this group's earlier chats newest first (dated 2026/11/22), one building on another, each with a heart (Tuesday's a favourite). */
    @Test fun groupChats() = chatsPanel("phone-group-chats", page = "CHATS")

    /** An episode of The Night Reel, shared with two watch groups (Will; Frank, Tommy and Jane). */
    private fun withChip(
        actions: AppActions,
        with: dev.nitrate.core.partners.WatchingWith? = null,
        room: dev.nitrate.core.partners.SameRoom? = null,
        inSession: Boolean = false,
    ): dev.nitrate.core.partners.WithChip {
        val episode = dev.nitrate.core.model.MediaItem("s1e2", "Episode 2", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel")
        val fake = actions.server as dev.nitrate.testing.FakeServer
        val session = if (inSession) fake.sampleSession(joined = true, startedByMe = true) else null
        return dev.nitrate.core.partners.WithChip.of(episode, fake.partnerInfo, emptyMap(), session, "Me", with, room = room)!!
    }

    /** The player's group button (avatars and the group's name), and "Counting for" as it starts when more than one group fits. */
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun withChipShown() = shoot("phone-group-button") { actions ->
        val chip = withChip(actions)
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            dev.nitrate.mobile.ui.GroupButton(withChip(actions, with = dev.nitrate.core.partners.WatchingWith.Group("g2")), chatAvailable = true, easy = false, onOpen = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(12.dp))
            dev.nitrate.mobile.ui.CountingBanner(chip, easy = false, onOpen = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 72.dp))
        }
    }

    /** Change, from the panel's top line: each group on the title, No group, and the same room. */
    @Test fun withSheet() = shoot("phone-group-change") { actions ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            dev.nitrate.mobile.ui.GroupSheet(withChip(actions), null, onDismiss = {}, initialPage = "COUNT")
        }
    }

    /** "We're in the same room": who's here of the group it counts for. */
    @Test fun withRoom() = shoot("phone-with-room") { actions ->
        val chip = withChip(actions, room = dev.nitrate.core.partners.SameRoom("g2", listOf("user-5")))
        check(chip.label == "In the room with Tommy") { chip.label }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            dev.nitrate.mobile.ui.GroupSheet(chip, null, initialPage = "ROOM", onDismiss = {})
        }
    }

    /** In a Group Watch Session the user started: the panel's top line says so; leaving it, ending it for everyone. */
    @Test fun withSession() = shoot("phone-with-session") { actions ->
        val chip = withChip(actions, inSession = true)
        check(chip.label == "In a Group Watch Session with Will") { chip.label }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            dev.nitrate.mobile.ui.GroupSheet(chip, null, onDismiss = {})
        }
    }

    /** The group's notes popping up over the video as they're reached. */
    @Test fun groupChatPopups() = shoot("phone-group-chat-popups", setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "notes"
    }) { actions ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val chat = androidx.compose.runtime.remember { actions.container.timelineChat(scope, live = false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            chat.start("s1e2", 0) { true }
            kotlinx.coroutines.delay(100)
            for (s in 0..8L) chat.at(s * 1_000)
        }
        Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
            dev.nitrate.mobile.ui.NoteToasts(chat.popups, Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 16.dp))
        }
    }

    /** A script shared by sam, who is writing in it now: the header names them and their cursor is marked. */
    @Test fun sharedScript() = shoot("phone-shared-script", setup = { c ->
        c.writing.account = c.server.currentAccountKey
        c.refreshCompanionForTests()
        c.writing.docs.value.forEach { c.writing.delete(it.id, backup = false) }
        c.sharedWriting.open("shared-1")
    }) { actions -> WritingScreen(actions, onBack = {}) }

    private fun storyContext() = dev.nitrate.android.writing.StoryContext(
        text = dev.nitrate.testing.SampleStory.text, scenes = dev.nitrate.testing.SampleStory.scenes, story = dev.nitrate.testing.SampleStory.story,
        selfId = "user-1", selfName = "joe", people = emptyList(), changeStory = {}, applyText = {}, openInScript = {}, history = null,
    )

    @Test fun storyScenes() = shoot("phone-story-scenes") { dev.nitrate.mobile.ui.ScenesPanel(storyContext()) }
    @Test fun storyLists() = shoot("phone-story-lists") { dev.nitrate.mobile.ui.ListsPanel(storyContext()) }
    @Test fun storyMap() = shoot("phone-story-map") { androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) { dev.nitrate.mobile.ui.MapPanel(storyContext(), Modifier.weight(1f)) } }

    @Test fun libraryHealth() = shoot("phone-library-health", setup = { c -> c.checkLibraryHealth(null) }) { dev.nitrate.mobile.ui.LibraryHealthScreen(it) }

    @Test fun guide() = shoot("phone-guide") { dev.nitrate.mobile.ui.GuideScreen(admin = true) }

    @Test fun householdDevices() = shoot("phone-household-devices") { dev.nitrate.mobile.ui.HouseholdDevicesScreen(it) }

    @Test fun playbackTrail() = shoot("phone-playback-trail") {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) { dev.nitrate.mobile.ui.TrailView(dev.nitrate.testing.sampleTrail()) }
    }

    @Test fun playbackOverview() = shoot("phone-playback-overview") { dev.nitrate.mobile.ui.PlaybackOverviewScreen(it) }

    @Test fun channels() = shoot("phone-channels") { dev.nitrate.mobile.ui.ChannelsScreen(it) }

    @Test fun channelEditor() = shoot("phone-channel-editor") { dev.nitrate.mobile.ui.ChannelEditorScreen(it, "c1") }

    /** A watch party's reminder as the in-app sheet shows it, and simply in easy mode. */
    @Test fun watchParty() = shoot("phone-watch-party") { actions ->
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.nitrate.mobile.ui.WatchPartyChoices((actions.server as dev.nitrate.testing.FakeServer).samplePartyPrompt(), onDismiss = {}, onChoose = {})
        }
    }

    @Test fun watchPartyEasy() = shoot("phone-watch-party-easy") { actions ->
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.nitrate.mobile.ui.WatchPartyChoices((actions.server as dev.nitrate.testing.FakeServer).samplePartyPrompt(easy = true), onDismiss = {}, onChoose = {})
        }
    }

    /** Someone's schedule change as the in-app sheet asks it (Yes, No, Later), and the note when it passed (OK). */
    @Test fun scheduleAsk() = shoot("phone-schedule-ask") { actions ->
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.nitrate.mobile.ui.ScheduleChangeChoices((actions.server as dev.nitrate.testing.FakeServer).sampleSchedulePrompt(), onLater = {}, onAnswer = {})
        }
    }

    @Test fun scheduleDone() = shoot("phone-schedule-done") { actions ->
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.nitrate.mobile.ui.ScheduleChangeChoices((actions.server as dev.nitrate.testing.FakeServer).sampleSchedulePrompt(done = true), onLater = {}, onAnswer = {})
        }
    }

    /** An invite into a group with a full schedule: labelled lines, the schedule as points, Decline apart from Not now. */
    @Test fun partnerRequestScheduled() = shoot("phone-partner-request-scheduled", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val r = (actions.container.server as dev.nitrate.testing.FakeServer).partnerInfo.incoming.first()
        dev.nitrate.mobile.ui.InviteAnswerDialog(actions, r.copy(schedule = dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, day = 4, hour = 19, together = true, locked = true,
                party = dev.nitrate.core.partners.PartyTime(listOf(5), 21, 0)))) {}
    }

    /** Changing a two-title group's schedule: every pace's choices, the time, parts per release, the first part, Sequential in order. */
    @Test fun scheduleChange() = shoot("phone-schedule-change", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val g = (actions.container.server as dev.nitrate.testing.FakeServer).partnerInfo.groups.first().let {
            it.copy(
                titleList = listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel"), dev.nitrate.core.partners.GroupTitle("m1", "Nitrate")),
                schedule = dev.nitrate.core.partners.GroupSchedule(
                    dev.nitrate.core.partners.GroupSchedule.WEEKLY, weekdays = listOf(1, 4), hour = 20, minute = 30, perRelease = 2, sequential = true,
                ),
            )
        }
        dev.nitrate.mobile.ui.ScheduleChangeDialog(actions, g) {}
    }

    @Test fun watchPartners() = shoot("phone-watch-partners", setup = { c -> c.refreshCompanionForTests() }) { dev.nitrate.mobile.ui.WatchPartnersScreen(it) }

    /** The guided setup's first step: titles picked from what was watched lately, Next, and Cancel (Back on the first page). */
    @Test fun groupSetupWhat() = shoot("phone-group-setup-what", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        dev.nitrate.mobile.ui.GroupSetupPages(actions, dev.nitrate.core.partners.GroupSetup(titles = listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel")))) {}
    }

    /** Who: several people ticked (and names typed), all invited into one group. */
    @Test fun groupSetupWho() = shoot("phone-group-setup-who", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val start = dev.nitrate.core.partners.GroupSetup(
            dev.nitrate.core.partners.GroupSetup.Step.WHO,
            listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel")), people = listOf("user-2", "user-3"), typed = "suzy",
        )
        dev.nitrate.mobile.ui.GroupSetupPages(actions, start) {}
    }

    /** Its last step: the Together switch, what it means, what's chosen, and Send the invite. */
    @Test fun groupSetupTogether() = shoot("phone-group-setup-together", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val start = dev.nitrate.core.partners.GroupSetup(
            dev.nitrate.core.partners.GroupSetup.Step.TOGETHER,
            listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel"), dev.nitrate.core.partners.GroupTitle("m1", "Nitrate")), people = listOf("user-2"),
            schedule = dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, together = true, party = dev.nitrate.core.partners.PartyTime(listOf(5), 21)),
        )
        dev.nitrate.mobile.ui.GroupSetupPages(actions, start) {}
    }

    /** The last step with a paced schedule and Together on: "If someone plays ahead", Don't allow chosen, and the summary saying locked. */
    @Test fun groupSetupPlayAhead() = shoot("phone-group-setup-play-ahead", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val start = dev.nitrate.core.partners.GroupSetup(
            dev.nitrate.core.partners.GroupSetup.Step.TOGETHER,
            listOf(dev.nitrate.core.partners.GroupTitle("series-1", "The Night Reel")), people = listOf("user-2"),
            schedule = dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, together = true, locked = true),
        )
        dev.nitrate.mobile.ui.GroupSetupPages(actions, start) {}
    }.also {
        check(compose.onAllNodes(androidx.compose.ui.test.hasText(dev.nitrate.core.partners.GroupSchedule.PLAY_AHEAD)).fetchSemanticsNodes().isNotEmpty()) { "the play-ahead line" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Thursdays 7pm, together, locked", substring = true)).fetchSemanticsNodes().isNotEmpty()) { "the summary says locked" }
    }

    /** A group whose schedule doesn't allow playing ahead: "Opens Thursday 7pm", why, and only OK (no Watch for myself). */
    @Test fun playAheadRefused() {
        var ask: dev.nitrate.core.plugins.GuardDecision.Ask? = null
        shoot("phone-play-ahead-refused", setup = { c ->
            (c.server as dev.nitrate.testing.FakeServer).partnerInfo = dev.nitrate.core.partners.PartnersInfo(
                listOf(
                    dev.nitrate.core.partners.WatchGroup(
                        "g1", "series-1", "The Night Reel", listOf(dev.nitrate.core.writing.UserRef("user-2", "Will")), 0,
                        dev.nitrate.core.partners.GroupSchedule(dev.nitrate.core.partners.GroupSchedule.WEEKLY, day = 4, hour = 19, locked = true),
                        lockedFromId = "s2e1", opensDay = 4,
                    ),
                ),
                emptyList(), emptyList(),
            )
            ask = c.watchPartners.beforePlay(c.server.getItem("s2e1"), c.plugins.host, null) as dev.nitrate.core.plugins.GuardDecision.Ask
        }) { dev.nitrate.mobile.ui.GuardDialog(ask!!) {} }
        check(ask!!.title == "Opens Thursday 7pm") { ask!!.title }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("OK")).fetchSemanticsNodes().isNotEmpty()) { "OK" }
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Watch for myself")).fetchSemanticsNodes().isEmpty()) { "nothing to watch for oneself" }
    }

    /** A long press on a group's card: that group's choices (mark for the group, same room, add a title, …). */
    @Test fun togetherChoices() = shoot("phone-together-choices", setup = { c -> c.refreshCompanionForTests() }) { actions ->
        val info = (actions.server as dev.nitrate.testing.FakeServer).partnerInfo
        val card = dev.nitrate.core.model.MediaItem("s1e2", "Episode 2", dev.nitrate.core.model.ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 1, indexNumber = 2, watchGroupId = "g1")
        val choices = dev.nitrate.core.partners.TogetherRow.choices(card, info, emptyMap(), emptyList(), canWatchNow = true, ownSpoilers = emptySet())
        check(choices.any { it is dev.nitrate.core.partners.TogetherChoice.InTheRoom }) { choices.map { it.label }.toString() }
        dev.nitrate.mobile.ui.TogetherChoicesList(card, info, emptyMap(), choices) {}
    }

    /** After a playback from a group's card that's next in another group too: "Just this group / All groups where it's next". */
    @Test fun countingPrompt() = shoot("phone-counting-prompt") { actions ->
        val info = (actions.server as dev.nitrate.testing.FakeServer).partnerInfo
        val counting = dev.nitrate.core.partners.Counting(dev.nitrate.core.partners.PlayOrigin.GroupCard("g1"), listOf(info.groups[0]), listOf(info.groups[1]))
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.nitrate.mobile.ui.CountingChoices(dev.nitrate.core.partners.CountAsk(listOf("s1e2"), "S1:E2 · Episode 2", counting)) {}
        }
    }

    @Test fun seriesWithPartner() = shoot("phone-series-partner", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true))
        c.refreshCompanionForTests()
    }) { DetailsScreen(it, "series-1", null) }

    @Test fun recap() = shoot("phone-recap", setup = { c -> c.pendingRecap = (c.server as dev.nitrate.testing.FakeServer).sampleRecap() }) { dev.nitrate.mobile.ui.RecapScreen(it) }

    @Test fun licenses() = shoot("phone-licenses") { dev.nitrate.mobile.ui.LicensesScreen() }

    @Test fun serverAddresses() = shoot("phone-servers") { dev.nitrate.mobile.ui.ServersScreen(it) }

    @Test fun login() = shoot("phone-login") { LoginScreen(it, "http://192.168.1.20:8096", "Home") {} }

    @Test fun home() = shoot("phone-home") { MainScreen(it) {} }

    @Test fun details() = shoot("phone-series") { DetailsScreen(it, "series-1", null) }

    /**
     * Every row style shows an episode not seen yet spoiler-safe: Home's rows, all in one style,
     * "Hide spoilers" on, and no unwatched episode's name anywhere on screen.
     */
    private fun spoilerRow(style: dev.nitrate.core.rows.RowStyle) {
        val hidden = mutableListOf<String>()
        shoot("phone-spoiler-row-${style.name.lowercase()}", setup = { c ->
            c.displaySettings.savePrefs("user-1", dev.nitrate.core.pages.DisplayPrefs(hideSpoilers = true))
            c.server.homeRows().forEach { row ->
                c.displaySettings.save("user-1", row, dev.nitrate.core.rows.RowDisplay(style))
                hidden += row.items.filter { it.kind == dev.nitrate.core.model.ItemKind.EPISODE && !it.user.played }.map { it.name }
            }
        }) { MainScreen(it) {} }
        check(hidden.isNotEmpty()) { "the sample Home has no unwatched episode to hide" }
        hidden.distinct().forEach { name ->
            compose.onAllNodes(androidx.compose.ui.test.hasText(name, substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test fun spoilerRowPosters() = spoilerRow(dev.nitrate.core.rows.RowStyle.POSTER)
    @Test fun spoilerRowArtwork() = spoilerRow(dev.nitrate.core.rows.RowStyle.WIDE)
    @Test fun spoilerRowShowcase() = spoilerRow(dev.nitrate.core.rows.RowStyle.SHOWCASE)
    @Test fun spoilerRowFullShowcase() = spoilerRow(dev.nitrate.core.rows.RowStyle.FULL_SHOWCASE)
    @Test fun spoilerRowFilmStrip() = spoilerRow(dev.nitrate.core.rows.RowStyle.FILM_STRIP)
    @Test fun spoilerRowLightning() = spoilerRow(dev.nitrate.core.rows.RowStyle.LIGHTNING)
    @Test fun spoilerRowCressida() = spoilerRow(dev.nitrate.core.rows.RowStyle.CRESSIDA)

    @Test fun movie() = shoot("phone-movie") { DetailsScreen(it, "m1", null) }

    /** A season's page: "The Night Reel ›", the season's name, its episode count, Play its next episode. */
    @Test fun season() {
        shoot("phone-season") { DetailsScreen(it, "series-1", "season-2") }
        compose.onAllNodesWithText("Season 2").onFirst().assertExists()
        compose.onAllNodesWithText("6 episodes").onFirst().assertExists()
    }

    /** A tablet's details page: the picture, title and actions on the left, the season's episodes beside them. */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletEpisode() {
        shoot("tablet-episode") { DetailsScreen(it, "s1e2", null) }
        compose.onAllNodesWithText("This one").onFirst().assertExists()
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletMovie() = shoot("tablet-movie") { DetailsScreen(it, "m1", null) }

    /** A tablet's Settings: the sections on the left, the chosen one beside them. */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletSettings() = shoot("tablet-settings") { SettingsTab(it, onSignedOut = {}) }

    /** What the details page shows while it loads. */
    @Test fun detailsLoading() = shoot("phone-details-loading") { dev.nitrate.mobile.ui.DetailsPlaceholder() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletDetailsLoading() = shoot("tablet-details-loading") { dev.nitrate.mobile.ui.DetailsPlaceholder() }

    @Test fun playlistLoading() = shoot("phone-playlist-loading") { dev.nitrate.mobile.ui.ListPlaceholder() }

    /** An episode's page: where it belongs (show › season), its own name, and the rest of its season. */
    /**
     * An episode entered from Will's group: its own Watched and, beside it, the group's mark
     * ("Not watched with Will"), which marks it in the group's record only; the episode list
     * shows the group's marks too.
     */
    @Test fun episodeFromAGroup() {
        lateinit var server: dev.nitrate.testing.FakeServer
        shoot("phone-episode-group", setup = { c ->
            server = c.server as dev.nitrate.testing.FakeServer
            server.partnerInfo = server.partnerInfo.copy(groups = server.partnerInfo.groups.map { if (it.id == "g1") it.copy(ownRecord = setOf("s1e1")) else it })
            c.refreshCompanionForTests()
        }) { DetailsScreen(it.fromGroup("g1"), "s1e2", null) }
        compose.onAllNodesWithText("Plays here count for the group", substring = true).onFirst().assertExists()
        org.junit.Assert.assertTrue("only the group it was entered from", compose.onAllNodesWithText("Frank", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Not watched with Will")).onFirst().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertTrue(server.groupMarks.toString(), "[g1] [s1e2]" in server.groupMarks)
        // Down the page, the episodes the group has carry its mark.
        compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasContentDescription("Watched with Will"))
    }

    /** The server refuses Watched: the page says why, and the tick stays as the server has it (never a mark that didn't happen). */
    @Test fun watchedRefusedSaysWhy() {
        shoot("phone-watched-refused", setup = { c -> (c.server as dev.nitrate.testing.FakeServer).setPlayedRefusal = "the server said 403 Forbidden" }) { DetailsScreen(it, "m2", null) }
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Watched")).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Couldn't mark it watched: the server said 403 Forbidden").onFirst().assertExists()
    }

    @Test fun episode() {
        shoot("phone-episode") { DetailsScreen(it, "s1e2", null) }
        compose.onAllNodesWithText("In this season").onFirst().assertExists()
    }

    /** The details page's More: everything the action row leaves out. */
    @Test fun detailsMore() {
        shoot("phone-movie-actions") { DetailsScreen(it, "m1", null) }
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("How it plays").onFirst().assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/phone-details-more.png")
    }

    @Test fun search() = shoot("phone-search") { SearchScreen(it, initialQuery = "re", autoFocus = false) }

    @Test
    fun downloads() = shoot("phone-downloads", sampleDownloads()) { DownloadsScreen(it) }

    private fun sampleDownloads(): DownloadsData {
        val server = FakeServer()
        fun record(item: MediaItem, quality: DownloadQuality, state: DownloadState, bytes: Long, total: Long, error: String? = null) =
            DownloadRecord(item.id, server.serverUrl, server.userId, item, quality, state = state, bytesDownloaded = bytes, totalBytes = total, error = error, createdAtMs = bytes)
        val runtime = 42 * 60_000L
        fun episode(n: Int, watched: Boolean = false, positionMs: Long = 0) = MediaItem(
            "s1e$n", "Episode title $n", ItemKind.EPISODE, seriesName = "The Night Reel", seasonNumber = 1, indexNumber = n, runtimeMs = runtime,
            user = UserState(played = watched, positionMs = positionMs),
        )
        return DownloadsData(
            records = listOf(
                record(episode(4), DownloadQuality.MEDIUM, DownloadState.DOWNLOADING, 412_000_000, 1_310_000_000),
                record(episode(5), DownloadQuality.MEDIUM, DownloadState.FAILED, 0, 1_310_000_000, "Your account isn't allowed to download from this server"),
                record(MediaItem("m1", "Nitrate", ItemKind.MOVIE, runtimeMs = 7_000_000), DownloadQuality.ORIGINAL, DownloadState.COMPLETED, 6_400_000_000, 6_400_000_000),
                record(episode(1, watched = true), DownloadQuality.LOW, DownloadState.COMPLETED, 480_000_000, 480_000_000),
                record(episode(2, positionMs = 754_000).let { it }, DownloadQuality.LOW, DownloadState.COMPLETED, 470_000_000, 470_000_000).copy(pendingSync = true),
            ),
        )
    }

    /** The player's overlays over a still: seek feedback, scrubbing preview and Up Next. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun playerOverlays() = shoot("phone-player-overlays") { actions ->
        val server = actions.server
        val next = MediaItem("s2e5", "Episode title 5", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 5)
        val info = TrickplayInfo(320, 180, 2, 2, 4, 10_000)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            GestureFeedbackView(GestureFeedback.Seek(-20_000))
            TrickplayPreview(server, "s2e4", info, positionMs = 21_000, x = 900f, barTop = 1000f)
            UpNextCard(next, server, seconds = 10, onPlay = {}, onHide = {})
        }
    }

    /** "Play on…" from a details page: the user's TVs, the playing one first. */
    @Test
    fun playOnDialog() = shoot("phone-play-on") { actions ->
        dev.nitrate.mobile.ui.PlayOnDialog(actions, MediaItem("m2", "Silver Screen", ItemKind.MOVIE), 0, onDismiss = {})
    }

    /** Something playing on the TV while browsing: the bar above the navigation pill. */
    @Test
    fun playingElsewhere() = shoot("phone-playing-elsewhere") { actions ->
        Box(Modifier.fillMaxSize()) {
            dev.nitrate.mobile.ui.PlayingElsewhereBar(actions, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp))
        }
    }

    /** The next film in a collection: offered at the credits, no countdown. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun nextInCollection() = shoot("phone-next-in-collection") { actions ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            UpNextCard(MediaItem("m3", "Gate Weave", ItemKind.MOVIE, productionYear = 2024), actions.server, seconds = null, onPlay = {}, onHide = {})
        }
    }

    /** Skipped to the scene after the credits, with the way back. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun creditScene() = shoot("phone-credit-scene") { actions ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(actions.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            CreditSceneBanner(dev.nitrate.core.playback.CreditSkipper.Action.Skip(450_000, 360_000, afterCredits = true), onAct = {}, onDone = {})
        }
    }

    /** "Still watching?" after episodes played on with nothing pressed. */
    @Test
    fun stillWatching() = shoot("phone-still-watching") { actions ->
        dev.nitrate.mobile.ui.StillWatchingPrompt(
            MediaItem("s2e6", "Episode title 6", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonNumber = 2, indexNumber = 6),
            dev.nitrate.core.playback.StillWatching.Rewind(dev.nitrate.core.playback.StillWatching.Spot("s2e4", 1_410_000, 0, false), listOf("s2e5")),
            {}, {}, {}, {},
        )
    }

    /** Upmix and the speakers of the output in use. */
    @Test fun speakers() = shoot("phone-speakers") { _ ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            dev.nitrate.mobile.ui.SpeakersSettings(
                dev.nitrate.core.playback.PlaybackSettings(
                    upmixStereo = true,
                    speakerLayouts = mapOf("HDMI:DENON-AVR" to dev.nitrate.core.playback.SpeakerLayout.FIVE_ONE, "HDMI:Living room soundbar" to dev.nitrate.core.playback.SpeakerLayout.THREE_ONE),
                ),
                dev.nitrate.core.playback.SoundOutput(dev.nitrate.core.playback.OutputKind.HDMI, "DENON-AVR", 8),
            ) {}
        }
    }

    /** The watch diary. */
    @Test fun diary() = shoot("phone-diary") { actions -> dev.nitrate.mobile.ui.DiaryScreen(actions) }

    /** A video playlist's page: how much is watched, Continue from where it was left, the entry you're up to marked NEXT. */
    @Test fun videoPlaylist() {
        shoot("phone-playlist") { actions -> dev.nitrate.mobile.ui.PlaylistScreen(actions, "p1") }
        compose.onAllNodesWithText("Rainy day", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("of 6 watched", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("NEXT").onFirst().assertExists()
        // Each video entry's ⋮ opens what a long press does (mark watched up to here…).
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More for", substring = true)).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Mark watched up to here").onFirst().assertExists()
    }

    /** Search finds what people say too. */
    @Test fun saidIn() = shoot("phone-search-said-in", setup = { c -> c.refreshCompanionForTests() }) { actions -> dev.nitrate.mobile.ui.SearchScreen(actions, initialQuery = "need a bigger boat", autoFocus = false) }

    /** Easy mode: bigger everything, and only Home, Search, Library and Settings in the navigation. */
    @Test fun easyMode() = shoot("phone-easy-mode", setup = { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(easyMode = true)) }) { actions ->
        dev.nitrate.mobile.ui.MainScreen(actions, startOnLibrary = true, onSignedOut = {})
    }

    /** "Your year": totals, month by month, top shows, genres, rewatches and artists. */
    @Test
    @Config(qualifiers = "w411dp-h1600dp-night-xxhdpi")
    fun stats() = shoot("phone-stats") { actions -> dev.nitrate.mobile.ui.StatsScreen(actions) }

    private suspend fun listeningTo(c: AppContainer, playing: Boolean = true) = dev.nitrate.testing.FakeListening(
        dev.nitrate.core.music.ListenState(c.server.albumTracks("al1"), index = 1, playing = playing, durationMs = 197_000, shuffle = true, repeat = dev.nitrate.core.music.Repeat.ALL),
        position = 72_000,
    )

    /** The song playing, above the nav pill: cover, title, artist, play/pause, next and how far through. */
    @Test
    fun miniPlayer() {
        lateinit var player: dev.nitrate.testing.FakeListening
        shoot("phone-mini-player", setup = { player = listeningTo(it) }) { actions ->
            Box(Modifier.fillMaxSize()) {
                dev.nitrate.mobile.ui.MiniPlayer(actions, Modifier.align(Alignment.BottomCenter).padding(12.dp), player = player)
            }
        }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitForIdle()
        check("toggle" in player.asked) { player.asked.toString() }
    }

    /** Casting: the device, what it plays and where, with jumps, pause and stop casting. */
    @Test
    fun castBar() {
        val status = dev.nitrate.core.cast.CastStatus("m1", "Nitrate", positionMs = 754_000, durationMs = 6_960_000, paused = false)
        val cast = FakeCast(status = status, atMs = Long.MAX_VALUE)
        shoot("phone-cast-bar") { actions ->
            Box(Modifier.fillMaxSize()) {
                dev.nitrate.mobile.ui.CastBar(actions, cast, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithText("+10").performClick()
        compose.waitForIdle()
        check(cast.sent.first() == "pause" && cast.sent[1].startsWith("seek ")) { cast.sent.toString() }
        compose.onNodeWithContentDescription("Stop casting").performClick()
        compose.waitForIdle()
        check(cast.state.value.connected == null)
    }

    /** Now Playing: the cover, the song, a seek bar, the controls and what's up next. */
    @Test
    fun nowPlaying() {
        lateinit var player: dev.nitrate.testing.FakeListening
        shoot("phone-now-playing", setup = { player = listeningTo(it) }) { actions ->
            dev.nitrate.mobile.ui.NowPlayingScreen(actions, onBack = {}, player = player)
        }
        compose.onNodeWithContentDescription("Next song").performClick()
        compose.onNodeWithText("Lamp House").performClick()
        compose.waitForIdle()
        check(player.asked.containsAll(listOf("next", "skip 2"))) { player.asked.toString() }
    }

    /** Now Playing for an audiobook: skips, chapters, speed and the sleep timer instead of shuffle and up next. */
    @Test
    fun nowPlayingBook() {
        val book = MediaItem(
            "ab1", "The Long Reel", ItemKind.AUDIOBOOK, runtimeMs = 9_000_000, artists = listOf("Ada Lumen"),
            chapters = listOf("Prologue", "The Booth", "Changeover", "House Lights").mapIndexed { i, n -> dev.nitrate.core.model.Chapter(i * 1_800_000L, n) },
        )
        val player = dev.nitrate.testing.FakeListening(dev.nitrate.core.music.ListenState(listOf(book), 0, playing = true, durationMs = 9_000_000, speed = 1.25f), position = 2_400_000)
        val marks = dev.nitrate.testing.FakeBookmarks(mapOf("ab1" to listOf(dev.nitrate.core.music.Bookmark("m1", 4_300_000, "The projectionist's confession"))))
        shoot("phone-now-playing-book") { actions -> dev.nitrate.mobile.ui.NowPlayingScreen(actions, onBack = {}, player = player, bookmarkStore = marks) }
        // One tap marks where it's playing.
        compose.onNodeWithText("Bookmark").performClick()
        compose.waitForIdle()
        check(marks.all.value["ab1"]!!.map { it.positionMs } == listOf(2_400_000L, 4_300_000L)) { marks.all.value.toString() }
        compose.onNodeWithText("+30 s").performClick()
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Changeover"))
        compose.onNodeWithText("Changeover").performClick()
        compose.waitForIdle()
        check(player.asked.containsAll(listOf("skip by 30000", "seek 3600000"))) { player.asked.toString() }
    }

    /** Timed lyrics over a song's cover, the line being sung bright. */
    @Test
    fun lyrics() = shoot("phone-lyrics") { actions ->
        val lyrics = dev.nitrate.core.music.Lyrics(
            listOf("Lights go down", "The reel begins to turn", "Silver on the screen", "We hold our breath", "", "And the city fades to grain", "Frame by frame").mapIndexed { i, t ->
                dev.nitrate.core.music.LyricLine(t, i * 4_000L)
            },
            synced = true,
        )
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(actions.server.backdropUrl(MediaItem("series-1", "", ItemKind.SERIES)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            dev.nitrate.mobile.ui.LyricsView(lyrics, positionMs = 9_000, onSeek = {})
        }
    }

    /** The player's More sheet: speed, sleep timer, night mode, subtitle timing. */
    @Test
    fun playerMore() = shoot("phone-player-more") { actions ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val session = androidx.compose.runtime.remember {
            dev.nitrate.android.playback.PlaybackSession(context, actions.server, actions.container.playbackSettings, deviceNoun = "This device", onEnded = {}, trackMemory = actions.container.trackMemory)
        }
        dev.nitrate.mobile.ui.PlayerMoreSheet(actions, session, sleepAt = null, sleepAtEnd = true, onSleep = { _, _ -> }, onPictureInPicture = {}, onPlayedElsewhere = {}, onDismiss = {})
    }

    /** "Will started a Group Watch Session", joined late: Join at where the group is, or from the beginning. */
    @Test fun sessionJoinPrompt() = shoot("phone-session-join") { actions ->
        val s = (actions.server as dev.nitrate.testing.FakeServer).sampleSession(positionMs = 120_000)
        val prompt = dev.nitrate.core.partners.SessionPrompt.of(s, s.serverTimeMs)!!
        check(prompt.choices.map { it.label } == listOf("Join at 2:00", "Start from the beginning", "Not now")) { prompt.choices.toString() }
        dev.nitrate.mobile.ui.SessionJoinDialog(prompt, onDismiss = {}, onChoose = {})
    }

    /** The countdown over the video, and the starter's wait (with Start now) while the group gathers. */
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun sessionCountdown() = shoot("phone-session-countdown") {
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            dev.nitrate.mobile.ui.SessionCountdown(3, null, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(24.dp).widthIn(max = 300.dp))
            dev.nitrate.mobile.ui.SessionCountdown(null, "Waiting for Will to join", onStartNow = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(24.dp).widthIn(max = 360.dp))
        }
    }

    /** The others' pause and jump offered (Pause too, Jump too), and drifting behind: Catch up. */
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun sessionNotices() = shoot("phone-session-notices") {
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                dev.nitrate.mobile.ui.SessionNoticeCard(dev.nitrate.core.partners.SessionNotice.Paused("Will", 61_000), onAct = {}, onDismiss = {})
                dev.nitrate.mobile.ui.SessionNoticeCard(dev.nitrate.core.partners.SessionNotice.Jumped("Will", 1_450_000), onAct = {}, onDismiss = {})
                dev.nitrate.mobile.ui.SessionNoticeCard(dev.nitrate.core.partners.SessionNotice.Drift("Will", 12_000, 130_000), onAct = {}, onDismiss = {})
            }
        }
    }

    @Test fun profiles() = shoot("phone-profiles", setup = { c -> c.profiles.setPin("home|kids", null); c.profiles.setPin("home|sam", "1234") }) {
        dev.nitrate.mobile.ui.ProfilesScreen(it, onOpened = {}, onSignInAgain = {}, onAdd = {})
    }

    private val moviesLibrary = dev.nitrate.core.model.MediaItem("lib-movies", "Movies", dev.nitrate.core.model.ItemKind.LIBRARY, collection = dev.nitrate.core.model.CollectionKind.MOVIES)

    @Test fun browseGrid() = shoot("phone-browse-grid") { dev.nitrate.mobile.ui.BrowseLibrary(it, moviesLibrary) }

    @Test fun browseList() = shoot("phone-browse-list", setup = { c ->
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = dev.nitrate.core.pages.BrowseView.LIST, watched = dev.nitrate.core.pages.WatchFilter.UNWATCHED, genres = listOf("Drama")))
    }) { dev.nitrate.mobile.ui.BrowseLibrary(it, moviesLibrary) }

    @Test fun addToList() = shoot("phone-add-to-list", setup = { c -> c.refreshAccount() }) { a ->
        val film = androidx.compose.runtime.produceState<dev.nitrate.core.model.MediaItem?>(null) { value = a.server.getItem("m1") }.value
        if (film != null) dev.nitrate.mobile.ui.AddToListDialog(a, film) {}
    }

    @Test fun album() = shoot("phone-album") { DetailsScreen(it, "al1", null) }

    @Test fun liveTv() = shoot("phone-live-tv") { dev.nitrate.mobile.ui.LiveTvScreen(it) }

    @Test fun libraryTab() = shoot("phone-library-tab") { MainScreen(it, onSignedOut = {}, startOnLibrary = true) }

    @Test fun libraryRecommended() = shoot("phone-library-movies") { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies") }

    /** A library's All tab with Home's look: tinted background, pill tabs, the compact bar; Fixed grid keeps them at the top. */
    @Test fun libraryAllFixed() = shoot("phone-library-all-fixed") { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }

    /** Fixed grid, scrolled: the tabs and the bar stay while the posters go under them. */
    @Test fun libraryAllFixedScrolled() = shoot("phone-library-all-fixed-scrolled", act = ::scrollGrid) { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }

    /** Scrolling grid, scrolled: the tabs and the bar have gone up with the posters (the back arrow and name stay). */
    @Test fun libraryAllScrollingScrolled() = shoot(
        "phone-library-all-scrolling-scrolled",
        setup = { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(phoneGridLayout = dev.nitrate.core.pages.GridLayout.SCROLLING)) },
        act = ::scrollGrid,
    ) { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }

    /** Scrolling grid at the top: it looks just like the fixed one until it moves. */
    @Test fun libraryAllScrolling() = shoot(
        "phone-library-all-scrolling",
        setup = { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(phoneGridLayout = dev.nitrate.core.pages.GridLayout.SCROLLING)) },
    ) { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }

    /** A library's All tab: the top bar's pencil opens Customize on the list, its View (Posters, Artwork, List) and Full list. */
    @Test fun libraryAllCustomize() {
        shoot("phone-library-all-customize", act = ::openListCustomize, wholeScreen = true) { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }
        compose.onNodeWithText("View").assertExists()
        compose.onNodeWithText("Full list").assertExists()
    }

    /** Artwork picked in Customize: the All tab shows wide artwork at once, saved under the library's own key. */
    @Test fun libraryAllCustomizeArtwork() {
        shoot("phone-library-all-artwork", act = {
            openListCustomize()
            compose.onNodeWithText("Artwork").performClick()
            compose.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
        }) { dev.nitrate.mobile.ui.LibraryScreen(it, "lib-movies", startTab = 1) }
        compose.onNodeWithText("View").assertDoesNotExist()
        val saved = kotlinx.coroutines.runBlocking { shot!!.displaySettings.view("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions()).first() }
        check(saved.view == dev.nitrate.core.pages.BrowseView.ARTWORK) { "Artwork should be saved under browse/lib-movies, was ${saved.view}" }
    }

    private fun openListCustomize() {
        compose.onNodeWithContentDescription("Customize this page").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun scrollGrid() {
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f, durationMillis = 600) }
        compose.mainClock.advanceTimeBy(1_500)
    }

    @Test fun browseArtwork() = shoot("phone-browse-artwork", setup = { c ->
        c.displaySettings.saveView("user-1", "browse/lib-movies", dev.nitrate.core.pages.BrowseOptions(view = dev.nitrate.core.pages.BrowseView.ARTWORK))
    }) { dev.nitrate.mobile.ui.BrowseLibrary(it, moviesLibrary) }

    /** A Home row drawn in [style], under Continue Watching as on the real Home. */
    @Composable
    private fun StyledRow(a: AppActions, style: dev.nitrate.core.rows.RowStyle) {
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = a.server.homeRows() }.value
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp)) {
            rows.firstOrNull { it.key == "home/continue_watching" }?.let { dev.nitrate.mobile.ui.CinematicRow(it, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, showTitles = true), a, onDisplay = {}) }
            rows.firstOrNull { it.key.startsWith("home/latest/") }?.let { dev.nitrate.mobile.ui.CinematicRow(it, dev.nitrate.core.rows.RowDisplay(style), a, onDisplay = {}) }
        }
    }

    /** Artwork with its cards hidden as the top row of Home: the whole screen is the first title. */
    @Composable
    private fun CardsHidden(a: AppActions, display: dev.nitrate.core.rows.RowDisplay = dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.WIDE, cards = false)) {
        val rows = androidx.compose.runtime.produceState<List<dev.nitrate.core.rows.MediaRow>>(emptyList()) { value = a.server.homeRows() }.value
        rows.firstOrNull { it.key.startsWith("home/latest/") }?.let { dev.nitrate.mobile.ui.CinematicRow(it, display, a, onDisplay = {}) }
    }

    @Test fun cardsHiddenRow() = shoot("phone-row-cards-hidden") { CardsHidden(it) }

    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun cardsHiddenRowLandscape() = shoot("phone-row-cards-hidden-land") { CardsHidden(it) }

    /** A row saved as Full Showcase before it was retired shows as Artwork with its cards hidden. */
    @Test fun savedFullShowcaseRow() = shoot("phone-row-saved-full-showcase") { CardsHidden(it, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.FULL_SHOWCASE)) }

    @Test @Config(qualifiers = "w411dp-h1000dp-night-xxhdpi")
    fun filmStripRow() = shoot("phone-row-film-strip") { StyledRow(it, dev.nitrate.core.rows.RowStyle.FILM_STRIP) }

    @Test @Config(qualifiers = "w411dp-h1100dp-night-xxhdpi")
    fun lightningRow() = shoot("phone-row-lightning") { StyledRow(it, dev.nitrate.core.rows.RowStyle.LIGHTNING) }

    @Test @Config(qualifiers = "w411dp-h1050dp-night-xxhdpi")
    fun cressidaRow() = shoot("phone-row-cressida") { StyledRow(it, dev.nitrate.core.rows.RowStyle.CRESSIDA) }

    @Test fun speedCheck() = shoot("phone-speed-check") { a -> dev.nitrate.mobile.ui.SpeedCheckDialog(a) {} }
    @Test fun queryAnalyzer() = shoot("phone-query-analyzer") { a -> dev.nitrate.mobile.ui.QueryAnalyzerScreen(a) }

    @Test fun addToLists() = shoot("phone-add-to-lists") { a ->
        dev.nitrate.mobile.ui.AddToListDialog(a, dev.nitrate.core.model.MediaItem("m1", "Nitrate", dev.nitrate.core.model.ItemKind.MOVIE)) {}
    }

    /** The figurative inks large, to judge their detail. */
    @Test @Config(qualifiers = "w411dp-h1850dp-night-xxhdpi")
    fun cressidaFigures() = shoot("phone-cressida-figures") { a ->
        val films = androidx.compose.runtime.produceState<List<dev.nitrate.core.model.MediaItem>>(emptyList()) { value = a.server.homeRows().first { it.key.startsWith("home/latest/") }.items }.value
        if (films.isEmpty()) return@shoot
        val inks = listOf(dev.nitrate.core.rows.CressidaInk.WESTERN_DRAGON, dev.nitrate.core.rows.CressidaInk.DRAGON, dev.nitrate.core.rows.CressidaInk.PHOENIX, dev.nitrate.core.rows.CressidaInk.FLAMES, dev.nitrate.core.rows.CressidaInk.SONGBIRD, dev.nitrate.core.rows.CressidaInk.CONSTELLATION)
        androidx.compose.foundation.layout.Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            inks.chunked(2).forEachIndexed { r, pair ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { i, ink ->
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(ink.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                            dev.nitrate.mobile.ui.CressidaCard(films[(r * 2 + i) % films.size], a, "fig-${ink.name}", dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, ink = ink), Modifier.fillMaxWidth().height(420.dp))
                        }
                    }
                    if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    /** Every Cressida ink, two to a row, on real cards. */
    @Test @Config(qualifiers = "w411dp-h2700dp-night-xxhdpi")
    fun cressidaInks() = shoot("phone-cressida-inks") { a ->
        val films = androidx.compose.runtime.produceState<List<dev.nitrate.core.model.MediaItem>>(emptyList()) { value = a.server.homeRows().first { it.key.startsWith("home/latest/") }.items }.value
        if (films.isEmpty()) return@shoot
        androidx.compose.foundation.layout.Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            dev.nitrate.core.rows.CressidaInk.entries.chunked(2).forEachIndexed { r, pair ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { i, ink ->
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(ink.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                            dev.nitrate.mobile.ui.CressidaCard(films[(r * 2 + i) % films.size], a, "ink-${ink.name}", dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, ink = ink), Modifier.fillMaxWidth().height(300.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun inkColourDialog() = shoot("phone-ink-colour") {
        dev.nitrate.mobile.ui.InkColourDialog(dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.CRESSIDA, inkTint = dev.nitrate.core.rows.InkTint.CUSTOM, inkArgb = 0xFF3FC6A0L), onSave = {}, onDismiss = {})
    }

    @Test fun remote() = shoot("phone-remote") { dev.nitrate.mobile.ui.RemoteScreen(it, "s1") }

    /** A TV playing an episode asks "Still watching?": answered from the phone; then its arrow pad. */
    @Test
    fun remoteAnswersAndSteers() {
        val target = FakeRemote()
        shoot("phone-remote-episode") { dev.nitrate.mobile.ui.RemoteScreen(it, "s1", target = target) }
        compose.onNodeWithText("Keep watching").performClick()
        compose.waitForIdle()
        check("answer keep" in target.asked) { target.asked.toString() }
        compose.onNodeWithText("Still watching?").assertDoesNotExist()
        // Further down: the chapters and the season's episodes.
        compose.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasScrollToNodeAction().not())
            .performScrollToNode(androidx.compose.ui.test.hasText("Episodes"))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/phone-remote-lists.png")
        compose.onNodeWithText("Reel change").performClick()
        compose.waitForIdle()
        check("seek 1260000" in target.asked) { target.asked.toString() }
        compose.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasScrollToNodeAction().not())
            .performScrollToNode(androidx.compose.ui.test.hasText("Arrows"))
        compose.onNodeWithText("Arrows").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/phone-remote-arrows.png")
        compose.onNodeWithContentDescription("Up").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        check(target.asked.containsAll(listOf("MoveUp", "Select"))) { target.asked.toString() }
    }

    @Test
    fun transfer() = shoot("phone-writing-transfer") {
        TransferDialog(dev.nitrate.mobile.platform.TransferState(running = true, address = "http://192.168.1.42:8765", pin = "482913"), onToggle = {}, onDismiss = {})
    }

    /** A Home the user has edited: a renamed row, a hidden one, and a row of their own. */
    private suspend fun customizeHome(c: AppContainer) {
        val horror = RowEntry("h1", RowSource.Query(ItemQuery(genres = listOf("Horror"), watched = WatchFilter.UNWATCHED, sort = QuerySort.RANDOM)), title = "Unwatched horror")
        c.displaySettings.saveLayout(
            "user-1", PageKeys.HOME,
            PageLayout(
                listOf(
                    RowEntry.builtIn("home/next_up"),
                    horror,
                    RowEntry.builtIn("home/continue_watching").copy(title = "Pick up where you left off"),
                    RowEntry.builtIn("home/latest/lib-movies").copy(hidden = true),
                ),
            ),
        )
        c.displaySettings.saveCustomPages("user-1", listOf(CustomPage("pg1", "Friday night"), CustomPage("pg2", "Kids", pinned = false)))
    }

    @Test fun homeEdited() = shoot("phone-home-edited", setup = ::customizeHome) { MainScreen(it) {} }

    @Test fun pageEditor() = shoot("phone-page-editor", setup = ::customizeHome) {
        val c = it.container
        val vm = androidx.lifecycle.viewmodel.compose.viewModel { RowsViewModel(PageKeys.HOME, c.server::homeRows, c.displaySettings, c.server) }
        PageEditor("Home", vm, it, null) {}
    }

    @Test fun rowBuilder() = shoot("phone-row-builder") { RowBuilderDialog(it, null, initial = null, initialTitle = null, onSave = { _, _ -> }, onDismiss = {}) }

    @Test fun displaySettings() = shoot("phone-display-settings", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(accent = AccentMode.TEAL, hideSpoilers = true, criticRating = false))
    }) { SettingsPageScreen(it, SettingsPage.DISPLAY) }

    @Test fun pluginSettings() = shoot("phone-settings-plugins", setup = { c ->
        c.plugins.setEnabled(dev.nitrate.core.episodes.PreviousEpisodePlugin.id, true)
        c.refreshCompanionForTests()
    }) { SettingsPageScreen(it, SettingsPage.PLUGINS) }

    /** Long-pressing a plot copies it; a tap still expands it. */
    @Test fun longPressCopiesThePlot() {
        shoot("phone-movie-copy") { DetailsScreen(it, "m2", null) }
        val plot = "A projectionist discovers a reel that shouldn't exist."
        compose.onNodeWithText(plot).performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()
        val clip = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        org.junit.Assert.assertEquals(plot, clip?.getItemAt(0)?.text?.toString())
    }

    @Test fun settingsFolded() = shoot("phone-settings") { SettingsScreen(it, {}) }

    /** Your watching lists Watch groups when the server's Companion has them (not only through search). */
    @Test fun settingsListWatchGroups() {
        shoot("phone-settings-your-watching", setup = { c -> c.refreshCompanionForTests() }) { SettingsScreen(it, {}) }
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText("Watch diary"))
        compose.onAllNodesWithText("Watch groups").onFirst().assertExists()
    }

    /** Settings search: settings found by the words around them, with where each lives. */
    @Test fun settingsSearch() {
        shoot("phone-settings-search") { SettingsScreen(it, {}, initialQuery = "night") }
        compose.onAllNodesWithText("Sound: Normal, Clearer dialogue, Night").onFirst().assertExists()
    }

    /** Admins: the server's Nitrate Companion with a newer version waiting for a restart (Settings → Updates). */
    @Test fun companionUpdateWaiting() = shoot("phone-companion-update", setup = { c ->
        (c.server as dev.nitrate.testing.FakeServer).companionFeatures += "updates"
        c.refreshCompanionForTests()
        c.refreshAccount()
    }) { SettingsPageScreen(it, SettingsPage.UPDATES) }

    @Test fun pictureAndSound() = shoot("phone-picture-and-sound") { SettingsPageScreen(it, SettingsPage.SOUND) }

    @Test fun whileWatching() = shoot("phone-while-watching", setup = { c -> c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true)) }) { SettingsPageScreen(it, SettingsPage.WATCHING) }

    @Test fun spoilerFree() = shoot("phone-series-spoilers", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true, communityRating = false, overview = false))
    }) { DetailsScreen(it, "series-1", null) }

    /** An unwatched movie with spoilers hidden, as an admin: the description waits, and every action (How it plays too) fits. */
    @Test fun movieSpoilerFree() = shoot("phone-movie-spoilers", setup = { c ->
        c.displaySettings.savePrefs("user-1", DisplayPrefs(hideSpoilers = true))
        c.refreshAccount()
    }) { DetailsScreen(it, "m2", null) }

    @Test fun manageDetails() = shoot("phone-manage-details") { ManageItemScreen(it, "m1") }

    @Test fun serverAdmin() = shoot("phone-server-admin") { ServerAdminScreen(it) }

    /** Manage → Refresh for an admin whose Companion has the credits report: the Credits check in a few words, and Copy report. */
    @Test fun manageCreditsCheck() = shoot("phone-manage-credits-check", setup = { c ->
        (c.server as FakeServer).companionFeatures += dev.nitrate.core.playback.CreditsReport.FEATURE
        c.refreshCompanionForTests()
        c.refreshAccount()
    }, act = {
        compose.onNodeWithText("Refresh").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Check now").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Copy report").performScrollTo().assertExists()
        compose.onAllNodesWithText("Credits start at 1:50:00.", substring = true).onFirst().assertExists()
    }) { ManageItemScreen(it, "m1") }

    /** Signs the test account in as a metadata editor (Companion feature `metadata`) in the Movies library. */
    private suspend fun asEditor(c: AppContainer) {
        (c.server as FakeServer).signInAsEditor()
        c.refreshCompanionForTests()
        c.refreshAccount()
    }

    /** An admin on a server whose Companion offers metadata editors. */
    private suspend fun withEditors(c: AppContainer) {
        (c.server as FakeServer).companionFeatures += dev.nitrate.core.admin.MetadataEditors.FEATURE
        c.refreshCompanionForTests()
        c.refreshAccount()
    }

    /** A metadata editor's More on a film in their library: Manage is there (and not on an episode outside it). */
    @Test fun editorDetailsMore() {
        var canManageEpisode = true
        shoot("phone-editor-details", setup = { c -> asEditor(c); canManageEpisode = c.canManageMetadata("s1e2") }, act = {
            compose.onNodeWithText("More").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
        }, wholeScreen = true) { DetailsScreen(it, "m1", null) }
        compose.onAllNodesWithText("Manage").onFirst().assertExists()
        check(!canManageEpisode) { "an episode outside the editor's libraries offered Manage" }
    }

    /** An editor's Manage: the note that admins see their changes, and a refused save saying why. */
    @Test fun editorManageRefused() = shoot("phone-editor-manage-refused", setup = { c ->
        asEditor(c)
        (c.server as FakeServer).metadataRefusal = "You can't edit this title"
    }, act = {
        compose.onNodeWithText("Save changes").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Couldn't save: You can't edit this title").onFirst().assertExists()
    }) { ManageItemScreen(it, "m1") }

    /** Users with the Companion's metadata editors: Sam's "Metadata editor" switch and the libraries it covers. */
    @Test fun serverAdminEditors() = shoot("phone-server-admin-editors", setup = { c -> withEditors(c) }, act = {
        compose.onNodeWithText("Users").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Sam").onFirst().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Delete media")))
            .onFirst().performScrollToNode(androidx.compose.ui.test.hasText("In these libraries"))
        compose.waitForIdle()
    }, wholeScreen = true) { ServerAdminScreen(it) }

    /** Manage server → Metadata edits: newest first, Undo where it can be undone. */
    @Test fun metadataEdits() = shoot("phone-metadata-edits", setup = { c -> withEditors(c) }, act = {
        compose.onNodeWithText("Metadata edits").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Undo").assertCountEquals(2)
    }) { ServerAdminScreen(it) }

    /** Writing beside the video: side by side when wide, stacked when upright. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun writingBesideVideo() = shoot("phone-player-writing-wide") { WritingBesideStill(it) }

    @Test
    fun writingBesideVideoUpright() = shoot("phone-player-writing-upright") { WritingBesideStill(it) }

    /** A tablet with the split set to video on top, for a wide film. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun writingStackedTablet() = shoot("tablet-player-writing-stacked") { WritingBesideStill(it, dev.nitrate.core.playback.WritingSplit.STACKED, 2.39f) }

    @Composable
    private fun WritingBesideStill(actions: AppActions, split: dev.nitrate.core.playback.WritingSplit = dev.nitrate.core.playback.WritingSplit.AUTO, aspect: Float? = 16f / 9f) {
        PlayerLayout(writing = true, split = split, videoAspect = aspect, modifier = Modifier, pane = { WritingScreen(actions, onBack = {}, pane = true) }) {
            AsyncImage(actions.server.backdropUrl(MediaItem("m3", "", ItemKind.MOVIE)), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }

    /** The first-time question after signing in, and what restoring would change. */
    @Test
    fun backupPrompt() = shoot("phone-backup-prompt") {
        dev.nitrate.mobile.ui.RestoreChooser("Set this device up like…", "Your other devices' settings are saved on your server. Choose one to copy its playback and plugin settings here; you'll see what changes first.",
            dev.nitrate.testing.SampleBackups.list, it.container.backups, "Not now", {}, {})
    }

    @Test
    fun backupDifferences() = shoot("phone-backup-differences") { dev.nitrate.mobile.ui.DifferencesDialog(dev.nitrate.testing.SampleBackups.preview, {}, {}) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun tabletHome() = shoot("tablet-home") { MainScreen(it) {} }

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun lightDetails() = shoot("phone-movie-light") { DetailsScreen(it, "m1", null) }

    /** The first-run setup, one step at a time (big text, Skip and Back on each, "Step n of N"). */
    private fun setupShot(name: String, step: dev.nitrate.core.setup.SetupStep) = shoot(name) { a ->
        val c = a.container
        val data = dev.nitrate.mobile.ui.PhoneSetupData(
            steps = dev.nitrate.core.setup.FirstRunSetup.steps(dev.nitrate.core.setup.SetupFacts(tv = false, profilesToAsk = true, backupsOffered = true, askSound = true, menuSuggestions = false)),
            accounts = listOf(
                dev.nitrate.core.model.SavedAccount(c.server.currentAccountKey ?: "me", "http://demo", "Home server", "user-1", "Alex", null),
                dev.nitrate.core.model.SavedAccount("other", "http://demo", "Home server", "user-2", "Sam", null),
            ),
            backups = dev.nitrate.testing.SampleBackups.list,
            output = dev.nitrate.core.playback.SoundOutput(dev.nitrate.core.playback.OutputKind.USB, "Desk speakers", 6),
        )
        dev.nitrate.mobile.ui.PhoneSetupFrame { dev.nitrate.mobile.ui.PhoneSetupSteps(c, data, startAt = step) }
    }

    @Test fun setupProfile() = setupShot("phone-setup-profile", dev.nitrate.core.setup.SetupStep.PROFILE)
    @Test fun setupCopy() = setupShot("phone-setup-copy", dev.nitrate.core.setup.SetupStep.COPY_SETTINGS)
    @Test fun setupSound() = setupShot("phone-setup-sound", dev.nitrate.core.setup.SetupStep.SOUND)
    @Test fun setupDone() = setupShot("phone-setup-done", dev.nitrate.core.setup.SetupStep.DONE)
}
