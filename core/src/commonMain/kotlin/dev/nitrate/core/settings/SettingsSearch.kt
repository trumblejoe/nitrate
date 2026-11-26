package dev.nitrate.core.settings

/**
 * Where a setting lives: a page of Settings (each app opens it its own way: TV routes, the phone's
 * `SettingsPage`s and routes). [path] is how it reads in a search result ("Display › Effects").
 */
enum class SettingsPlace(val path: String) {
    SOUND("Picture & sound"),
    SOUND_ADVANCED("Picture & sound › Advanced"),
    SUBTITLES("Subtitles"),
    DISPLAY("Display"),
    DISPLAY_LAYOUT("Display › Layout for every page"),
    DISPLAY_SHOWN("Display › What's shown"),
    DISPLAY_EFFECTS("Display › Effects"),
    DISPLAY_PAGES("Display › Pages"),
    MENU_STOPS("Display › Menu stops"),
    DISPLAY_PROFILE("Display profile"),
    WATCHING("While watching"),
    BEFORE_IT_PLAYS("While watching › Before it plays"),
    NOTIFICATIONS("Notifications"),
    PROFILES("Account › Switch user"),
    SERVERS("Account › Servers"),
    SERVER_SETTINGS("Account › Server-side settings"),
    BACKUP("Account › Device backup"),
    SETUP("Account › Set up this device again"),
    STATS("Your watching › Your year"),
    DIARY("Your watching › Watch diary"),
    PARTNERS("Your watching › Watch groups"),
    CATALOGS("Your watching › Comic catalogs"),
    REMOTE("Your watching › Control a device"),
    GUIDE("Tools › Checking how things play"),
    PLAYBACK_TEST("Tools › Playback test"),
    AUDIO_TEST("Tools › Audio channel test"),
    TV_CHECK("Tools › TV & receiver check"),
    OVERVIEW("Tools › Library playback overview"),
    SPEED_CHECK("Tools › Server speed check"),
    QUERY_ANALYZER("Tools › Query analyzer"),
    HOUSEHOLD("Tools › Household devices"),
    HEALTH("Tools › Library health"),
    IMAGES("Tools › Library images overview"),
    MANAGE_SERVER("Tools › Manage server"),
    UPDATES("About › Updates"),
    PLUGINS("About › Plugins"),
    LICENCES("About › Open-source licences"),
}

/** Which app has a setting. */
enum class SettingsOn { BOTH, TV, PHONE }

/**
 * One searchable setting: its name as the page shows it, where it is, and other words people might
 * type for it ([words]: what it's about, older names, the formats it covers).
 */
data class SettingEntry(
    val title: String,
    val place: SettingsPlace,
    val words: String = "",
    val on: SettingsOn = SettingsOn.BOTH,
    val adminOnly: Boolean = false,
)

/**
 * Settings search: every setting (and settings page) by name and the words around it, for the
 * search box at the top of Settings on both apps. Keep it in step when a setting is added, renamed
 * or moved; each app maps [SettingsPlace] to its own page.
 */
object SettingsSearch {
    private val B = SettingsOn.BOTH
    private val T = SettingsOn.TV
    private val P = SettingsOn.PHONE

