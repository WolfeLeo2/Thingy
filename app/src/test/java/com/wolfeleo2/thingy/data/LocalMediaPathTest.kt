package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A picked video's storagePath is a content:// URI until the transcode replaces it with a real
 * file. "Not on disk yet" must be distinguishable from "broken" — conflating them marked every
 * shared video `failed` within seconds of saving it.
 */
class LocalMediaPathTest {

    private val everythingExists: (String) -> Boolean = { true }
    private val nothingExists: (String) -> Boolean = { false }

    private fun video(storagePath: String?) =
        Item(id = "v1", type = ItemType.VIDEO.wire, storagePath = storagePath)

    @Test
    fun `a freshly picked video is not ready`() {
        val item = video("content://media/external/video/media/1000000042")
        assertNull(localMediaPath(item, everythingExists))
    }

    @Test
    fun `a transcoded video is ready`() {
        val path = "/data/user/0/com.wolfeleo2.thingy/files/videos/abc.mp4"
        assertEquals(path, localMediaPath(video(path), everythingExists))
    }

    @Test
    fun `a local path whose file is gone is not ready`() {
        assertNull(localMediaPath(video("/data/videos/deleted.mp4"), nothingExists))
    }

    @Test
    fun `an item with no storagePath is not ready`() {
        assertNull(localMediaPath(video(null), everythingExists))
    }
}
