package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Date

/** Where the export reads each item's media from, and what it admits it can't reach. */
class DataExportTest {

    private val filesDir = "/data/user/0/com.wolfeleo2.thingy/files"
    private val nothingExists: (String) -> Boolean = { false }

    private fun image(
        id: String = "i1",
        storagePath: String? = null,
        imageUrl: String? = null,
        sticker: Boolean? = null,
    ) = Item(
        id = id, type = ItemType.IMAGE.wire, storagePath = storagePath,
        imageUrl = imageUrl, sticker = sticker,
    )

    @Test
    fun `prefers the on-device original over re-downloading it`() {
        val item = image(storagePath = "/data/images/a.webp", imageUrl = "https://cdn/a.webp")
        val ref = mediaRefFor(item, filesDir) { it == "/data/images/a.webp" }!!
        assertEquals("/data/images/a.webp", ref.localPath)
        assertEquals("media/i1.webp", ref.entryName)
    }

    @Test
    fun `falls back to the synced copy when the original is gone`() {
        val item = image(storagePath = "/data/images/gone.webp", imageUrl = "https://cdn/a.webp")
        val synced = "$filesDir/saved/i1.webp"
        val ref = mediaRefFor(item, filesDir) { it == synced }!!
        assertEquals(synced, ref.localPath)
    }

    @Test
    fun `falls back to the CDN when this device has no copy at all`() {
        val ref = mediaRefFor(image(imageUrl = "https://cdn/a.webp"), filesDir, nothingExists)!!
        assertNull(ref.localPath)
        assertEquals("https://cdn/a.webp", ref.remoteUrl)
    }

    @Test
    fun `media saved on another device and never synced is reported, not silently dropped`() {
        // No local file, and imageUrl is still the other device's local path — unreachable here.
        val item = image(storagePath = "/data/other-device/a.webp", imageUrl = "/data/other-device/a.webp")
        val ref = mediaRefFor(item, filesDir, nothingExists)!!
        assertNull(ref.localPath)
        assertNull(ref.remoteUrl) // caller records this item id under mediaMissing
    }

    @Test
    fun `stickers keep their transparency`() {
        val ref = mediaRefFor(image(sticker = true, imageUrl = "https://cdn/a.png"), filesDir, nothingExists)!!
        assertEquals("media/i1.png", ref.entryName)
    }

    @Test
    fun `videos are exported as mp4`() {
        val video = Item(id = "v1", type = ItemType.VIDEO.wire, imageUrl = "https://cdn/v.mp4")
        assertEquals("media/v1.mp4", mediaRefFor(video, filesDir, nothingExists)!!.entryName)
    }

    @Test
    fun `notes carry no media`() {
        val note = Item(id = "n1", type = ItemType.NOTE.wire, note = "hello")
        assertNull(mediaRefFor(note, filesDir, nothingExists))
    }

    @Test
    fun `a link without a hero image is not a missing file`() {
        val link = Item(id = "l1", type = ItemType.LINK.wire, url = "https://example.com")
        assertNull(mediaRefFor(link, filesDir, nothingExists))
    }

    @Test
    fun `export file name is dated and sortable`() {
        val day = Calendar.getInstance().apply { set(2026, Calendar.JULY, 27, 13, 0, 0) }
        assertEquals("thingy-export-2026-07-27.zip", exportFileName(Date(day.timeInMillis)))
    }
}
