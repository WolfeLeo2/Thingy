package com.wolfeleo2.thingy.ui.canvas

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemType
import com.wolfeleo2.thingy.lib.formatItemDate
import com.wolfeleo2.thingy.ui.ImageFace
import com.wolfeleo2.thingy.ui.ImageFaceCorner
import com.wolfeleo2.thingy.ui.ImageFaceElevation
import com.wolfeleo2.thingy.ui.ImageFaceFrame
import com.wolfeleo2.thingy.ui.LibraryViewModel
import com.wolfeleo2.thingy.ui.TextFace
import com.wolfeleo2.thingy.ui.ThingyEmptyState
import com.wolfeleo2.thingy.ui.previewRatio
import com.wolfeleo2.thingy.ui.previewUrl
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A pan/zoom plane holding every saved thingy — the image alone, no caption furniture. Opens in
 * survey mode ([INITIAL_ZOOM], below [SNAP_ZOOM]): free panning, nothing focused. Pinch past
 * [SNAP_ZOOM] and it's browse mode: each pan settles onto the nearest item, which lifts and shows
 * its title and date.
 *
 * A *view*, not a workspace: nothing is dragged, nothing is stored. Placement is derived from the
 * item's creation rank and a hash of its id — see CanvasLayout.kt.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CanvasScreen(
    library: LibraryViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenItem: (List<String>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val items by library.items.collectAsStateWithLifecycle()

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            val list = items
            when {
                list == null -> Unit // loading — blank for the frame before the cache resolves
                list.isEmpty() -> ThingyEmptyState(
                    shape = MaterialShapes.Sunny,
                    icon = Icons.Filled.ScatterPlot,
                    title = "Nothing to spread out yet",
                    message = "Save a few thingies and they'll scatter across the canvas.",
                )
                else -> CanvasPlane(list, sharedTransitionScope, animatedVisibilityScope, onOpenItem)
            }

            FloatingActionButton(
                onClick = onBack,
                shape = FloatingActionButtonDefaults.mediumShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
    }
}

/**
 * Where the camera is looking. Saveable, and that is the whole point: `NavDisplay` composes only
 * the top entry, so pushing ItemDetail disposes the canvas. Held in a plain `remember` the camera
 * reset to the newest item on the way back, which put the tapped card somewhere else on screen —
 * or off it entirely — and the shared element had nothing to morph into. Restoring the exact view
 * is what makes the return leg of the transition land.
 */
@Stable
private class CanvasCamera(x: Float, y: Float, zoom: Float, parked: Boolean) {
    val offset = Animatable(Offset(x, y), Offset.VectorConverter)
    var zoom by mutableFloatStateOf(zoom)
    var parked by mutableStateOf(parked)

    /** Handed from the gesture loop to the settle effect. Deliberately not snapshot state — it's
     *  read once per gesture and writing it shouldn't recompose the plane. */
    var pendingFling: Offset = Offset.Zero

