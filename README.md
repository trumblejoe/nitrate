# Nitrate

A [Jellyfin](https://jellyfin.org) client for Android TV and Android phones/tablets (iOS planned), built with Kotlin and Jetpack Compose.

> Named after nitrate film, the early film stock known for its glow on the screen.

## Status

Early, but usable end to end:

- **Sign-in:** servers on your network are found automatically, or enter an address. Pick a user tile, type a password, or use **Quick Connect** to approve the TV from your phone
- **Home:** My Media, Continue Watching, Next Up, and Latest per library
- **Library pages:** a *Recommended* tab with rows per library type, and an *All* tab with a sortable, paged grid
- **Row display settings:** every row on Home and on each library page can be Posters, Wide or **Showcase**, in three sizes, saved per user. Long-press a card (or use *Customize rows*) to change one
- **Showcase rows:** focused, the row fills the screen with the item's artwork, logo, details and overview, and Left/Right step through it. Optional wrap-around and backdrop rotation
- **Detail pages:** movies, episodes and series (season chips and episode row), with resume/from start, watched, favourite, cast and similar items
- **Playlists:** large tiles (stylized art when a playlist has no wide image), an optional progress count or bar, grouping by tag with a jump list, and a playlist page with a cinematic header, thumbnails, Play all and Shuffle
- **Playback:** a device profile built from the TV's real decoders, HDR formats and HDMI audio passthrough; the server picks direct play, remux or transcode. A direct-play decoder failure falls back to a transcode and is remembered for that format. Progress is reported to the server
- **Player:** D-pad controls with seek bar, audio and subtitle selection (including external subtitle files), and an Info panel showing the play method, the server's transcode reasons and the decoders in use
- **Playback settings (per TV):** bitrate and resolution caps, and an Auto / Always / Never override for every video codec, HDR format and audio codec, alongside what the TV reports and any failures learned on it
- **Playback test:** plays bundled clips (H.264, HEVC incl. 10-bit, HDR10, HLG and Dolby Vision profiles 5 and 8.1, VP9, AV1, MPEG-2; AAC, MP3, Dolby Digital/Plus, DTS, TrueHD, Opus, Vorbis, FLAC) and compares what really plays with what the TV claims, then suggests overrides. Detail pages have a **Playback info** button showing what the server would do for that item
- **Audio channel test:** walks through every speaker in stereo, 5.1 (Dolby Digital, Dolby Digital Plus, TrueHD, DTS, AAC, PCM) and 7.1 (PCM). Each speaker says its name, then plays noise, lit up on a room map; select a speaker to repeat it. It shows whether the TV passes the bitstream to your receiver or decodes it, and warns when it is downmixing
- **Plugins** (Home → Plugins), built in and switchable per TV:
  - **Unwatched previous episode:** before an episode plays, checks that what comes before it has been watched — including specials placed before, during or after seasons, or after the finale — and spots gaps in episode or season numbering that mean something is missing from your library. Offers to play the earlier one, mark it watched, or play anyway. Looks at just the previous episode, the season, or the whole series
  - **Writing pane:** a Fountain screenplay editor for a Bluetooth keyboard, beside the video while it keeps playing (Ctrl+E or *Write* in the player; on a phone, turn it upright for video on top and room for the keyboard) or full screen from Home. Highlights scene headings, characters and more as you type, has element shortcuts (Ctrl+1–6) and video keys (Ctrl+K play/pause, Ctrl+J/L seek), autosaves, backs up to your Jellyfin account (queued and retried when offline), and imports and exports Fade In's own `.fadein` files as well as `.fountain`. An optional **computer transfer** page (off until you switch it on in Documents, PIN-protected, turns itself off after 15 minutes) lets a browser on your network download and upload scripts
- **Phone and tablet look:** a full-bleed hero carousel with parallax, screens tinted by each title's artwork, a floating glass navigation pill, and artwork that morphs from the card you tap into its page
- **Search:** as you type, across movies, shows, episodes, collections and people, with recent searches and pages for each person
- **Player (phone and tablet):** double-tap the sides to skip 10 s (taps add up), double-tap the middle to pause, swipe up/down on the left for brightness and on the right for volume, pinch to fill the screen, and thumbnail previews while scrubbing (when the server has trickplay images). **Skip intro / recap / credits** buttons when the server marks them (media segments, e.g. from an intro-detection plugin), and an **Up Next** card with a countdown that rolls into the next episode in viewing order, specials included, also from downloads when offline
- **Smart downloads:** per show, keep the next 1–10 unwatched episodes downloaded; episodes it added are deleted once watched (here or anywhere), topped up every few hours and after you watch
- **Downloads (phone and tablet):** download a movie, episode or whole season as the original file or as a smaller copy your server converts (1080p, 720p, 480p or 360p, H.264 or HEVC, AAC stereo), with the size shown before you choose. Downloads run in the background with a progress notification, can wait for Wi-Fi, resume after an interruption (originals), and keep their subtitles and artwork. Downloaded items play from the device even when online, and what you watch offline is sent to the server when it's back (if you didn't watch it elsewhere since)
- **Offline:** if the server can't be reached at startup, Nitrate opens an offline home with plugin pages such as Writing, and returns to normal once the server answers
- **Settings sync:** row and view settings are stored in your Jellyfin display preferences, so they follow you to other TVs

