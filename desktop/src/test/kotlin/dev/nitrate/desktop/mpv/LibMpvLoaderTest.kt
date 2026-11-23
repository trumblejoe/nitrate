package dev.nitrate.desktop.mpv

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Windows release checks its own libmpv build here (NITRATE_REQUIRE_MPV=1 with
 * NITRATE_MPV_DIR at desktop/resources/windows-x64): every DLL it needs is beside it, it speaks
 * client API 2.x and starts a player. Skipped elsewhere.
 */
class LibMpvLoaderTest {
    @Test
    fun `the libmpv shipped with the app loads and starts`() {
        assumeTrue("only when asked to (the Windows release)", System.getenv("NITRATE_REQUIRE_MPV") == "1")
        val lib = when (val r = LibMpvLoader.load()) {
            is LibMpvLoader.Result.Loaded -> r
            is LibMpvLoader.Result.Missing -> fail(r.reason)
        }
        assertTrue(lib.version.startsWith("2."), lib.version)
        val api = JnaMpvApi.create(lib) ?: fail("mpv_create failed")
        try {
            api.setOption("vo", "null")
            api.setOption("ao", "null")
            assertTrue(api.initialize() >= 0, "mpv starts")
            // The LGPL build: mpv says so in its configuration.
            val config = api.getString("mpv-configuration").orEmpty()
            assertTrue("-Dgpl=false" in config, "mpv must be the LGPL build (-Dgpl=false), not: $config")
        } finally {
            api.destroy()
        }
    }
}
