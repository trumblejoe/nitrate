package dev.nitrate.core

/** A component Nitrate ships, its licence, and where its source is. */
data class OpenSourceComponent(
    val name: String,
    val license: String,
    /** The licence's full text, by file name under the app's `licenses/` assets. */
    val licenseText: String,
    val url: String,
    val note: String? = null,
)

/** What's inside Nitrate that others wrote, for the Open-source licences page. */
object OpenSource {
    private const val APACHE = "Apache License 2.0"

    val components = listOf(
        OpenSourceComponent(
            "FFmpeg", "GNU LGPL 2.1 or later", "LGPL-2.1.txt", "https://ffmpeg.org",
            note = "Decodes DTS, Dolby TrueHD, Dolby Digital (Plus) and ALAC, and H.264 (including 10-bit), MPEG-2, " +
                "MPEG-4 Part 2 and VC-1 video up to 1080p, when the device can't. Built from FFmpeg release/6.0 with only " +
                "those decoders (plus libswscale and libswresample) and nothing GPL or non-free; it's a separate library " +
                "(libffmpegJNI.so) the app loads at run time. Source: https://ffmpeg.org/download.html; build script and " +
                "options: tools/build-ffmpeg.sh in https://github.com/trumblejoe/nitrate.",
        ),
        OpenSourceComponent(
            "libass", "ISC License", "ISC-libass.txt", "https://github.com/libass/libass",
            note = "Draws styled (ASS/SSA) subtitles with their own fonts, colours and positions, in a layer over the " +
                "picture. libass 0.17.3 in a separate library (libassJNI.so) with FreeType, HarfBuzz and FriBidi; " +
                "build script and options: tools/build-libass.sh in https://github.com/trumblejoe/nitrate.",
        ),
        OpenSourceComponent(
            "FreeType", "FreeType License (FTL)", "FTL.txt", "https://freetype.org",
            note = "Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved. " +
                "Used by libass to draw subtitle fonts.",
        ),
        OpenSourceComponent("HarfBuzz", "MIT License (\"Old MIT\")", "MIT-HarfBuzz.txt", "https://harfbuzz.github.io",
            note = "Text shaping for libass."),
        OpenSourceComponent(
            "GNU FriBidi", "GNU LGPL 2.1 or later", "LGPL-2.1.txt", "https://github.com/fribidi/fribidi",
            note = "Right-to-left text for libass, linked into the separate library libassJNI.so. Source: " +
                "https://github.com/fribidi/fribidi/releases (1.0.16); build script: tools/build-libass.sh in " +
                "https://github.com/trumblejoe/nitrate.",
        ),
        OpenSourceComponent("AndroidX Media3 (ExoPlayer, FFmpeg extension)", APACHE, "Apache-2.0.txt", "https://github.com/androidx/media"),
        OpenSourceComponent("AndroidX and Jetpack Compose", APACHE, "Apache-2.0.txt", "https://developer.android.com/jetpack/androidx"),
        OpenSourceComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-io", APACHE, "Apache-2.0.txt", "https://github.com/JetBrains/kotlin"),
        OpenSourceComponent("Ktor", APACHE, "Apache-2.0.txt", "https://github.com/ktorio/ktor"),
        OpenSourceComponent("OkHttp and Okio", APACHE, "Apache-2.0.txt", "https://github.com/square/okhttp"),
        OpenSourceComponent("Coil", APACHE, "Apache-2.0.txt", "https://github.com/coil-kt/coil"),
        OpenSourceComponent("ZXing (QR codes, TV only)", APACHE, "Apache-2.0.txt", "https://github.com/zxing/zxing"),
    )
}
