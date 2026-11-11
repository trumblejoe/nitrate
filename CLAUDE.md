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
