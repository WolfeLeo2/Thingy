package com.wolfeleo2.thingy.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.wolfeleo2.thingy.ui.seededUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Placement maths for the infinite canvas. Everything here is pure and works in **dp units**
 * (the composable converts to pixels at the edge), so the whole layout is unit-testable without
 * a device or a Compose test rule.
 *
 * Items are laid out on a square spiral walking outward from the origin, one per cell, ordered
 * by *ascending* creation time. Ascending is load-bearing: the feed is newest-first, and
 * spiralling in that order would shift every item one cell on every save, sliding the canvas out
 * from under the user and destroying the spatial memory that makes panning worth it. Ascending
 * means a new item takes the next free cell at the growing edge and nothing already placed moves.
 */

// ponytail: tuning knobs set by reasoning, not by feel — expect to move them after a device run.
// SNAP_ZOOM is the one most likely to be wrong: raise it if the canvas feels like it's fighting
// you, lower it if leaning in doesn't feel like it starts helping.
//
// CELL_DP, ITEM_DP and JITTER are the exception: they are NOT independent. Cards must not overlap,
// or nearestRank — which only looks at the nine cells around the camera and assumes one position
// means one item — can pick the wrong one. CanvasLayoutTest asserts the budget; retuning one of
// the three without the others fails the build rather than quietly breaking the snap.
const val CELL_DP = 200f
const val ITEM_DP = 130f
const val JITTER = 0.16f
const val SNAP_ZOOM = 0.9f
const val FOCUS_SCALE = 1.18f
const val MIN_ZOOM = 0.35f
const val MAX_ZOOM = 2.5f

/** Opens in survey mode — deliberately below [SNAP_ZOOM], so you get your bearings before browsing. */
const val INITIAL_ZOOM = 0.65f

/** Where a double tap lands you in browse mode — comfortably past [SNAP_ZOOM], not at the limit. */
const val DOUBLE_TAP_ZOOM = 1.2f

// ponytail: fling friction and the ignore-it threshold are guesses. Raise the friction if a flick
// throws you halfway across the library; lower it if crossing the canvas still feels like work.
const val FLING_FRICTION = 1.4f
const val MIN_FLING_DP_PER_S = 40f

/**
 * Cell for a rank on a square spiral: rank 0 at the origin, then ring 1's eight cells, ring 2's
 * sixteen, and so on. Ring r holds ranks [(2r-1)^2, (2r+1)^2).
 */
fun spiralCell(rank: Int): IntOffset {
    require(rank >= 0) { "rank must be non-negative" }
    if (rank == 0) return IntOffset(0, 0)
    var r = ((sqrt(rank.toFloat()) + 1f) / 2f).toInt()
    // sqrt rounding can land a rank one ring off either way; nudge it back.
    while (ringBase(r) > rank) r--
    while (ringBase(r + 1) <= rank) r++
    val i = rank - ringBase(r)
    return when {
        i < 2 * r -> IntOffset(r, -r + 1 + i)                    // right column, bottom to top
        i < 4 * r -> IntOffset(r - 1 - (i - 2 * r), r)            // top row, right to left
        i < 6 * r -> IntOffset(-r, r - 1 - (i - 4 * r))           // left column, top to bottom
        else -> IntOffset(-r + 1 + (i - 6 * r), -r)               // bottom row, left to right
    }
}

/** Inverse of [spiralCell]. */
fun cellToRank(cell: IntOffset): Int {
    val r = max(abs(cell.x), abs(cell.y))
    if (r == 0) return 0
    val i = when {
        cell.x == r && cell.y != -r -> cell.y + r - 1
        cell.y == r -> 2 * r + (r - 1 - cell.x)
        cell.x == -r -> 4 * r + (r - 1 - cell.y)
        else -> 6 * r + cell.x + r - 1
    }
    return ringBase(r) + i
}

private fun ringBase(r: Int): Int = if (r <= 0) 0 else (2 * r - 1) * (2 * r - 1)

/** Stable per-item scatter within the cell, in [-JITTER, JITTER) of a cell in each axis. */
fun jitterFor(itemId: String): Offset =
    Offset(seededUnit("$itemId-x") * JITTER, seededUnit("$itemId-y") * JITTER)

/** Centre of an item on the plane, in dp. */
fun planeCenter(rank: Int, itemId: String): Offset {
    val cell = spiralCell(rank)
    val j = jitterFor(itemId)
    return Offset((cell.x + j.x) * CELL_DP, (cell.y + j.y) * CELL_DP)
}

