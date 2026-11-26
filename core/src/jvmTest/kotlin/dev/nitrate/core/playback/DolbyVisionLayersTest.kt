package dev.nitrate.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** MEL or FEL in the player's Info (once seen while playing). */
class DolbyVisionLayersTest {
    private fun path(layers: DolbyVisionLayers?, mode: Profile7Playback) = VideoPath(
        inRange = "Dolby Vision", fileRange = "Dolby Vision (profile 7)", profile7 = mode, dvLayers = layers,
    ).hdrPath()

    @Test fun infoSaysWhichKind() {
        assertEquals("Dolby Vision (profile 7) → Dolby Vision (FEL, converted to profile 8.1 here)", path(DolbyVisionLayers.FEL, Profile7Playback.AS_PROFILE_8))
        assertEquals("Dolby Vision (profile 7) → Dolby Vision (MEL, its base layer)", path(DolbyVisionLayers.MEL, Profile7Playback.BASE_LAYER))
        assertEquals("Dolby Vision (profile 7) → Dolby Vision (converted to profile 8.1 here)", path(null, Profile7Playback.AS_PROFILE_8))
    }
}