    companion object {
        val Saver = listSaver<CanvasCamera, Any>(
            save = { listOf(it.offset.value.x, it.offset.value.y, it.zoom, it.parked) },
            restore = { CanvasCamera(it[0] as Float, it[1] as Float, it[2] as Float, it[3] as Boolean) },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CanvasPlane(
    list: List<Item>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenItem: (List<String>, Int) -> Unit,
) {
    // Ascending, oldest first: rank 0 sits at the origin and a new save takes the next free cell at
    // the growing edge, so nothing already placed ever moves. See CanvasLayout.kt.
    val ordered = remember(list) { list.sortedBy { it.createdAt?.time ?: 0L } }
    val ids = remember(ordered) { ordered.map { it.id } }
    val radius = remember(ordered.size) { planeRadiusDp(ordered.size) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val camera = rememberSaveable(saver = CanvasCamera.Saver) {
        CanvasCamera(0f, 0f, INITIAL_ZOOM, parked = false)
    }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var gestureEnd by remember { mutableIntStateOf(0) }
    val snapSpec = MaterialTheme.motionScheme.slowSpatialSpec<Offset>()
    val zoomSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val decaySpec = remember { exponentialDecay<Offset>(frictionMultiplier = FLING_FRICTION) }

    // First open parks on the newest item — the frontier of the spiral, where the fresh stuff is.
    // Guarded by `parked` so coming back from the detail screen restores the saved view instead.
    LaunchedEffect(ordered.size) {
        if (!camera.parked && ordered.isNotEmpty()) {
            camera.offset.snapTo(planeCenter(ordered.lastIndex, ordered.last().id))
            camera.parked = true
        }
    }

    val focusedRank = if (camera.zoom >= SNAP_ZOOM) {
        nearestRank(camera.offset.value, ordered.size) { ordered[it].id }
    } else null

    // Settle-only: the canvas never pulls while a finger is down. On release it coasts, springs
    // back inside the placed region, then — in browse mode — centres whatever it came to rest near.
    // The Expressive spatial spring supplies the bounce, so there's no hand-tuned spring() here.
    LaunchedEffect(gestureEnd) {
        if (gestureEnd == 0) return@LaunchedEffect
        settling = true
        try {
            val fling = camera.pendingFling
            camera.pendingFling = Offset.Zero
            // Coasting past the frontier is fine and rather nice — the clamp below rubber-bands it.
            if (fling.getDistance() > MIN_FLING_DP_PER_S) camera.offset.animateDecay(fling, decaySpec)

            val settled = clampCamera(camera.offset.value, radius)
            val target = if (camera.zoom >= SNAP_ZOOM) {
                nearestRank(settled, ordered.size) { ordered[it].id }
                    ?.let { planeCenter(it, ordered[it].id) } ?: settled
            } else settled
            if (target != camera.offset.value) camera.offset.animateTo(target, snapSpec)
        } finally {
            settling = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clipToBounds()
            // Double tap toggles survey ↔ browse. Only over empty canvas: a card's `clickable`
            // consumes the tap first, and it has to — delaying every card tap by the double-tap
            // timeout to make the gesture reachable there would make opening an item feel broken.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val from = camera.zoom
                        val to = doubleTapZoom(from)
                        val start = camera.offset.value
                        val viewportPx = Offset(size.width.toFloat(), size.height.toFloat())
                        scope.launch {
                            // The anchor shift is exact at any intermediate zoom, so stepping it
                            // alongside the zoom keeps the tapped point pinned for the whole ride.
                            animate(from, to, animationSpec = zoomSpec) { z, _ ->
                                camera.zoom = z
                                scope.launch {
                                    camera.offset.snapTo(
                                        start + zoomAnchorShift(tap, viewportPx, density.density, from, z)
                                    )
                                }
                            }
                            gestureEnd++ // settle: clamp, and snap if we landed in browse mode
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Catching a coasting canvas stops it dead, as it should.
                    scope.launch { camera.offset.stop() }
                    dragging = true
                    val tracker = VelocityTracker()
                    var panTotal = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        var shift = Offset.Zero

                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            val from = camera.zoom
                            val to = (from * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            if (to != from) {
                                // Pin the plane point under the fingers instead of zooming about
                                // the middle of the screen.
                                shift += zoomAnchorShift(
                                    centroid = event.calculateCentroid(useCurrent = true),
                                    viewportPx = Offset(size.width.toFloat(), size.height.toFloat()),
                                    densityScale = density.density,
                                    fromZoom = from,
                                    toZoom = to,
                                )
                                camera.zoom = to
                            }
                        }

                        val pan = event.calculatePan()
                        // Screen px → plane dp: undo the density and the current zoom.
                        if (pan != Offset.Zero) shift -= pan / (density.density * camera.zoom)

                        // The tracker follows the accumulated pan rather than a single pointer's
                        // position, so lifting one finger of a pinch doesn't register as a flick.
                        event.changes.firstOrNull { it.pressed }?.let {
                            panTotal += pan
                            tracker.addPosition(it.uptimeMillis, panTotal)
                        }

                        if (shift != Offset.Zero) {
                            scope.launch { camera.offset.snapTo(camera.offset.value + shift) }
                            // Consumed only once the gesture is actually a drag, so a plain tap
                            // still reaches the card's clickable underneath.
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    dragging = false
                    val v = tracker.calculateVelocity()
                    camera.pendingFling =
                        flingVelocityDp(Offset(v.x, v.y), density.density, camera.zoom)
                    gestureEnd++
                }
            }
    ) {
        val viewportDp = Offset(maxWidth.value, maxHeight.value)
        val halfWpx = with(density) { maxWidth.toPx() } / 2f
        val halfHpx = with(density) { maxHeight.toPx() } / 2f
        val zoom = camera.zoom
        val eye = camera.offset.value
        val (xs, ys) = visibleCells(eye, viewportDp, zoom)

        // Screen-px centre of an item, given the camera. Shared by the cards and the caption.
        fun screenCenter(center: Offset) = Offset(
            halfWpx + (center.x - eye.x) * zoom * density.density,
            halfHpx + (center.y - eye.y) * zoom * density.density,
        )

        for (cy in ys) for (cx in xs) {
            val rank = cellToRank(IntOffset(cx, cy))
            if (rank !in ordered.indices) continue
            val item = ordered[rank]
            key(item.id) {
                CanvasCard(
                    item = item,
                    focused = rank == focusedRank,
                    zoom = zoom,
                    screenCenter = screenCenter(planeCenter(rank, item.id)),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onClick = { onOpenItem(ids, rank) },
                )
            }
        }

        // Caption for the focused item. Hidden mid-gesture and while the canvas is still coasting,
        // so it doesn't strobe through a dozen titles on the way past them.
        val fr = focusedRank
        if (fr != null && !dragging && !settling) {
            val focused = ordered[fr]
            val sizeDp = itemSize(cardRatio(focused))
            val center = screenCenter(planeCenter(fr, focused.id))
            val halfCardPx = with(density) { (sizeDp.y / 2f).dp.toPx() } * zoom * FOCUS_SCALE
            val gapPx = with(density) { 10.dp.toPx() }
            val captionWidth = (CELL_DP * 1.4f).dp
            val halfCaptionPx = with(density) { captionWidth.toPx() } / 2f
            Column(
                Modifier
                    .offset {
                        IntOffset(
                            (center.x - halfCaptionPx).roundToInt(),
                            (center.y + halfCardPx + gapPx).roundToInt(),
                        )
                    }
                    .width(captionWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = focused.title ?: focused.note ?: focused.url ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    focused.siteName?.takeIf { focused.type == ItemType.LINK.wire },
                    focused.createdAt?.time?.let { formatItemDate(it) },
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A link whose hero image never resolved falls through to [TextFace], and 1.91:1 of text is
 * unreadable at this size — so anything without a picture is laid out square.
 */
@Composable
private fun cardRatio(item: Item): Float = if (item.previewUrl() != null) item.previewRatio() else 1f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CanvasCard(
    item: Item,
    focused: Boolean,
    zoom: Float,
    screenCenter: Offset,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val url = item.previewUrl()
    val sizeDp = itemSize(cardRatio(item))
    val motionSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val lift by animateFloatAsState(
        if (focused) FOCUS_SCALE else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "focus",
    )

    // Zoom is applied by *sizing* the card, not by a graphicsLayer on the plane. Layout bounds are
    // then the truth, which is what sharedElement measures — a graphicsLayer would hand the detail
    // hero the unscaled rect and the morph would start from the wrong place.
    val scale = zoom * lift
    val wPx = with(density) { (sizeDp.x * scale).dp.toPx() }
    val hPx = with(density) { (sizeDp.y * scale).dp.toPx() }

    // Same one-node-per-item rule as the feed: images morph the framed picture, text items morph
    // the container. Sharing both at once makes the image detach mid-flight.
    val shared = with(sharedTransitionScope) {
        if (url != null) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "item-image-${item.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> motionSpec },
            )
        } else {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "item-card-${item.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = BoundsTransform { _, _ -> motionSpec },
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
            )
        }
    }

    Box(
        Modifier
            .offset {
                IntOffset((screenCenter.x - wPx / 2f).roundToInt(), (screenCenter.y - hPx / 2f).roundToInt())
            }
            .size((sizeDp.x * scale).dp, (sizeDp.y * scale).dp)
            .clickable(onClick = onClick)
            .then(if (url == null) shared else Modifier)
            // ponytail: a long note clips inside its square tile. The canvas is images-first and the
            // tile stays recognisable; give text items their own layout if it grates.
            .clipToBounds(),
    ) {
        // The shared node for an image is the framed element itself (white matte, die-cut and all),
        // exactly as in the feed — that's what the detail hero morphs from. The frame scales with
        // the card: sizing rather than scaling the card leaves absolute chrome untouched, which at
        // survey zoom turns a 4dp matte into a thick clumsy border around a thumbnail.
        if (url != null) {
            ImageFace(
                item, url, shared,
                frame = ImageFaceFrame * scale,
                corner = ImageFaceCorner * scale,
                elevation = ImageFaceElevation * scale,
            )
        } else {
            TextFace(item)
        }
    }
}
