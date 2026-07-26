package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR text: normalizing what ML Kit returns, and folding it into the searchable blob. */
class OcrSearchTextTest {

    @Test
    fun `collapses ML Kit's line breaks into one searchable line`() {
        val raw = "Zanzibar ferry ticket\nDeparts 14:35  gate B\n\nBooking QX7742"
        assertEquals("Zanzibar ferry ticket Departs 14:35 gate B Booking QX7742", normalizeOcrText(raw, 2000))
    }

    @Test
    fun `an image with no readable text yields null, not an empty field`() {
        assertNull(normalizeOcrText("", 2000))
        assertNull(normalizeOcrText("   \n\t ", 2000))
    }

    @Test
    fun `caps a dense screenshot so it cannot bloat the document`() {
        val wall = "word ".repeat(1000)
        assertEquals(2000, normalizeOcrText(wall, 2000)!!.length)
    }

    @Test
    fun `searchText finds words that appear only inside the image`() {
        val text = buildSearchText(
            title = "Ferry Ticket",
            description = "A ticket",
            tags = listOf("travel"),
            note = null,
            ocrText = "Booking QX7742",
        )
        assertTrue(text.contains("QX7742"))
    }

    @Test
    fun `searchText is unchanged for items with no OCR`() {
        val withoutOcr = buildSearchText("Ferry Ticket", "A ticket", listOf("travel"), null, null)
        assertEquals("Ferry Ticket A ticket travel", withoutOcr)
    }

    @Test
    fun `blank fields never leave double spaces in the blob`() {
        val text = buildSearchText("", "A ticket", emptyList(), null, "QX7742")
        assertEquals("A ticket QX7742", text)
    }
}
