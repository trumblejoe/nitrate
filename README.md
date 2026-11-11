# Nitrate

An Android TV client for [Jellyfin](https://jellyfin.org), built with Kotlin and Jetpack Compose for TV.

> Named after nitrate film, the early film stock known for its glow on the screen.

## Status

Early, but usable end to end:

- **Sign-in:** connect by address (http/https and default ports tried automatically); the session is remembered
- **Home:** My Media, Continue Watching, Next Up, and Latest per library
- **Library pages:** a *Recommended* tab with rows per library type, and an *All* tab with a sortable, paged grid
- **Row display settings:** every row on Home and on each library page can be Posters, Wide or **Showcase**, in three sizes, saved per user. Long-press a card (or use *Customize rows*) to change one
- **Showcase rows:** focused, the row fills the screen with the item's artwork, logo, details and overview, and Left/Right step through it. Optional wrap-around and backdrop rotation
- **Detail pages:** movies, episodes and series (season chips and episode row), with resume/from start, watched, favourite, cast and similar items
- **Playlists:** large tiles (stylized art when a playlist has no wide image), an optional progress count or bar, grouping by tag with a jump list, and a playlist page with a cinematic header, thumbnails, Play all and Shuffle
- **Playback:** a device profile built from the TV's real decoders; the server picks direct play, remux or transcode, and a direct-play failure falls back to a transcode. Progress is reported to the server

## Roadmap

- [ ] Audio and subtitle track selection, and a custom player overlay
- [ ] Quick Connect sign-in (no typing passwords on a remote)
- [ ] Search
- [ ] Skip intro and credits (media segments)
- [ ] Settings screen (max bitrate, rewatching in Next Up)
- [ ] Multiple servers and users
- [ ] Sync row settings to the server so they follow the user between devices

## Stack

| Area | Library |
| --- | --- |
| UI | Jetpack Compose, `androidx.tv:tv-material` |
| Navigation | Navigation Compose (type-safe routes) |
| Server API | [Jellyfin Kotlin SDK](https://github.com/jellyfin/jellyfin-sdk-kotlin) |
| Playback | AndroidX Media3 ExoPlayer |
| Images | Coil 3 |
| Storage | DataStore Preferences |

Build tooling: AGP 9.5, Gradle 9.9, Kotlin 2.4, JDK 17+. compileSdk 37.2, minSdk 24, targetSdk 36.

## Building

Requires the Android SDK (platform 37.2). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Install on a TV or emulator with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the application ID `dev.nitrate.tv.debug`, so it can sit alongside a release build.

## Project layout

```
app/src/main/java/dev/nitrate/tv/
├── NitrateApp.kt          Application; builds the AppContainer and Coil image loader
├── MainActivity.kt        Single activity, hosts Compose
├── data/
│   ├── AppContainer.kt    Manual dependency wiring
│   ├── SessionStore.kt    Saved server URL, token, user (DataStore)
│   ├── JellyfinRepository.kt  All Jellyfin SDK calls
│   ├── rows/              Row model, display options, per-user settings store
│   └── playback/          Device profile built from the TV's decoders
└── ui/
    ├── NitrateNavHost.kt  Routes and start-destination logic
    ├── theme/             Colors and TV Material theme
    ├── components/        Shared composables (D-pad text field)
    ├── rows/              Shared rows page, card and showcase rows, row settings dialogs
    ├── details/           Movie / series / episode pages
    ├── library/           Library pages, grids, playlists library
    ├── playlist/          Playlist page
    ├── connect/  login/  home/  player/
```

## Notes

Nitrate is an independent client and is not affiliated with the Jellyfin project.
