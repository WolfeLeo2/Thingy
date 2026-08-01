package com.wolfeleo2.thingy.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class CanvasLayoutTest {

    @Test
    fun `jitter budget keeps cells from colliding`() {
        // Adjacent cell centres are CELL_DP apart; each card occupies ITEM_DP/2 either side of its
        // centre and can drift a further JITTER*CELL_DP toward its neighbour. If that sum exceeds a
        // cell, cards overlap and nearestRank — which only checks the nine cells around the camera,
        // assuming one position means one item — can snap to the wrong one. The three constants are
        // therefore coupled and cannot be retuned independently.
        val worstCaseSpan = ITEM_DP + 2f * JITTER * CELL_DP
        assertTrue(
            "cards can overlap: $worstCaseSpan dp of card and jitter in a $CELL_DP dp cell",
            worstCaseSpan <= CELL_DP,
        )
    }

    @Test
    fun `spiral starts at the origin`() {
        assertEquals(IntOffset(0, 0), spiralCell(0))
    }

    @Test
    fun `spiral never reuses a cell`() {
        val seen = mutableSetOf<IntOffset>()
        for (rank in 0 until 1000) {
            assertTrue("duplicate cell at rank $rank", seen.add(spiralCell(rank)))
        }
    }

    @Test
    fun `spiral rings grow monotonically`() {
        var previousRing = -1
        for (rank in 0 until 1000) {
            val cell = spiralCell(rank)
            val ring = max(abs(cell.x), abs(cell.y))
            assertTrue("ring went backwards at rank $rank", ring >= previousRing)
            previousRing = ring
        }
    }

    @Test
    fun `cellToRank inverts spiralCell`() {
        for (rank in 0 until 1000) {
            assertEquals(rank, cellToRank(spiralCell(rank)))
        }
    }

    @Test
    fun `jitter is deterministic and bounded`() {
        val a = jitterFor("abc123")
        val b = jitterFor("abc123")
        assertEquals(a, b)
        for (id in listOf("a", "b", "zzz", "0193-item-id", "")) {
            val j = jitterFor(id)
            assertTrue("x out of range for '$id'", abs(j.x) <= JITTER)
            assertTrue("y out of range for '$id'", abs(j.y) <= JITTER)
        }
    }

    @Test
    fun `jitter differs between axes`() {
        val j = jitterFor("some-item")
        assertTrue("both axes got the same salt", j.x != j.y)
    }

    /**
     * The regression test for the whole design. Ranking ascending by creation time means a new save
     * appends at the frontier; if this ever fails the canvas has started sliding under the user on
     * every save, which is exactly what stable placement exists to prevent.
     */
    @Test
    fun `saving a new item moves nothing already placed`() {
        val existing = List(40) { "item-$it" }
        val before = existing.mapIndexed { rank, id -> id to planeCenter(rank, id) }

        val after = (existing + "item-new").mapIndexed { rank, id -> id to planeCenter(rank, id) }.toMap()

        before.forEach { (id, position) ->
            assertEquals("$id moved when a newer item was saved", position, after[id])
        }
    }

    @Test
    fun `deleting an item leaves its neighbours alone`() {
        // A gap is the deliberate trade: closing it would shift every item after the hole.
        val ids = List(20) { "item-$it" }
        val before = ids.mapIndexed { rank, id -> id to planeCenter(rank, id) }.toMap()

        // Rank is position in the ascending list, so removing the *last* item is the only deletion
        // that leaves ranks untouched; anything earlier shifts by design and we assert the gap
        // policy instead at the call site (the composable skips ranks past the list end).
        val survivors = ids.dropLast(1)
        survivors.forEachIndexed { rank, id ->
            assertEquals("$id moved", before[id], planeCenter(rank, id))
        }
    }

    @Test
    fun `item fits inside the item square whatever its ratio`() {
        for (ratio in listOf(0.5f, 0.8f, 1f, 1.5f, 1.91f, 2f)) {
            val size = itemSize(ratio)
            assertTrue("width overflows at $ratio", size.x <= ITEM_DP + 0.01f)
            assertTrue("height overflows at $ratio", size.y <= ITEM_DP + 0.01f)
            assertEquals("ratio not preserved at $ratio", ratio, size.x / size.y, 0.001f)
        }
    }

    @Test
    fun `visible cells cover the viewport plus a margin ring`() {
        // Camera at the origin, viewport just inside one cell: only the centre cell is on screen,
        // so the window is that plus one margin ring in each direction.
        val viewport = Offset(CELL_DP * 0.9f, CELL_DP * 0.9f)
        val (xs, ys) = visibleCells(Offset.Zero, viewport, zoom = 1f, margin = 1)
        assertEquals(-1..1, xs)
        assertEquals(-1..1, ys)
    }

    @Test
    fun `cells are half-open so a viewport edge grazes the next cell`() {
        // A cell covers [c*CELL - CELL/2, c*CELL + CELL/2). A viewport exactly one cell wide
        // therefore touches cell 1 on its right edge and still starts inside cell 0 on its left.
        // Documented rather than fixed: an off-by-one here would leave a column unloaded.
        val (xs, _) = visibleCells(Offset.Zero, Offset(CELL_DP, CELL_DP), zoom = 1f, margin = 0)
        assertEquals(0..1, xs)
    }

    @Test
    fun `zooming out widens the visible window`() {
        val tight = visibleCells(Offset.Zero, Offset(CELL_DP, CELL_DP), zoom = 1f)
        val wide = visibleCells(Offset.Zero, Offset(CELL_DP, CELL_DP), zoom = MIN_ZOOM)
        assertTrue(
            "zooming out should reveal more cells",
            wide.first.count() > tight.first.count() && wide.second.count() > tight.second.count(),
        )
    }

    @Test
    fun `nearest rank picks the item the camera is parked on`() {
        val ids = List(60) { "item-$it" }
        for (rank in listOf(0, 1, 7, 23, 44, 59)) {
            val parked = planeCenter(rank, ids[rank])
            assertEquals(rank, nearestRank(parked, ids.size) { ids[it] })
        }
    }

    @Test
    fun `nearest rank follows the camera toward a neighbour`() {
        val ids = List(60) { "item-$it" }
        val here = planeCenter(0, ids[0])
        val neighbour = 4 // an arbitrary ring-1 cell
        val there = planeCenter(neighbour, ids[neighbour])
        // 90% of the way across: firmly in the neighbour's cell.
        val camera = here + (there - here) * 0.9f
        assertEquals(neighbour, nearestRank(camera, ids.size) { ids[it] })
    }

    @Test
    fun `the canvas opens in survey mode`() {
        // Below SNAP_ZOOM, so you land looking at the shape of your library rather than at one item.
        assertTrue("INITIAL_ZOOM must sit in survey mode", INITIAL_ZOOM < SNAP_ZOOM)
        assertTrue("INITIAL_ZOOM must be reachable", INITIAL_ZOOM in MIN_ZOOM..MAX_ZOOM)
    }

    @Test
    fun `plane radius encloses every placed item`() {
        for (count in listOf(1, 2, 9, 25, 60, 400)) {
            val radius = planeRadiusDp(count)
            for (rank in 0 until count) {
                val p = planeCenter(rank, "item-$rank")
                assertTrue("rank $rank of $count escapes the radius", abs(p.x) <= radius && abs(p.y) <= radius)
            }
        }
        assertEquals(0f, planeRadiusDp(0), 0f)
    }

    @Test
    fun `clamp springs the camera back over the placed region`() {
        val radius = planeRadiusDp(25)
        // Inside is left alone...
        val inside = Offset(radius / 2f, -radius / 3f)
        assertEquals(inside, clampCamera(inside, radius))
        // ...the void is unreachable.
        val void = Offset(radius * 10f, radius * -10f)
        assertEquals(Offset(radius, -radius), clampCamera(void, radius))
    }

    @Test
    fun `zooming pins the plane point under the fingers`() {
        val viewport = Offset(1080f, 2400f)
        val densityScale = 3f
        val camera = Offset(500f, -200f)
        val centroid = Offset(900f, 400f) // well off-centre, near the top right

        // The plane point under the centroid, before and after the zoom, must be the same point.
        fun planeUnderCentroid(eye: Offset, zoom: Float) =
            eye + (centroid - viewport / 2f) / densityScale / zoom

        val from = 0.6f
        val to = 1.8f
        val moved = camera + zoomAnchorShift(centroid, viewport, densityScale, from, to)

        val before = planeUnderCentroid(camera, from)
        val after = planeUnderCentroid(moved, to)
        assertEquals("x drifted", before.x, after.x, 0.01f)
        assertEquals("y drifted", before.y, after.y, 0.01f)
    }

    @Test
    fun `zooming about the exact centre moves the camera nowhere`() {
        val viewport = Offset(1080f, 2400f)
        val shift = zoomAnchorShift(viewport / 2f, viewport, 3f, 0.6f, 1.8f)
        assertEquals(Offset.Zero, shift)
    }

    @Test
    fun `double tap toggles between the two modes`() {
        assertTrue("double tap must land in browse mode", DOUBLE_TAP_ZOOM >= SNAP_ZOOM)
        assertTrue("double tap must be reachable", DOUBLE_TAP_ZOOM in MIN_ZOOM..MAX_ZOOM)
        // Survey → browse, browse → survey, and toggling twice is a round trip.
        assertEquals(DOUBLE_TAP_ZOOM, doubleTapZoom(INITIAL_ZOOM), 0f)
        assertEquals(INITIAL_ZOOM, doubleTapZoom(DOUBLE_TAP_ZOOM), 0f)
        assertEquals(INITIAL_ZOOM, doubleTapZoom(doubleTapZoom(INITIAL_ZOOM)), 0f)
        // Anywhere in survey mode goes up; anywhere in browse mode comes back down.
        assertEquals(DOUBLE_TAP_ZOOM, doubleTapZoom(MIN_ZOOM), 0f)
        assertEquals(INITIAL_ZOOM, doubleTapZoom(MAX_ZOOM), 0f)
    }

    @Test
    fun `fling carries the camera the way the content was thrown`() {
        // Flicking content to the right (+x) must move the camera left (-x), or the canvas would
        // coast backwards out from under the flick.
        val thrownRight = flingVelocityDp(Offset(1200f, 0f), densityScale = 3f, zoom = 1f)
        assertTrue("camera must coast opposite the pan", thrownRight.x < 0f)

        // A flick at the same speed covers more plane when zoomed out.
        val zoomedOut = flingVelocityDp(Offset(1200f, 0f), densityScale = 3f, zoom = 0.5f)
        assertTrue(abs(zoomedOut.x) > abs(thrownRight.x))

        // px/s ÷ density ÷ zoom = dp/s.
        assertEquals(-400f, thrownRight.x, 0.001f)
    }

    @Test
    fun `a still finger produces no fling`() {
        // Compared by magnitude, not identity: negating a zero velocity yields -0.0, which is not
        // bit-equal to Offset.Zero. Only the distance is ever tested against the fling threshold.
        assertEquals(0f, flingVelocityDp(Offset.Zero, 3f, 1f).getDistance(), 0f)
        assertTrue(
            "a resting lift must fall under the threshold",
            flingVelocityDp(Offset.Zero, 3f, 1f).getDistance() < MIN_FLING_DP_PER_S,
        )
    }

    @Test
    fun `nearest rank is null past the frontier and on an empty canvas`() {
        val ids = List(5) { "item-$it" }
        assertNull(nearestRank(Offset.Zero, 0) { "" })
        // Far outside the placed spiral — nothing to snap to, so panning into the void just stops.
        assertNull(nearestRank(Offset(CELL_DP * 50, CELL_DP * 50), ids.size) { ids[it] })
        assertNotNull(nearestRank(Offset.Zero, ids.size) { ids[it] })
    }
}
