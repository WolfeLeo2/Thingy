package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voice note is only worth saving if you can find it again, and every path from spoken words to
 * a search hit runs through plain functions — so they're the ones worth pinning.
 */
class VoiceNoteTest {

    private val transcript = "remind me the landlord's number is 0722 415 900 and rent is due friday"

    @Test
    fun `spoken words are searchable`() {
        val text = buildSearchText(
            title = "Rent reminder",
            description = "A note about rent",
            tags = listOf("home"),
            note = null,
            ocrText = null,
            transcript = transcript,
        )
        assertTrue(text.contains("0722 415 900"))
        assertTrue(text.contains("friday"))
    }

    @Test
    fun `semantic search indexes the transcript too`() {
        val item = Item(
            type = ItemType.AUDIO.wire,
            title = "Rent reminder",
            transcript = transcript,
        )
        assertTrue(item.embedText().contains("landlord"))
    }

    /** The transcript is the only text an audio item has — losing it makes the note unfindable. */
    @Test
    fun `an audio item with no title still embeds its transcript`() {
        val item = Item(type = ItemType.AUDIO.wire, transcript = transcript)
        assertTrue(item.embedText().contains("landlord"))
    }

    @Test
    fun `every other item type is unaffected by the new field`() {
        assertEquals(
            "Ferry Ticket A ticket travel",
            buildSearchText("Ferry Ticket", "A ticket", listOf("travel"), null, null),
        )
        assertEquals("Ferry Ticket", Item(type = ItemType.IMAGE.wire, title = "Ferry Ticket").embedText())
    }

    /** OfflineImageSyncer writes these files and the card resolver reads them back by name. */
    @Test
    fun `synced file extension round-trips per type`() {
        assertEquals("m4a", syncedMediaExt(ItemType.AUDIO.wire))
        assertEquals("mp4", syncedMediaExt(ItemType.VIDEO.wire))
        assertEquals("webp", syncedMediaExt(ItemType.IMAGE.wire))
        assertEquals("webp", syncedMediaExt(ItemType.LINK.wire))
    }

    /**
     * Older installs — and co-members in a shared space who haven't updated — parse the wire string
     * with `from()`. It must degrade to null rather than throwing, or one audio item poisons a feed.
     */
    @Test
    fun `an unknown type degrades to null instead of throwing`() {
        assertEquals(ItemType.AUDIO, ItemType.from("audio"))
        assertEquals(null, ItemType.from("hologram"))
    }

    @Test
    fun `silence reads as an empty meter and a peak fills it`() {
        assertEquals(0f, AudioIngestor.amplitudeToLevel(0), 0.001f)
        assertEquals(0f, AudioIngestor.amplitudeToLevel(-1), 0.001f)
        assertEquals(1f, AudioIngestor.amplitudeToLevel(32_767), 0.001f)
    }

    /**
     * The point of the dB mapping: a linear meter puts speech near the floor. Half of full-scale
     * amplitude is only -6dB, so it should read high, not at 0.5.
     */
    @Test
    fun `the meter is logarithmic, not linear`() {
        val half = AudioIngestor.amplitudeToLevel(16_384)
        assertTrue("half amplitude read $half, expected well above linear 0.5", half > 0.85f)
    }

    @Test
    fun `room noise stays near the bottom of the meter`() {
        // ~-54dB, below QUIET_FLOOR_DB — should clamp to silence rather than go negative.
        assertEquals(0f, AudioIngestor.amplitudeToLevel(65), 0.001f)
    }
}
