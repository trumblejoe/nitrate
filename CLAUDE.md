# Nitrate

Jellyfin client for Android TV, Android phones/tablets, and (later) iOS. Kotlin (Multiplatform core), Compose, Media3, Ktor.

## Modules

- `:core` – Kotlin Multiplatform (jvm, iosArm64, iosSimulatorArm64). Nitrate's own models (`model/`), the `MediaServer` interface, the Jellyfin client (`jellyfin/`, Ktor + kotlinx.serialization), and all platform-neutral logic: rows, playback rules and test advisor, episode order, Fountain/.fadein, plugin logic. Code lives in `commonMain`; only the transfer server is in `jvmMain`. No `java.*`/`android.*` in `commonMain` — `./gradlew :core:compileKotlinIosArm64` catches it on Linux.
- `:core-android` – Android side of `:core`: OkHttp engine and device identity for `JellyfinClient`, DataStore stores, `DeviceProbe`, `PlaybackSession`, `SettingsSync`, `WritingStore`, and the shared `AppContainer`.
- `:tv` – Android TV app (Compose for TV).
- `:mobile` – Android phone/tablet app (Material 3), written only against `:core` and `:core-android`'s container so it can move to Compose Multiplatform.

## Commands

- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew test`
- If Maven Central returns 429, retry with `--max-workers=2`.

## Conventions

- Screens use `:core` models (`MediaItem`, `PlaybackPlan`…) and talk to the server only through `MediaServer`. Only `core/jellyfin` knows Jellyfin's API.
- Ids are strings (dashed lowercase UUIDs, as stored in settings and row keys) and times are milliseconds; conversion from Jellyfin's dashless ids and ticks happens in `core/jellyfin/Mappers.kt`.
- Jellyfin JSON is PascalCase (`JellyfinJson` maps camelCase DTO fields). DTOs are internal and hold only fields Nitrate reads. When calling a new endpoint, add it to `tools/trim-openapi.py`, regenerate `core/src/jvmTest/resources/jellyfin-openapi.json`, and exercise it in `JellyfinContractTest`, which checks paths, query parameters, body fields and enum values against the spec.
- TV: use `androidx.tv.material3` components so focus and D-pad behaviour are correct; every interactive element must be reachable and visibly focused with a D-pad.
- Dependency versions live in `gradle/libs.versions.toml`.
- Rows (`core/rows/MediaRow`) have a stable `key` such as `home/next_up` or `library/<id>/recently_added`. Display settings are saved against it, so never rename a key.
- TV home and library pages both render through `ui/rows/RowsPage`; add row behaviour there, not per screen.
- Playback capability ids are a codec name (`hevc`, `eac3`) or `range:<HDR format>`. Playback settings are per device and must never be synced; display settings are per user and sync through `SettingsSync`.
- Plugins are compiled in (no downloaded code). Logic lives in `:core` (`NitratePlugin`, `PlaybackGuard`) and is registered in `AppContainer.plugins`; each app adds its screens for a plugin by `pluginId` (TV: `TV_PLUGIN_UI`). A plugin's `id` is a storage key: never change it.
- Downloads: logic in `core/downloads` (`DownloadManager`, common Kotlin with kotlinx-io); Android runs it through WorkManager (`core-android/downloads`), UI in `:mobile` only. A downloaded item plays from the device (`PlayerRoute(local = true)`) and its watch state is recorded locally and sent by `syncWatchState`, never through `/Sessions` reporting. Transcoded downloads need a fresh `playSessionId` each time or the server reuses an old transcode. Jellyfin 12.4 rejects `api_key` query auth: always send the Authorization header.
- Mobile look: screens sit in `TintedBackdrop` (tint from `rememberArtworkColor`), use `accentColor()` for highlights, `SectionHeader`, `PillButton`, `RoundAction`, and `pressable()` for touch feedback. Artwork that should morph into the page it opens gets a `sharedKey`/`sharedArtwork(key)` unique on its screen, passed on as `DetailsRoute.artKey`. Leave `NavBarClearance` at the bottom of tab screens for the floating nav pill. Screenshot tests use the abstract art from `tools/generate-sample-art.py`.
- Every single-item "play" goes through `PluginRegistry.checkBeforePlay` (TV: `PlaybackGate`) so guards run; queued playback (playlists) deliberately bypasses it.
- Other Jellyfin clients (e.g. Wholphin, GPL) may inspire features, but never copy their code.
