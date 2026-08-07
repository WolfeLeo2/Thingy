package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media this device never held locally still has to be classifiable, or an item saved from another
 * client sits in `processing` forever — [localMediaPath] returns null on every pass, MediaNotReadyYet
 * never clears, and it retries on each feed snapshot for good.
 *
 * The pairing with [localMediaPath] is the whole contract: local wins when present, the CDN copy is
 * the fallback, and only "neither" means genuinely-not-ready.
 */
class RemoteMediaUrlTest {

    private fun video(storagePath: String? = null, imageUrl: String? = null) =
        Item(id = "v1", type = ItemType.VIDEO.wire, storagePath = storagePath, imageUrl = imageUrl)

    private val cdn = "https://res.cloudinary.com/cumjajjx/video/upload/v1/abc.mp4"

    @Test
    fun `an uploaded item exposes its CDN copy`() {
        assertEquals(cdn, remoteMediaUrl(video(imageUrl = cdn)))
    }

    /**
     * The mid-transcode case. imageUrl is still the picked content:// URI, so there is no remote
     * copy to fall back to and the item must stay "not ready" rather than trying to download it.
     */
    @Test
    fun `a freshly picked video has no remote copy`() {
        val picked = "content://media/external/video/media/1000000042"
        val item = video(storagePath = picked, imageUrl = picked)
        assertNull(remoteMediaUrl(item))
        assertNull(localMediaPath(item) { true })
    }

    @Test
    fun `a local-only audio item has no remote copy until upload lands`() {
        val path = "/data/user/0/com.wolfeleo2.thingy/files/audio/note.m4a"
        assertNull(remoteMediaUrl(video(storagePath = path, imageUrl = path)))
    }

    @Test
    fun `an item with no imageUrl has no remote copy`() {
        assertNull(remoteMediaUrl(video()))
    }

    /**
     * The case the whole change exists for: no local file, but a CDN copy — classifiable, where
     * before it was permanently stuck.
     */
    @Test
    fun `media from another client resolves remotely when there is no local file`() {
        val item = video(storagePath = null, imageUrl = cdn)
        assertNull(localMediaPath(item) { true })
        assertEquals(cdn, remoteMediaUrl(item))
    }

    @Test
    fun `local file wins over the CDN copy when both exist`() {
        val path = "/data/user/0/com.wolfeleo2.thingy/files/videos/abc.mp4"
        val item = video(storagePath = path, imageUrl = cdn)
        assertEquals(path, localMediaPath(item) { true })
    }

    @Test
    fun `the inline cap stays under Gemini's 20MB request limit`() {
        assertTrue(MAX_INLINE_BYTES < 20L * 1024 * 1024)
    }
}
