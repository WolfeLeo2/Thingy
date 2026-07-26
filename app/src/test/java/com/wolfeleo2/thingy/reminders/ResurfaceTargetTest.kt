package com.wolfeleo2.thingy.reminders

import com.wolfeleo2.thingy.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/** What the daily resurface — notification and home-screen widget alike — decides to show. */
class ResurfaceTargetTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 26, 9, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun item(id: String, createdAt: Long?) =
        Item(id = id, createdAt = createdAt?.let { Date(it) })

    private fun daysAgo(days: Int) = now - days * 24L * 60 * 60 * 1000

    private fun yearsAgoToday(years: Int) = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.YEAR, -years)
    }.timeInMillis

    @Test
    fun `an exact anniversary wins over anything else`() {
        val items = listOf(
            item("recent", daysAgo(30)),
            item("anniversary", yearsAgoToday(1)),
            item("older", daysAgo(400)),
        )
        assertEquals("anniversary", pickResurfaceTarget(items, now)!!.id)
    }

    @Test
    fun `an item saved today is not its own anniversary`() {
        val items = listOf(item("today", now))
        assertNull(pickResurfaceTarget(items, now))
    }

    @Test
    fun `falls back to something at least a fortnight old`() {
        val items = listOf(item("yesterday", daysAgo(1)), item("old", daysAgo(20)))
        assertEquals("old", pickResurfaceTarget(items, now)!!.id)
    }

    @Test
    fun `the fallback only ever picks from the eligible set`() {
        val items = listOf(item("a", daysAgo(60)), item("b", daysAgo(90)), item("fresh", daysAgo(2)))
        repeat(20) {
            assertTrue(pickResurfaceTarget(items, now)!!.id in setOf("a", "b"))
        }
    }

    @Test
    fun `nothing old enough resurfaces nothing`() {
        val items = listOf(item("a", daysAgo(1)), item("b", daysAgo(13)))
        assertNull(pickResurfaceTarget(items, now))
    }

    @Test
    fun `an item with no timestamp is never picked`() {
        assertNull(pickResurfaceTarget(listOf(item("undated", null)), now))
    }

    @Test
    fun `an empty library resurfaces nothing`() {
        assertNull(pickResurfaceTarget(emptyList(), now))
    }
}