/**
 * Size in dp, fitted inside an [ITEM_DP] square. Fitting the *longest* side rather than the
 * width is what keeps a 2:1 portrait item from overflowing its cell vertically.
 * [ratio] is width ÷ height, matching the feed's `previewRatio()`.
 */
fun itemSize(ratio: Float): Offset =
    if (ratio >= 1f) Offset(ITEM_DP, ITEM_DP / ratio) else Offset(ITEM_DP * ratio, ITEM_DP)

/**
 * Cell-coordinate window covering the viewport, widened by [margin] rings so items entering from
 * the edge are already loaded. Grid placement makes this arithmetic rather than a scan over
 * items, so composition cost is O(items on screen) at any library size.
 */
fun visibleCells(
    camera: Offset,
    viewportDp: Offset,
    zoom: Float,
    margin: Int = 1,
): Pair<IntRange, IntRange> {
    val halfW = viewportDp.x / 2f / zoom
    val halfH = viewportDp.y / 2f / zoom
    val xs = cellRange(camera.x - halfW, camera.x + halfW, margin)
    val ys = cellRange(camera.y - halfH, camera.y + halfH, margin)
    return xs to ys
}

private fun cellRange(minDp: Float, maxDp: Float, margin: Int): IntRange {
    val lo = floor(minDp / CELL_DP + 0.5f).toInt() - margin
    val hi = floor(maxDp / CELL_DP + 0.5f).toInt() + margin
    return lo..hi
}

/**
 * Half-width of the region the spiral actually occupies, in dp. O(1): the outermost ring is the
 * ring of the last rank, so there's no need to walk the items.
 */
fun planeRadiusDp(count: Int): Float {
    if (count <= 0) return 0f
    val cell = spiralCell(count - 1)
    return (max(abs(cell.x), abs(cell.y)) + 0.5f) * CELL_DP
}

/**
 * Keeps the camera over the placed region. Panning past the frontier lands you in a featureless
 * void with no cue for which way home is; rather than wrapping the plane around (which would put
 * the same item in two places and break both spatial memory and the shared-element keys), the
 * camera simply springs back on release.
 */
fun clampCamera(camera: Offset, radiusDp: Float): Offset =
    Offset(camera.x.coerceIn(-radiusDp, radiusDp), camera.y.coerceIn(-radiusDp, radiusDp))

/**
 * Camera shift that keeps the plane point under [centroid] pinned while the zoom changes from
 * [fromZoom] to [toZoom]. Without this, pinching zooms about the middle of the screen and the
 * thing you were reaching for slides away from your fingers.
 *
 * [centroid] and [viewportPx] are in pixels; the result is in plane dp.
 */
fun zoomAnchorShift(
    centroid: Offset,
    viewportPx: Offset,
    densityScale: Float,
    fromZoom: Float,
    toZoom: Float,
): Offset {
    val fromCenter = (centroid - viewportPx / 2f) / densityScale
    return fromCenter * (1f / fromZoom - 1f / toZoom)
}

/** Double tap toggles between the two modes rather than stepping through zoom levels. */
fun doubleTapZoom(current: Float): Float =
    if (current < SNAP_ZOOM) DOUBLE_TAP_ZOOM else INITIAL_ZOOM

/**
 * Fling velocity in screen px/s → camera velocity in plane dp/s. Negated: dragging content right
 * moves the camera left, and the fling has to keep going the way the content was travelling.
 */
fun flingVelocityDp(velocityPx: Offset, densityScale: Float, zoom: Float): Offset =
    -velocityPx / (densityScale * zoom)

/**
 * Rank of the item closest to the camera, or null if the canvas is empty. Only the nine cells
 * around the camera are considered — [cellsCannotCollide] guarantees the winner is among them.
 */
fun nearestRank(camera: Offset, count: Int, idOf: (Int) -> String): Int? {
    if (count <= 0) return null
    val cx = (camera.x / CELL_DP).roundToInt()
    val cy = (camera.y / CELL_DP).roundToInt()
    var best: Int? = null
    var bestDist = Float.MAX_VALUE
    for (dx in -1..1) for (dy in -1..1) {
        val rank = cellToRank(IntOffset(cx + dx, cy + dy))
        if (rank !in 0 until count) continue
        val d = (planeCenter(rank, idOf(rank)) - camera).getDistanceSquared()
        if (d < bestDist) { bestDist = d; best = rank }
    }
    return best
}
