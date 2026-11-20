package dev.nitrate.core.help

/**
 * One part of the playback guide. [tvOnly] parts are left out on phones, [adminOnly] ones for
 * people who aren't the server's admin.
 */
data class GuideSection(
    val id: String,
    val title: String,
    val intro: String,
    val steps: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val tvOnly: Boolean = false,
    val adminOnly: Boolean = false,
)

/**
 * "Checking how things play": what each of Nitrate's playback tools is for, how to use it, and
 * what to do about the usual problems. Shown in Settings → Tools on both apps (and linked from the
 * tools themselves). Plain words: written for whoever is holding the remote.
 */
object PlaybackGuide {
    val sections: List<GuideSection> = listOf(
        GuideSection(
            "start", "Which tool to use",
            "Nitrate plays files as they are whenever it can, and only lets your server convert what this device can't play. These tools show you what happens, why, and how to fix it.",
            steps = listOf(
                "Something looks or sounds wrong right now: open the player's Info. Its Path shows every step from the file to the screen.",
                "Wondering about one title before you play it: open it and choose How it plays.",
                "Wondering about the whole library: Settings → Tools → Library playback overview.",
                "Not sure your TV or receiver shows HDR, Dolby Vision or surround: Settings → Tools → TV & receiver check.",
                "Still stuck: send a log (player Info, or Settings → Tools → Send device report). It's saved on your server for its admin.",
            ),
            tips = listOf("The settings these tools change (a format set to Always or Never, failures it learned, direct play) are in Settings → Tools → Advanced playback settings, the same page as Picture & sound → Advanced."),
        ),
        GuideSection(
            "info", "While it plays: Info and its Path",
            "Open the player's controls and choose Info. The top shows the picture and the sound as they are now. Below it, Path lists each step from the file to the screen and who did it: the file, Nitrate, your server, this device, and you.",
            steps = listOf(
                "Read Picture from 1 to 8: what the file is, what Nitrate asked the server for, what the server did, what arrived, the decoder, what went out, and what the TV should show.",
                "Sound works the same way, ending with what the receiver should show.",
                "Anything in amber turned out differently from what was expected. The line under it says what was expected.",
                "Converted by the server and you'd like to know why: choose Why converting? It offers fixes when there are some.",
            ),
            tips = listOf("The app can't see the HDMI signal itself, so the last step of each says what to look for on the TV or receiver."),
        ),
        GuideSection(
            "howitplays", "Before it plays: How it plays",
            "On a title's page, How it plays works out what will happen on this device before you press play: whether it plays as it is or is converted (and why), the decoder, HDR, the sound, the refresh rate and the subtitles. \"On this device\" also quotes what your checks found.",
        ),
        GuideSection(
            "overview", "Library playback overview",
            "Settings → Tools → Library playback overview reads every film and episode you can see and works out how each plays here. It takes a moment for a big library.",
            steps = listOf(
                "Pick a library at the top, or All.",
                "The counts tell you how many titles play as they are, are repackaged, or have their sound or picture converted. Pick one to see just those.",
                "Open a title to see why, then Test play to try it for real.",
                "Test a sample plays one title of each kind of file in turn. It's the quickest way to check a whole library.",
                "Send to server log saves the whole list on your server, for its admin.",
            ),
            tips = listOf("\"Shared with your server's admin\" means the admin can see this device's results on their Household devices page. Select it to stop sharing."),
        ),
        GuideSection(
            "testplay", "Test plays",
            "A test play plays about 30 seconds of the real file through the normal path, then shows its Path. Nothing is marked watched and no resume point is saved.",
            steps = listOf(
                "Watch the TV and the receiver while it plays: you'll be asked what they showed.",
                "It jumps ahead once partway through, to check seeking and that surround sound still reaches the receiver untouched afterwards.",
                "Afterwards answer the questions (Yes, No or Not sure), then read What to do. A setting it suggests can be changed right there.",
                "Choose Test again to see whether a change helped.",
            ),
            tips = listOf(
                "Smoothness shows how long it took to start, any stops to buffer, dropped frames, and the network against what the file needs.",
                "When the server converts, Server reported shows what it really did and whether it keeps up.",
            ),
        ),
        GuideSection(
            "tvcheck", "TV & receiver check",
            "Settings → Tools → TV & receiver check plays short clips from your server (they come with the Nitrate Companion plugin), each about 20 seconds: HDR10, Dolby Vision, HLG, and Dolby Digital, Dolby Digital Plus, Dolby TrueHD, DTS and 7.1 sound.",
            steps = listOf(
                "Choose Check them all, or one clip.",
                "Picture clips: watch for the TV's HDR or Dolby Vision badge, or its picture mode changing to an HDR one (open the TV's picture settings while it plays to check).",
                "Sound clips: watch the receiver's display for the format, and listen: each speaker plays in turn as it's named on screen.",
                "Say what you saw. Your answers are kept for this device and shown in How it plays, and What to do suggests any setting to change.",
            ),
            tips = listOf("The Dolby Vision 5 clip shows green or purple colours when Dolby Vision isn't being processed: that's the clip working, not a fault in the clip."),
            tvOnly = true,
        ),
        GuideSection(
            "devicecheck", "Playback test",
            "Settings → Tools → Playback test plays tiny samples of each video and sound format to find what this TV really decodes, then times one refresh-rate switch. Run it once, and again after a system update. Its results make How it plays and the overview more exact.",
            tvOnly = true,
        ),
        GuideSection(
            "washedout", "HDR looks washed out or grey",
            "That's an HDR picture turned into an ordinary (SDR) one badly. Path's Picture steps say who converted it.",
            steps = listOf(
                "\"Tone-mapped to SDR by the server\": your server converted it. Its reason says why: often HDR10 or Dolby Vision set to Never on this device, or the display not reporting HDR.",
                "If the TV can show HDR (check with the TV & receiver check), set HDR10 and Dolby Vision to Always in Settings → Tools → Advanced playback settings.",
                "If it really can't, the server's tone mapping needs to be on: an admin can check it in Server-side settings → Converting on the server.",
                "\"Converted to SDR by this device\": the device did it itself because Android reports no HDR. The picture is usually fine. For real HDR, see the next section.",
            ),
        ),
        GuideSection(
            "nohdr", "The TV never shows HDR",
            "The device only sends HDR when the TV, and any receiver or soundbar in between, tell it they can take it.",
            steps = listOf(
                "On the receiver, turn on its enhanced 4K or HDR signal setting for that input (Denon: Video → 4K Signal Format → Enhanced).",
                "On the TV, turn on the enhanced or deep colour mode for that HDMI input (Samsung: Input Signal Plus; LG: HDMI Deep Colour; Sony: Enhanced format).",
                "Use an HDMI cable rated 18 Gbps or more between the receiver and the TV.",
                "On the device, set the display's dynamic range to Adaptive (Fire TV: Display & Sounds → Display).",
                "Turn the TV, the receiver and then the device off and on again, then run the TV & receiver check.",
            ),
        ),
        GuideSection(
            "surround", "Surround comes out as PCM, or not at all",
            "\"Sent untouched\" means the receiver decodes the sound itself and should show its format. \"Decoded here\" means the device decoded it to plain PCM: still all channels, but the receiver shows PCM.",
            steps = listOf(
                "Info's Why decoded says the reason: Night mode or Clearer dialogue on, a speed other than normal, or the output not taking the format.",
                "On a Fire TV, set Display & Sounds → Audio → Surround Sound to Best available.",
                "On the receiver, set the input's decode mode to Auto.",
                "If it only happens after the TV switches refresh rate, try Refresh rate: Only when seamless.",
            ),
        ),
        GuideSection(
            "stutter", "Stutter, judder or stopping to buffer",
            "Path's Smoothness and Refresh rate parts say which it is.",
            steps = listOf(
                "Judder (uneven motion in slow pans): the TV's refresh rate doesn't suit the film. Set Match frame rate to Always in Picture & sound.",
                "Tired of the screen going black when you leave a film? Stay at the film's rate (Picture & sound) keeps it for the menus: Always (until you leave Nitrate), 15 minutes, or Never.",
                "Stops to buffer with \"the network is slower than the stream needs\": use a wired connection or 5 GHz Wi-Fi. A bitrate limit makes the server send it smaller instead.",
                "\"Too slow for real time\" from the server: its converting needs hardware acceleration (an admin's setting).",
                "Many dropped frames with a software decoder: this device can't decode that format smoothly. Another version of the title may play better.",
            ),
        ),
        GuideSection(
            "admin", "For the server's admin",
            "You can see how every device in the household plays your library without asking anyone to read a screen out.",
            steps = listOf(
                "Settings → Tools → Household devices lists each device's latest summary: what it converts and why, what Android reports for HDR, its test plays and what its user saw.",
                "\"Converted on several devices\" comes first: those titles are worth adding a version every device plays as it is (for example a 1080p SDR copy).",
                "Server-side settings → Converting on the server shows hardware acceleration and tone mapping, with what to check.",
                "Logs sent from devices are in Jellyfin's Dashboard → Logs.",
            ),
            adminOnly = true,
        ),
    )

    /** The parts for a device: TV-only parts only on a TV, admin parts only for admins. */
    fun sectionsFor(tv: Boolean, admin: Boolean): List<GuideSection> =
        sections.filter { (tv || !it.tvOnly) && (admin || !it.adminOnly) }
}
