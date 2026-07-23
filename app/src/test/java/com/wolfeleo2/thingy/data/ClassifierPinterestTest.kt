package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierPinterestTest {

    @Test
    fun `strips share-invite suffix and query params to bare pin url`() {
        val shared = "https://www.pinterest.com/pin/319826011047823011/sent/" +
            "?invite_code=abc&sender=1&sfo=1"
        assertEquals("https://www.pinterest.com/pin/319826011047823011/", canonicalPinterestUrl(shared))
    }

    @Test
    fun `leaves an already-bare pin url unchanged`() {
        val bare = "https://www.pinterest.com/pin/319826011047823011/"
        assertEquals(bare, canonicalPinterestUrl(bare))
    }

    @Test
    fun `adds trailing slash for a pin url missing one`() {
        val noSlash = "https://www.pinterest.com/pin/319826011047823011"
        assertEquals("https://www.pinterest.com/pin/319826011047823011/", canonicalPinterestUrl(noSlash))
    }

    @Test
    fun `falls back to original url when no pin id is present`() {
        val home = "https://www.pinterest.com/"
        assertEquals(home, canonicalPinterestUrl(home))
    }
}
