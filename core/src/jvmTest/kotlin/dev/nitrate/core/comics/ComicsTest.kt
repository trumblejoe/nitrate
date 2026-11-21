package dev.nitrate.core.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicsTest {
    @Test fun pagesAreImagesInNaturalOrder() {
        val entries = listOf(
            "Saga 001/page10.jpg", "Saga 001/page2.jpg", "Saga 001/page1.JPG", "ComicInfo.xml",
            "__MACOSX/Saga 001/._page1.jpg", "Saga 001/.hidden.png", "Saga 001/notes.txt", "Saga 001/page02b.webp",
        )
        assertEquals(listOf("Saga 001/page1.JPG", "Saga 001/page2.jpg", "Saga 001/page02b.webp", "Saga 001/page10.jpg"), ComicPages.order(entries))
    }

    @Test fun comicInfoGivesWhatTheReaderNeeds() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo>
              <Series>Moon &amp; Sun</Series><Number>3</Number><Title>Tides</Title>
              <PageCount>24</PageCount><Manga>YesAndRightToLeft</Manga>
              <Pages>
                <Page Image="0" Type="FrontCover" />
                <Page Image="11" DoublePage="True" />
                <Page Image="12" Type="Story"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val info = ComicInfo.parse(xml)
        assertEquals("Moon & Sun", info.series)
        assertEquals("3", info.number)
        assertEquals(24, info.pageCount)
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, info.direction)
        assertEquals(setOf(11), info.doublePages)
        assertEquals(ComicInfo(), ComicInfo.parse("not xml at all"))
    }

    @Test fun spreadsPairFacingPages() {
        // The cover alone, then pairs; the last page alone when it has no partner.
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), Spreads.of(6, twoUp = true).map { it.pages })
        // A double-page image stands alone, and so does the page before it.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), Spreads.of(5, twoUp = true, wide = setOf(2)).map { it.pages })
        // Right to left: the pair is shown the other way round.
        assertEquals(listOf(listOf(0), listOf(2, 1)), Spreads.of(3, twoUp = true, direction = ReadingDirection.RIGHT_TO_LEFT).map { it.pages })
        // One page at a time, and the strip, are page by page.
        assertEquals(3, Spreads.of(3, twoUp = false).size)
        assertEquals(3, Spreads.of(3, twoUp = true, direction = ReadingDirection.VERTICAL).size)
        assertEquals(2, Spreads.indexOf(Spreads.of(6, twoUp = true), 4))
    }

    @Test fun progressIsThePage() {
        assertEquals(12_000L, ComicProgress.positionMs(12))
        assertEquals(12, ComicProgress.page(12_000, 24))
        assertEquals(23, ComicProgress.page(99_000, 24))
        assertTrue(ComicProgress.finished(23, 24))
        assertFalse(ComicProgress.finished(22, 24))
        assertEquals("13 / 24", ComicProgress.label(12, 24))
    }

    @Test fun arrowsFollowTheReadingDirection() {
        assertEquals(1, ReaderKeys.step(right = true, ReadingDirection.LEFT_TO_RIGHT))
        assertEquals(-1, ReaderKeys.step(right = true, ReadingDirection.RIGHT_TO_LEFT))
        assertEquals(1, ReaderKeys.step(right = false, ReadingDirection.RIGHT_TO_LEFT))
    }

    @Test fun picturesSizesFromTheirHeaders() {
        val png = ByteArray(33).also {
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
            it[18] = 0x04; it[19] = 0xB0.toByte() // 1200 wide
            it[22] = 0x07; it[23] = 0x08 // 1800 tall
        }
        assertEquals(1200 to 1800, ImageSize.of(png))
        val jpeg = intArrayOf(
            0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, 0xFF, 0xC4, 0, 3, 0,
            0xFF, 0xC0, 0, 11, 8, 0x07, 0xD0, 0x0B, 0xB8, 3, 0, 0, 0, 0,
        ).map { it.toByte() }.toByteArray()
        assertEquals(3000 to 2000, ImageSize.of(jpeg))
        assertTrue(ImageSize.isWide(ImageSize.of(jpeg)))
        assertEquals(null, ImageSize.of(byteArrayOf(1, 2, 3)))
        val listing = ComicListing("rar", listOf(ComicPageInfo("a.jpg", 10, 20, 1), ComicPageInfo("b.jpg", 40, 20, 1)), null)
        assertEquals(setOf(1), listing.widePages)
        assertTrue(listing.paged)
        assertFalse(ComicListing("pdf", emptyList(), null).paged)
    }

    @Test fun theReadButtonSaysWhereTheReaderIs() {
        val book = dev.nitrate.core.model.MediaItem("b", "B", dev.nitrate.core.model.ItemKind.BOOK)
        assertEquals("Read", ComicProgress.readLabel(book))
        assertEquals("Continue from page 13", ComicProgress.readLabel(book.copy(user = dev.nitrate.core.model.UserState(positionMs = 12_000))))
        assertEquals("Continue (34%)", ComicProgress.readLabel(book.copy(container = "epub", user = dev.nitrate.core.model.UserState(positionMs = 340_000))))
        assertEquals("Read", ComicProgress.readLabel(book.copy(user = dev.nitrate.core.model.UserState(played = true, positionMs = 5_000))))
    }
}
