# Nitrate

An Android TV client for [Jellyfin](https://jellyfin.org), built with Kotlin and Jetpack Compose for TV.

> Named after nitrate film, the early film stock known for its glow on the screen.

## Status

Early scaffold. Working today:

- Connect to a server by address (http/https and default ports are tried automatically)
- Sign in with username and password; session is remembered
- Home screen: Continue Watching, Next Up, and Latest per video library, with a backdrop and details for the focused item
- Direct-play video with Media3/ExoPlayer, resuming from the saved position
- Playback start, progress, and stop reported back to the server

## Roadmap

- [ ] Detail screens for movies, series, seasons
- [ ] Library browsing with sort and filter
- [ ] Transcoding fallback (PlaybackInfo + device profile) for unsupported codecs
- [ ] Audio and subtitle track selection
- [ ] Quick Connect sign-in (no typing passwords on a remote)
- [ ] Multiple servers and users
- [ ] Search
- [ ] Skip intro and credits (media segments)

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
│   └── JellyfinRepository.kt  All Jellyfin SDK calls
└── ui/
    ├── NitrateNavHost.kt  Routes and start-destination logic
    ├── theme/             Colors and TV Material theme
    ├── components/        Shared composables (D-pad text field)
    ├── connect/  login/  home/  player/
```

## Notes

Nitrate is an independent client and is not affiliated with the Jellyfin project.
