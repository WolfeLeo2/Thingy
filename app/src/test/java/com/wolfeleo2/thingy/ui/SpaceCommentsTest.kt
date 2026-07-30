package com.wolfeleo2.thingy.ui

import com.wolfeleo2.thingy.data.MAX_COMMENT_CHARS
import com.wolfeleo2.thingy.data.SpaceComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Date

/** The unread rule behind the badge count and the teaser pill. */
class SpaceCommentsTest {

    private val me = "me"
    private val alice = "alice"

    private fun comment(id: String, author: String, at: Long?) =
        SpaceComment(id = id, spaceId = "s", userId = author, text = id, createdAt = at?.let(::Date))

    @Test
    fun `only other people's comments count as unread`() {
        val comments = listOf(
            comment("a", alice, 100),
            comment("mine", me, 200),
        )
        assertEquals(listOf("a"), unreadComments(comments, me, 0L).map { it.id })
    }

    @Test
    fun `the read cursor excludes everything up to and including it`() {
        val comments = listOf(
            comment("old", alice, 100),
            comment("cursor", alice, 200),
            comment("new", alice, 300),
        )
        assertEquals(listOf("new"), unreadComments(comments, me, 200L).map { it.id })
    }

    /**
     * The bug this replaced: the teaser used the newest comment overall, so writing your own
     * comment masked an unread one underneath and you were never told about it.
     */
    @Test
    fun `your own newer comment does not mask an older unread one`() {
        val comments = listOf(
            comment("alice-unread", alice, 100),
            comment("my-reply", me, 200),
        )
        val unread = unreadComments(comments, me, 0L)
        assertEquals("alice-unread", unread.lastOrNull()?.id)
    }

    /** A pending write has no server timestamp yet — and can only ever be your own. */
    @Test
    fun `an unacked comment is not counted`() {
        val comments = listOf(comment("pending", me, null))
        assertEquals(emptyList<String>(), unreadComments(comments, me, 0L).map { it.id })
    }

    @Test
    fun `reading everything leaves nothing unread`() {
        val comments = listOf(comment("a", alice, 100), comment("b", alice, 200))
        assertEquals(emptyList<String>(), unreadComments(comments, me, 200L).map { it.id })
        assertNull(unreadComments(comments, me, 200L).lastOrNull())
    }

    /** Signed out / unknown uid: everything is someone else's, nothing crashes. */
    @Test
    fun `a null current user treats all comments as other people's`() {
        val comments = listOf(comment("a", alice, 100), comment("b", me, 200))
        assertEquals(listOf("a", "b"), unreadComments(comments, null, 0L).map { it.id })
    }

    /**
     * The client cap only exists to stop a write the rules would deny. If the two drift the user
     * gets a silent "Couldn't post that comment" instead of a full text field, so pin them together
     * — this fails if either side is edited alone.
     */
    @Test
    fun `the client text cap matches the security rule`() {
        val rule = File("../firestore.rules").takeIf { it.exists() } ?: File("firestore.rules")
        val ruleCap = Regex("""text\.size\(\)\s*<=\s*(\d+)""")
            .find(rule.readText())?.groupValues?.get(1)?.toInt()
        assertEquals(ruleCap, MAX_COMMENT_CHARS)
    }
}
