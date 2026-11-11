# Nitrate

Jellyfin client for Android TV, Android phones/tablets, and (later) iOS. Kotlin, Compose, Media3, Jellyfin Kotlin SDK 1.9.

## Modules

- `:core` – plain Kotlin, no Android or SDK imports. Nitrate's own models (`model/`), the `MediaServer` interface, and all platform-neutral logic: rows, playback rules and test advisor, episode order, Fountain/.fadein, transfer server, plugin logic. Becomes Kotlin Multiplatform for iOS, so keep it free of `android.*` and Jellyfin SDK types.
- `:core-android` – Android implementation of `:core`: `JellyfinServer` (the only code that touches the Jellyfin SDK), DataStore stores, `DeviceProbe`, `SettingsSync`, `WritingStore`, and the shared `AppContainer`.
- `:tv` – Android TV app (Compose for TV).
- `:mobile` – Android phone/tablet app (Material 3), written only against `:core` and `:core-android`'s container so it can move to Compose Multiplatform.

## Commands

- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew test`
- If Maven Central returns 429, retry with `--max-workers=2`.

## Conventions

- Screens use `:core` models (`MediaItem`, `PlaybackPlan`…) and talk to the server only through `MediaServer`. Never import `org.jellyfin` outside `:core-android/jellyfin`.
- Ids are strings and times are milliseconds in `:core`; conversion from Jellyfin UUIDs and ticks happens in `jellyfin/Mappers.kt`.
- SDK 1.9 accessor names are singular: `libraryApi` (items, resume, latest), `showApi`, `userViewApi`, `videoApi`, `sessionApi` (playback reporting), `authenticationApi`.
- TV: use `androidx.tv.material3` components so focus and D-pad behaviour are correct; every interactive element must be reachable and visibly focused with a D-pad.
- Dependency versions live in `gradle/libs.versions.toml`.
- Rows (`core/rows/MediaRow`) have a stable `key` such as `home/next_up` or `library/<id>/recently_added`. Display settings are saved against it, so never rename a key.
- TV home and library pages both render through `ui/rows/RowsPage`; add row behaviour there, not per screen.
- Playback capability ids are a codec name (`hevc`, `eac3`) or `range:<HDR format>`. Playback settings are per device and must never be synced; display settings are per user and sync through `SettingsSync`.
- Plugins are compiled in (no downloaded code). Logic lives in `:core` (`NitratePlugin`, `PlaybackGuard`) and is registered in `AppContainer.plugins`; each app adds its screens for a plugin by `pluginId` (TV: `TV_PLUGIN_UI`). A plugin's `id` is a storage key: never change it.
- Every single-item "play" goes through `PluginRegistry.checkBeforePlay` (TV: `PlaybackGate`) so guards run; queued playback (playlists) deliberately bypasses it.
- Other Jellyfin clients (e.g. Wholphin, GPL) may inspire features, but never copy their code.
