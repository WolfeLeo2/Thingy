package com.wolfeleo2.thingy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which spaces become share targets, and under what label. */
class SpaceShortcutsTest {

    private fun space(id: String, name: String) = Space(id = id, name = name)

    @Test
    fun `shortcut id is the space id, which is how a share routes back`() {
        val entries = shortcutEntries(listOf(space("abc123", "Recipes")))
        assertEquals(listOf("abc123" to "Recipes"), entries)
    }

    @Test
    fun `publishes at most MAX targets`() {
        val many = (1..10).map { space("id$it", "Space $it") }
        assertEquals(SpaceShortcuts.MAX, shortcutEntries(many).size)
        assertEquals("id1" to "Space 1", shortcutEntries(many).first())
    }

    @Test
    fun `a space with no id is dropped rather than published unresolvable`() {
        val entries = shortcutEntries(listOf(space("", "Unsaved"), space("ok", "Saved")))
        assertEquals(listOf("ok" to "Saved"), entries)
    }

    @Test
    fun `a blank name still gets a usable label`() {
        val (_, label) = shortcutEntries(listOf(space("id", "   "))).single()
        assertTrue(label.isNotBlank())
    }
}
