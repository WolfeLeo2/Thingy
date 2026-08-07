package com.wolfeleo2.thingy.ui

import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

private fun item(
    id: String,
    type: ItemType = ItemType.IMAGE,
    title: String? = null,
    createdAtMillis: Long? = 0L,
) = Item(
    id = id,
    type = type.wire,
    title = title,
    createdAt = createdAtMillis?.let { Date(it) },
)

class FilterSortTest {

    private val mixed = listOf(
        item("img", ItemType.IMAGE, "Banana", 3_000),
        item("lnk", ItemType.LINK, "apple", 1_000),
        item("note", ItemType.NOTE, "Cherry", 2_000),
        item("vid", ItemType.VIDEO, "date", 5_000),
        item("aud", ItemType.AUDIO, "Elderberry", 4_000),
    )

    private fun ids(l: List<Item>) = l.map { it.id }

    @Test
    fun `ALL keeps every type`() {
        val out = applyFilterSort(mixed, TypeFilter.ALL, SortField.DATE_SAVED, ascending = true)
        assertEquals(5, out.size)
    }

    @Test
    fun `each filter selects only its own type`() {
        val expected = mapOf(
            TypeFilter.IMAGES to "img",
            TypeFilter.LINKS to "lnk",
            TypeFilter.NOTES to "note",
            TypeFilter.VIDEOS to "vid",
            TypeFilter.AUDIO to "aud",
        )
        expected.forEach { (filter, id) ->
            val out = applyFilterSort(mixed, filter, SortField.DATE_SAVED, ascending = true)
            assertEquals("filter $filter", listOf(id), ids(out))
        }
    }

    @Test
    fun `date sort runs both directions`() {
        assertEquals(
            listOf("lnk", "note", "img", "aud", "vid"),
            ids(applyFilterSort(mixed, TypeFilter.ALL, SortField.DATE_SAVED, ascending = true)),
        )
        assertEquals(
            listOf("vid", "aud", "img", "note", "lnk"),
            ids(applyFilterSort(mixed, TypeFilter.ALL, SortField.DATE_SAVED, ascending = false)),
        )
    }

    /**
     * A just-saved item's @ServerTimestamp is still null. Treating that as epoch is the bug that
     * once let a brand new item resurface as an "on this day" memory — it must read as newest.
     */
    @Test
    fun `null createdAt sorts as the newest item in both directions`() {
        val pending = item("pending", ItemType.NOTE, "Pending", createdAtMillis = null)
        val list = mixed + pending

        val newestFirst = applyFilterSort(list, TypeFilter.ALL, SortField.DATE_SAVED, ascending = false)
        assertEquals("pending", newestFirst.first().id)

        val oldestFirst = applyFilterSort(list, TypeFilter.ALL, SortField.DATE_SAVED, ascending = true)
        assertEquals("pending", oldestFirst.last().id)
    }

    @Test
    fun `title sort ignores case`() {
        assertEquals(
            listOf("lnk", "img", "note", "vid", "aud"), // apple, Banana, Cherry, date, Elderberry
            ids(applyFilterSort(mixed, TypeFilter.ALL, SortField.TITLE, ascending = true)),
        )
    }

    @Test
    fun `title sort falls back through displayTitle`() {
        val list = listOf(
            item("hasNote", ItemType.NOTE, title = null).copy(note = "Zebra"),
            item("hasUrl", ItemType.LINK, title = null).copy(url = "https://www.apple.com/x"),
            item("bare", ItemType.NOTE, title = null), // -> "Untitled"
        )
        assertEquals(
            listOf("hasUrl", "bare", "hasNote"), // apple.com, Untitled, Zebra
            ids(applyFilterSort(list, TypeFilter.ALL, SortField.TITLE, ascending = true)),
        )
    }

    @Test
    fun `equal titles break the tie by newest first`() {
        val list = listOf(
            item("old", ItemType.NOTE, "Same", 1_000),
            item("new", ItemType.NOTE, "Same", 9_000),
        )
        assertEquals(
            listOf("new", "old"),
            ids(applyFilterSort(list, TypeFilter.ALL, SortField.TITLE, ascending = true)),
        )
    }

    @Test
    fun `pair helper preserves membership and keeps groups separate`() {
        val saved = listOf("m1" to item("img", ItemType.IMAGE, "B", 1_000), "m2" to item("vid", ItemType.VIDEO, "A", 2_000))
        val suggested = listOf("m3" to item("img2", ItemType.IMAGE, "C", 3_000))

        val out = saved.filterSort(TypeFilter.ALL, SortField.TITLE, ascending = true) +
            suggested.filterSort(TypeFilter.ALL, SortField.TITLE, ascending = true)

        assertEquals(listOf("m2", "m1", "m3"), out.map { it.first })
    }

    @Test
    fun `pair helper applies the type filter`() {
        val saved = listOf("m1" to item("img", ItemType.IMAGE), "m2" to item("vid", ItemType.VIDEO))
        val out = saved.filterSort(TypeFilter.VIDEOS, SortField.DATE_SAVED, ascending = false)
        assertEquals(listOf("m2"), out.map { it.first })
    }

    @Test
    fun `empty message names the filter`() {
        assertEquals("Nothing saved yet", TypeFilter.ALL.emptyMessage)
        assertEquals("No videos yet", TypeFilter.VIDEOS.emptyMessage)
        assertEquals("No voice yet", TypeFilter.AUDIO.emptyMessage)
    }
}