## Roadmap

- [ ] Search
- [ ] Skip intro and credits (media segments)
- [ ] Remember preferred audio and subtitle languages per series
- [ ] Multiple servers and users

## Stack

| Area | Library |
| --- | --- |
| UI | Jetpack Compose, `androidx.tv:tv-material` |
| Navigation | Navigation Compose (type-safe routes) |
| Server API | Nitrate's own multiplatform Jellyfin client ([Ktor](https://ktor.io) + kotlinx.serialization), contract-tested against Jellyfin's OpenAPI spec |
| Playback | AndroidX Media3 ExoPlayer |
| Images | Coil 3 |
| Storage | DataStore Preferences |

Build tooling: AGP 9.5, Gradle 9.9, Kotlin 2.4, JDK 17+. compileSdk 37.2, minSdk 24, targetSdk 36.

## Testing surround formats Nitrate can't bundle

The audio channel test also lists files from your own server whose title contains **Nitrate Test**. They are always played as the original file, so the real bitstream (TrueHD, E-AC-3 7.1, DTS-HD, Atmos) reaches your receiver.

- **Map-following walk-throughs:** encode the bundled master `app/src/main/assets/channeltest/71_flac.mka` (or `51_flac.mka`) with your own encoder, e.g. Dolby Media Encoder for 7.1 TrueHD or E-AC-3, and title it with *Walk* and the layout, e.g. `Nitrate Test Walk 7.1 TrueHD`. Speakers take 3-second turns in the order FL, FR, C, LFE, then RL, RR, SL, SR for 7.1 (SL, SR for 5.1), and the map lights each one.
- **Any other test file** (a receiver maker's or Dolby/DTS demo): title it `Nitrate Test …` with the layout, e.g. `Nitrate Test Atmos demo 7.1`. It plays with the output readout (bitstream or PCM and channel count), without the map following.

## Testing

```bash
./gradlew test :core:jvmTest
```

Unit tests cover the device profile, the playback-test advisor, downloads, and the Jellyfin client, which `JellyfinContractTest` checks against Jellyfin's OpenAPI spec (`tools/trim-openapi.py`). `JellyfinLiveTest` runs the client and downloads against a real server when `JELLYFIN_URL`, `JELLYFIN_USER` and `JELLYFIN_PASSWORD` are set. The test clips in `app/src/main/assets/testclips` are generated by `tools/generate-test-clips.sh`, plus `tools/generate-dolby-vision-clips.sh` for Dolby Vision (needs [dovi_tool](https://github.com/quietvoid/dovi_tool) and Dolby's [mp4muxer](https://github.com/DolbyLaboratories/dlb_mp4base); it checks the profile and that every frame carries Dolby Vision metadata), and the speaker walk-throughs in `app/src/main/assets/channeltest` by `tools/generate-channel-test.sh` (needs ffmpeg with libflite, and [dcaenc](https://gitlab.com/patrakov/dcaenc) for DTS). The latter runs `tools/check-channel-isolation.py`, which fails if any channel carries sound outside its own slot.

## Building

Requires the Android SDK (platform 37.2). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```bash
./gradlew assembleDebug
```

The APKs land in `tv/build/outputs/apk/debug/` and `mobile/build/outputs/apk/debug/`. Install with:

```bash
adb install -r tv/build/outputs/apk/debug/tv-debug.apk
```

Debug builds use the application IDs `dev.nitrate.tv.debug` and `dev.nitrate.app.debug`, so they sit alongside release builds.

## Releases

`.github/workflows/release.yml` builds signed release APKs for both apps and publishes them on the repository's Releases page:

- **Release:** push a tag such as `v0.2.0`.
- **Test build:** Actions → Release → *Run workflow*, on any branch. It is published as a pre-release named `dev-<run number>`.

Every build has a higher version code, so it installs over the previous one. On a phone, open the release on GitHub and tap the `nitrate-mobile-….apk` (allow installs from your browser when asked). On a TV, use an app such as Downloader, or `adb install -r nitrate-tv-….apk`.

### Dev builds (no setup)

Until a release key is set up, every build is signed with the public dev key in `signing/` and published as a **Nitrate Dev** pre-release. Dev builds install as their own apps (`dev.nitrate.app.dev`, `dev.nitrate.tv.dev`), update from one dev build to the next, and never clash with real releases. The key isn't secret, so only install dev builds from this repository's Releases page.

### Signing key (for real releases)

Android only updates an app with an APK signed by the same key, so releases use a key kept in the repository's secrets. Create it once on your computer (needs a JDK for `keytool`) and keep a backup: losing it means uninstalling before the next release can install.

```bash
keytool -genkeypair -keystore nitrate-release.jks -alias nitrate -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Nitrate"
```

Then add three secrets under Settings → Secrets and variables → Actions, or with the GitHub CLI:

```bash
base64 < nitrate-release.jks | tr -d '\n' | gh secret set NITRATE_KEYSTORE_BASE64 -R trumblejoe/nitrate
```

```bash
gh secret set NITRATE_KEYSTORE_PASSWORD -R trumblejoe/nitrate
```

```bash
gh secret set NITRATE_KEY_ALIAS -R trumblejoe/nitrate --body nitrate
```

`keytool` asks for one password; use it for `NITRATE_KEYSTORE_PASSWORD`. Without the secrets the workflow makes a dev build instead; local `assembleRelease` builds are signed with the debug key.

## Project layout

| Module | What it is |
| --- | --- |
| `core` | Kotlin Multiplatform (JVM + iOS): Nitrate's own models, the `MediaServer` interface and its Jellyfin client, and all platform-neutral logic (rows, playback rules and test advisor, episode order, Fountain/.fadein, transfer server, plugins). |
| `core-android` | Android implementation: HTTP engine and device identity for the Jellyfin client, on-device storage, the decoder/display probe, `PlaybackSession` (Media3), settings sync, shared view models and `AppContainer`. |
| `tv` | Android TV app (Compose for TV), application ID `dev.nitrate.tv`. |
| `mobile` | Android phone/tablet app (Material 3, bottom bar on phones, side rail on tablets), application ID `dev.nitrate.app`. Screens use only multiplatform-ready libraries; Android-only pieces live in its `platform` package. |

Builds: `./gradlew assembleDebug` produces `tv/build/outputs/apk/debug/tv-debug.apk` and `mobile/build/outputs/apk/debug/mobile-debug.apk`. `./gradlew :mobile:testDebugUnitTest` also renders phone and tablet screenshots against a fake server into `mobile/build/outputs/roborazzi/`.

### Toward iOS

Phase 2 (done) made `core` Kotlin Multiplatform with its own Jellyfin client, so the server code already compiles for iOS; phase 3 moves `mobile`'s screens to Compose Multiplatform with an AVPlayer/VLCKit player chosen per file. iOS builds need macOS (GitHub's macOS runners can build and upload to TestFlight).

## Notes

Nitrate is an independent client and is not affiliated with the Jellyfin project.