    val entries: List<SettingEntry> = listOf(
        // Picture & sound
        SettingEntry("Picture & sound", SettingsPlace.SOUND, "audio video playback quality"),
        SettingEntry("Sound: Normal, Clearer dialogue, Night", SettingsPlace.SOUND, "night mode dialogue boost voices quiet loud compress"),
        SettingEntry("Speakers", SettingsPlace.SOUND, "upmix stereo surround 5.1 7.1 receiver soundbar keep stereo layout"),
        SettingEntry("Music loudness", SettingsPlace.SOUND, "levelling normalization volume album songs"),
        SettingEntry("Audio sync", SettingsPlace.SOUND, "delay lip sync lag offset out of step"),
        SettingEntry("Line up drifting sound", SettingsPlace.SOUND, "drift drifting out of sync resync re-sync passthrough receiver dts lip"),
        SettingEntry("Match frame rate", SettingsPlace.SOUND, "refresh rate judder 24p 23.976 hz display mode", T),
        SettingEntry("Stay at the film's rate", SettingsPlace.SOUND, "refresh rate hold keep afterwards", T),
        SettingEntry("How refresh rate works", SettingsPlace.SOUND, "refresh rate judder black screen", T),
        SettingEntry("Maximum bitrate", SettingsPlace.SOUND, "quality data limit network buffering convert"),
        // Picture & sound › Advanced
        SettingEntry("Advanced playback settings", SettingsPlace.SOUND_ADVANCED, "troubleshooting formats fixes"),
        SettingEntry("Formats this device plays (Always / Never)", SettingsPlace.SOUND_ADVANCED, "codecs hevc av1 h264 hdr hdr10 dolby vision override capabilities"),
        SettingEntry("Tunneled playback", SettingsPlace.SOUND_ADVANCED, "tunneling tunnel", T),
        SettingEntry("Dolby Vision profile 7 as 8.1", SettingsPlace.SOUND_ADVANCED, "dv uhd blu-ray fel mel convert"),
        SettingEntry("3D films", SettingsPlace.SOUND_ADVANCED, "side by side top and bottom mvc"),
        SettingEntry("Maximum resolution", SettingsPlace.SOUND_ADVANCED, "4k 1080p limit height"),
        SettingEntry("Allow direct play", SettingsPlace.SOUND_ADVANCED, "transcode convert"),
        SettingEntry("Allow direct stream", SettingsPlace.SOUND_ADVANCED, "remux repackage"),
        SettingEntry("Learn from failures", SettingsPlace.SOUND_ADVANCED, "learned failures convert after failing"),
        SettingEntry("Forget learned failures", SettingsPlace.SOUND_ADVANCED, "reset failed titles"),
        SettingEntry("Lossless and object audio", SettingsPlace.SOUND_ADVANCED, "truehd dts-hd dts:x atmos passthrough bitstream", T),
        // Subtitles
        SettingEntry("Subtitles", SettingsPlace.SUBTITLES, "captions cc"),
        SettingEntry("Subtitle size", SettingsPlace.SUBTITLES, "text size bigger smaller captions"),
        SettingEntry("Subtitle colour", SettingsPlace.SUBTITLES, "color yellow white captions"),
        SettingEntry("Subtitle background", SettingsPlace.SUBTITLES, "box shadow captions"),
        SettingEntry("Subtitle edges", SettingsPlace.SUBTITLES, "outline drop shadow captions"),
        SettingEntry("Subtitle height from bottom", SettingsPlace.SUBTITLES, "position raise captions"),
        SettingEntry("Styled subtitles (ASS/SSA)", SettingsPlace.SUBTITLES, "anime fonts karaoke signs libass"),
        // Display
        SettingEntry("Display", SettingsPlace.DISPLAY, "look appearance"),
        SettingEntry("Easy mode", SettingsPlace.DISPLAY, "simple bigger text accessibility elderly"),
        SettingEntry("True black", SettingsPlace.DISPLAY, "oled dark background"),
        SettingEntry("Library lists", SettingsPlace.DISPLAY, "fixed scrolling grid layout pinned tabs sort filters view bar all full list posters", P),
        SettingEntry("Screensaver", SettingsPlace.DISPLAY, "ambient idle minutes", T),
        SettingEntry("Clock", SettingsPlace.DISPLAY, "time", T),
        SettingEntry("Menu", SettingsPlace.DISPLAY, "rail bar dial overlay pop-up popup placement fade in black menu side", T),
        SettingEntry("Menu stops", SettingsPlace.MENU_STOPS, "menu items order comics channels radial dial hide hidden", T),
        SettingEntry("Menu groups", SettingsPlace.MENU_STOPS, "group libraries together anime kids folder split rename", T),
        SettingEntry("More page", SettingsPlace.MENU_STOPS, "more stops out of the menu collections playlists switch user", T),
        SettingEntry("Playlists in the menu or on Home", SettingsPlace.DISPLAY_PAGES, "pin playlist home tab menu stop unpin", T),
        SettingEntry("Pages", SettingsPlace.DISPLAY_PAGES, "home rows library tabs page themes backgrounds custom pages edit", T),
        SettingEntry("Page themes and backgrounds", SettingsPlace.DISPLAY_PAGES, "theme scene background gradient colour roses poetry", T),
        SettingEntry("Looks (layout presets)", SettingsPlace.DISPLAY_LAYOUT, "preset cinematic classic compact minimal style", T),
        SettingEntry("Layout for every page", SettingsPlace.DISPLAY_LAYOUT, "arrangement full art rows one at a time stacked", T),
        SettingEntry("Caption corner and size", SettingsPlace.DISPLAY_LAYOUT, "caption title position compact minimal logo", T),
        SettingEntry("Row position and length", SettingsPlace.DISPLAY_LAYOUT, "bottom top left right column part way", T),
        SettingEntry("Tab style", SettingsPlace.DISPLAY_LAYOUT, "tabs chips underline pill neon marquee film strip", T),
        SettingEntry("Focus style", SettingsPlace.DISPLAY_LAYOUT, "grow outline glow lift highlight", T),
        SettingEntry("Card corners and spacing", SettingsPlace.DISPLAY_LAYOUT, "rounded square tight roomy gap", T),
        SettingEntry("Caption box", SettingsPlace.DISPLAY_LAYOUT, "contrast readable dark box", T),
        SettingEntry("Which row this is", SettingsPlace.DISPLAY_LAYOUT, "row marker 3 / 9", T),
        SettingEntry("A–Z bar", SettingsPlace.DISPLAY_LAYOUT, "letters alphabet jump", T),
        SettingEntry("Library list", SettingsPlace.DISPLAY_LAYOUT, "all tab grid fixed scrolling posters details layout", T),
        SettingEntry("Up/Down along the row", SettingsPlace.DISPLAY_LAYOUT, "d-pad navigation full art", T),
        SettingEntry("Effects", SettingsPlace.DISPLAY_EFFECTS, "animations themes reduce motion amount card effects", T),
        SettingEntry("Holiday effects and their dates", SettingsPlace.DISPLAY_EFFECTS, "occasions seasonal christmas halloween easter thanksgiving new year festivus dates", T),
        SettingEntry("Reduce motion", SettingsPlace.DISPLAY_EFFECTS, "still animations", T),
        SettingEntry("Display profile", SettingsPlace.DISPLAY_PROFILE, "profiles tv phone tablet import radial menu", T),
        // What's shown
        SettingEntry("What's shown", SettingsPlace.DISPLAY_SHOWN, "ratings badges facts"),
        SettingEntry("Ratings", SettingsPlace.DISPLAY_SHOWN, "stars critic score age rating"),
        SettingEntry("Descriptions", SettingsPlace.DISPLAY_SHOWN, "overview plot tagline"),
        SettingEntry("Cast", SettingsPlace.DISPLAY_SHOWN, "actors people crew"),
        SettingEntry("More like this", SettingsPlace.DISPLAY_SHOWN, "similar recommendations"),
        SettingEntry("Logos", SettingsPlace.DISPLAY_SHOWN, "title art clearlogo"),
        SettingEntry("Progress on cards", SettingsPlace.DISPLAY_SHOWN, "bar circle none progress"),
        SettingEntry("Watched marks", SettingsPlace.DISPLAY_SHOWN, "tick check played"),
        SettingEntry("Titles under cards", SettingsPlace.DISPLAY_SHOWN, "names labels"),
        SettingEntry("Continue Watching and Next Up as one row", SettingsPlace.DISPLAY_SHOWN, "combine merge home rows"),
        // While watching
        SettingEntry("While watching", SettingsPlace.WATCHING, "player"),
        SettingEntry("Before it plays", SettingsPlace.BEFORE_IT_PLAYS, "pre-rolls intros trailers cinema mode verses", T),
        SettingEntry("Skip to scenes in the credits", SettingsPlace.WATCHING, "stinger post credits mid credits"),
        SettingEntry("\"Last time\" recaps", SettingsPlace.WATCHING, "recap previously catch up days"),
        SettingEntry("Hide spoilers", SettingsPlace.WATCHING, "blur stills unwatched episodes descriptions"),
        SettingEntry("Audio description", SettingsPlace.WATCHING, "described audio blind narration"),
        SettingEntry("Theme music", SettingsPlace.WATCHING, "theme songs details pages"),
        SettingEntry("\"Still watching?\"", SettingsPlace.WATCHING, "are you still watching sleep autoplay"),
        SettingEntry("Picture-in-picture", SettingsPlace.WATCHING, "pip small window"),
        SettingEntry("Show missing episodes", SettingsPlace.WATCHING, "virtual unaired seasons"),
        // Notifications (phone)
        SettingEntry("Notifications", SettingsPlace.NOTIFICATIONS, "alerts", P),
        SettingEntry("New episodes", SettingsPlace.NOTIFICATIONS, "notify followed shows", P),
        SettingEntry("New seasons", SettingsPlace.NOTIFICATIONS, "notify season premiere", P),
        SettingEntry("Group Watch Session reminders", SettingsPlace.NOTIFICATIONS, "notify watch group party session together postpone tonight", P),
        // Account & servers
        SettingEntry("Switch user", SettingsPlace.PROFILES, "account profile pin who's watching users"),
        SettingEntry("Servers", SettingsPlace.SERVERS, "address url vpn local remote primary connection"),
        SettingEntry("Server-side settings", SettingsPlace.SERVER_SETTINGS, "languages audio language subtitle language permissions jellyfin account", T),
        SettingEntry("Device backup", SettingsPlace.BACKUP, "restore copy settings another device"),
        SettingEntry("Set up this device again", SettingsPlace.SETUP, "first run setup welcome start over speakers copy settings menu tidy"),
        // Your watching
        SettingEntry("Your year", SettingsPlace.STATS, "stats statistics wrapped top shows genres"),
        SettingEntry("Watch diary", SettingsPlace.DIARY, "letterboxd ratings notes log"),
        SettingEntry("Watch groups", SettingsPlace.PARTNERS, "partners together schedule privacy private account watch party group watch session watch now together titles setup"),
        SettingEntry("Comic catalogs", SettingsPlace.CATALOGS, "opds komga kavita calibre clu books"),
        SettingEntry("Control a device", SettingsPlace.REMOTE, "remote tv play on cast", P),
        // Tools
        SettingEntry("Checking how things play", SettingsPlace.GUIDE, "guide help hdr surround stutter troubleshooting"),
        SettingEntry("Playback test", SettingsPlace.PLAYBACK_TEST, "device check samples decoder", T),
        SettingEntry("Audio channel test", SettingsPlace.AUDIO_TEST, "speakers voice each channel surround", T),
        SettingEntry("TV & receiver check", SettingsPlace.TV_CHECK, "hdr dolby vision atmos clips receiver shows", T),
        SettingEntry("Library playback overview", SettingsPlace.OVERVIEW, "converted transcoded titles test play"),
        SettingEntry("Server speed check", SettingsPlace.SPEED_CHECK, "slow network latency", T),
        SettingEntry("Query analyzer", SettingsPlace.QUERY_ANALYZER, "requests slow server load"),
        SettingEntry("Household devices", SettingsPlace.HOUSEHOLD, "admin devices reports", adminOnly = true),
        SettingEntry("Library health", SettingsPlace.HEALTH, "missing artwork duplicates metadata", adminOnly = true),
        SettingEntry("Library images overview", SettingsPlace.IMAGES, "images pictures artwork posters backdrops logos missing small size space storage disk", adminOnly = true),
        SettingEntry("Manage server", SettingsPlace.MANAGE_SERVER, "libraries tasks users activity admin dashboard", adminOnly = true),
        // About
        SettingEntry("Updates", SettingsPlace.UPDATES, "version new release update"),
        SettingEntry("Plugins", SettingsPlace.PLUGINS, "extras channels partners writing"),
        SettingEntry("Open-source licences", SettingsPlace.LICENCES, "licenses credits ffmpeg"),
    )

    /**
     * The settings matching [query] on this app ([tv]), best first: every word typed must start a word
     * of the setting's name, its page or its other words; names matching come before the rest.
     */
    fun find(query: String, tv: Boolean, admin: Boolean = false, limit: Int = 12): List<SettingEntry> {
        val typed = words(query)
        if (typed.isEmpty()) return emptyList()
        return entries
            .filter { it.on == SettingsOn.BOTH || (it.on == SettingsOn.TV) == tv }
            .filter { admin || !it.adminOnly }
            .mapNotNull { e ->
                val title = words(e.title)
                val all = title + words(e.place.path) + words(e.words)
                if (!typed.all { t -> all.any { it.startsWith(t) } }) return@mapNotNull null
                // Names first: how many typed words start a word of the name, then whether the name starts with the first.
                val inTitle = typed.count { t -> title.any { it.startsWith(t) } }
                val starts = if (title.firstOrNull()?.startsWith(typed.first()) == true) 1 else 0
                e to (inTitle * 10 + starts * 5 - title.size)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}.:]+")).map { it.trim('.', ':') }.filter { it.isNotEmpty() }
}
