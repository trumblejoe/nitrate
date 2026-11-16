package dev.nitrate.mobile

import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.media3.test.utils.TestUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.downloads.Mp4Index
import kotlinx.io.files.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * A converted download (fragmented MP4, as the server streams it) has no length and can't seek
 * in the player until Nitrate indexes it: checked with Media3's own MP4 reader.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConvertedDownloadSeekTest {
    private fun seekMap(file: File) = TestUtil.extractSeekMap(
        FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED), FakeExtractorOutput(),
        DefaultDataSource(ApplicationProvider.getApplicationContext(), false), Uri.fromFile(file),
    )

    @Test fun `an indexed conversion has a length and seeks`() {
        val file = File.createTempFile("converted", ".mp4").apply { deleteOnExit() }
        File("../core/src/jvmTest/resources/downloads/converted-fragmented.mp4").copyTo(file, overwrite = true)

        val before = seekMap(file)
        assertFalse(before.isSeekable)

        assertTrue(Mp4Index.addIndex(Path(file.path)))
        val after = seekMap(file)
        assertTrue(after.isSeekable)
        assertEquals(4.0, after.durationUs / 1_000_000.0, 0.1)
        // A seek to 2.5 s lands on the fragment holding it.
        val points = after.getSeekPoints(2_500_000)
        assertTrue(points.first.timeUs <= 2_500_000 && points.first.timeUs >= 1_500_000)
    }
}
