# Nitrate

Android TV Jellyfin client. Kotlin, Jetpack Compose for TV (`androidx.tv.material3`), Media3, Jellyfin Kotlin SDK 1.9.

## Commands

- Build: `./gradlew assembleDebug`
- If Maven Central returns 429, retry with `--max-workers=2`.

## Conventions

- All Jellyfin SDK calls go through `data/JellyfinRepository.kt`; screens never touch `ApiClient` directly.
- SDK 1.9 accessor names are singular: `libraryApi` (items, resume, latest), `showApi`, `userViewApi`, `videoApi`, `sessionApi` (playback reporting), `authenticationApi`.
- Use `androidx.tv.material3` components (not `androidx.compose.material3`) so focus and D-pad behaviour are correct.
- Every interactive element must be reachable and visibly focused with a D-pad; no touch-only UI.
- Dependency versions live in `gradle/libs.versions.toml`.
- Rows (`data/rows/MediaRow`) have a stable `key` such as `home/next_up` or `library/<id>/recently_added`. Display settings are saved against it, so never rename a key.
- Home and library pages both render through `ui/rows/RowsPage`; add row behaviour there, not per screen.
- Other Jellyfin clients (e.g. Wholphin, GPL) may inspire features, but never copy their code.
