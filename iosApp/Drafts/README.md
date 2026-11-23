# Drafts (not built)

This folder holds Swift code that hasn't been built yet, because it needs a Mac. None of these files are in `project.yml`'s sources, so CI never compiles them. Until one is switched on, the app works as before: no Chromecasts are listed, and EPUBs open in Nitrate's own reader.

The Kotlin side is done and compiles:

- **Cast:** `useCast(sdk)` in ios-app `CastBridge.kt` feeds mobile-ui `IosCast`, which is what `rememberCast()` returns.
- **Readium:** `useBookReader(reader)` in ios-app `BookBridge.kt` is what `IosPlatform.epubReader()` uses.

To switch one on, on macOS with Xcode:

1. Move the file into `iosApp/Nitrate/`.
2. Add its package to `project.yml` and to the target's `dependencies`. Each file names its package at the top.
3. For Cast only, add these to Info.plist:
   - `NSLocalNetworkUsageDescription`
   - `NSBonjourServices: [_googlecast._tcp, _F007D354._googlecast._tcp]`
4. In `NitrateApp.swift`, call it next to `MpvBridgeKt.useMpv`:
   - `CastBridgeKt.useCast(sdk: NitrateCast())`
   - `BookBridgeKt.useBookReader(reader: ReadiumReader())`
5. Run `xcodegen` and build. Where an SDK version has changed something, fix it; those places are marked TODO.
