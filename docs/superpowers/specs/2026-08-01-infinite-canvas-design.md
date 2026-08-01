# Infinite canvas — design

Date: 2026-08-01
Status: approved, not yet implemented

## Summary

A full-screen pan/zoom plane holding every saved thingy. Cards show the image alone — no
caption furniture. Zoomed out you survey; zoomed in, each pan settles onto the nearest item,
which scales up and reveals its title and date. Tapping it opens the detail screen through the
existing shared-element morph.

The canvas is a *view*, not a workspace. Nothing is dragged, nothing is stored, no Firestore
field is added. Positions are derived from data the items already carry.

## Non-goals

- Arranging. No drag, no persisted positions, no `x`/`y` on `Item`.
- Per-space canvases. Home-level only. The composable takes a list of items, so a space canvas
  is a later caller, not a later rewrite.
- Multi-select, long-press menus, delete. The canvas is for looking; the feed already does
  managing.
- Magnetic pull while a finger is down. Snap fires on gesture settle only.

## Placement

Two independent halves: which cell an item occupies, and where inside that cell it sits.

### Cell — spiral by creation rank

Items are sorted by `createdAt` **ascending** and assigned successive cells on a square spiral
walking outward from the origin: rank 0 at `(0, 0)`, then ring 1's eight cells, then ring 2's
sixteen, and so on.

Ascending order is the load-bearing detail. `LibraryViewModel.items` is newest-first, and
spiralling in that order would shift every item one cell each time something is saved — the
canvas would slide out from under you on every save, destroying the spatial memory that makes a
canvas worth panning in the first place. Ascending order means a new item takes the next free
cell at the growing edge and nothing already placed ever moves.

The ordering also gives the plane a meaning for free: the centre is the oldest thing you saved,
the rim is this week. The camera opens parked on the newest item, at the frontier.

A deleted item leaves its cell empty. That is deliberate — closing the gap would move every
item after it. A hole in a scatter is invisible.

**Known crack:** an item that syncs in late carrying an older `createdAt` inserts mid-sequence
and shifts everything newer than it by one cell. Rare at this app's scale and self-limiting
(one insert, one cell). Marked with a `ponytail:` comment; the fix, if it ever bites, is to
rank by a monotonic first-seen stamp instead of `createdAt`.

### Position within the cell — seeded jitter

Cells are a fixed square, `CELL_DP = 200.dp`. An item is fitted inside an `ITEM_DP = 130.dp`
square: taking `previewRatio()` (width ÷ height, clamped 0.5–2.0, the same helper the feed
uses), a landscape item is `ITEM_DP` wide and shorter, a portrait item is `ITEM_DP` tall and
narrower. Fitting the *longest* side rather than the width is what keeps a 2:1 portrait item
from overflowing its cell vertically. The item is then centred in the cell and offset by a
jitter of up to `JITTER = 0.16` of the cell in each axis.

Jitter comes from `seededUnit(item.id)` — the FNV-1a helper already in `CoverStack.kt`, already
`internal`, already used for exactly this kind of stable per-item randomness. Two calls with
different salts (`"${id}-x"`, `"${id}-y"`) give the two axes.

The jitter budget is bounded so cells cannot collide:
`ITEM_DP + 2 × JITTER × CELL_DP = 130 + 64 = 194 ≤ CELL_DP`, leaving at least 6dp of air
between neighbours in the worst case. That is the property that keeps "which item is nearest the
centre" an arithmetic lookup rather than a search, and it is asserted in the tests — the three
constants cannot be retuned independently.

## Camera and gestures

Camera state is `offset: Offset` (plane coordinates of the viewport centre) and `zoom: Float`,
held in a small `CanvasState` class with `mutableStateOf` backing.

- Pan and pinch via `Modifier.pointerInput` / `detectTransformGestures`.
- Zoom clamped to `MIN_ZOOM = 0.35f .. MAX_ZOOM = 2.5f`.
- Offset unclamped. The plane is finite (items occupy a bounded spiral) but the camera is not
  fenced in; panning into empty space and back is harmless and cheaper than edge-resistance.
- Initial camera: centred on the highest-ranked item, `zoom = 1f`.

## Snap and focus

Snapping is **zoom-gated**. Below `SNAP_ZOOM = 0.9f` the canvas is in survey mode: free panning,
no snap, no captions, everything small. At or above it, browse mode engages.

In browse mode, on gesture end:

1. `nearestRank(camera, viewport)` picks the item whose plane position is closest to the
   viewport centre.
2. The camera animates to centre it, using `MaterialTheme.motionScheme.slowSpatialSpec<Offset>()`
   — the Material 3 Expressive spatial springs are what supply the bounce, so no hand-tuned
   `spring()` is needed and the motion matches the rest of the app.
3. That item becomes focused.

The focused item scales to `FOCUS_SCALE = 1.18f` via `animateFloatAsState` with
`motionScheme.fastSpatialSpec()`, and its title and relative date fade in beneath it
(`formatItemDate(createdAt)` from `lib/DateFormat.kt`, matching the rest of the app). Link items
also show `siteName`, mirroring the feed's caption so the same item reads the same way in both
places. Unfocused items sit at 1.0 with no caption. Dropping below `SNAP_ZOOM` clears the focus.

Nothing is focused in survey mode, so the caption never appears over a field of tiny thumbnails.

## Rendering

One `Box` sized to the viewport. The camera is a `graphicsLayer` on an inner container
(`translationX/Y`, `scaleX/Y`); children are placed at absolute plane offsets. No custom
`Layout` is needed — offsets plus a parent transform do it.

Only visible items are composed. `visibleCells(camera, viewport)` converts the viewport
rectangle into a cell-coordinate range, expanded by a one-cell margin ring so items entering
from the edge are already loaded. Because placement is a grid, this is arithmetic, not a scan:
cost is O(items on screen) regardless of library size, and the same arithmetic feeds the snap.

Item content reuses the feed's rendering wholesale — `previewUrl()` / `feedImageRequest()` for
images, videos and links (so the canvas shares the feed's Coil memory-cache entries and the
detail hero still gets its instant placeholder), and `TextFace`-equivalent surfaces for notes
and voice notes, which have no thumbnail. `TextFace` is currently `private` in `ItemCard.kt` and
becomes `internal`.

Captions, overflow menus, selection rings, the processing spinner and the sync badge are all
omitted. The canvas shows the image and nothing else.

## Navigation

`Canvas` joins `nav/Keys.kt` as a `data object NavKey` and gets an `entry<Canvas>` in `AppRoot`,
sitting alongside `Map` — same shape, same hoisted `LibraryViewModel`, so no second Firestore
listener. `MainShell`'s top bar gains an `Icons.Filled.ScatterPlot` action next to Map, wired
through a new `onOpenCanvas` parameter.

Tapping an item calls `onOpenItem(ids, index)` with the canvas's own ordering, pushing
`ItemDetail` exactly as the feed does. Unlike `Map`, the canvas passes the real
`SharedTransitionScope` — its cards *are* the framed image, so `"item-image-{id}"` morphs into
the detail hero properly. Key collision with the feed is impossible: `NavDisplay` composes only
the top entry, so `Home` is not in the composition while `Canvas` is.

Predictive back is off app-wide (`android:enableOnBackInvokedCallback="false"` in the manifest,
disabled because it competed with the shared-element transition). The canvas inherits that and
needs no special handling; back pops through the normal path and reverses the morph.

## Files

New:

- `ui/canvas/CanvasLayout.kt` — pure placement and camera math, no Compose imports beyond
  geometry types.
- `ui/canvas/CanvasScreen.kt` — the composable: gestures, culling, focus, caption, tap-out.
- `app/src/test/java/com/wolfeleo2/thingy/ui/canvas/CanvasLayoutTest.kt`

Edited:

- `nav/Keys.kt` — `data object Canvas : NavKey`
- `ui/AppRoot.kt` — `entry<Canvas>`, `onOpenCanvas` wiring
- `ui/MainShell.kt` — top bar action, `onOpenCanvas` parameter
- `ui/ItemCard.kt` — `TextFace` `private` → `internal`

## Tests

Everything decidable is a pure function in `CanvasLayout.kt`, so this is plain JUnit with no
device and no Compose test rule:

- `spiralCell(0)` is the origin; the first 25 ranks are distinct; ring index is non-decreasing
  in rank; no duplicate cell over 1000 ranks.
- `jitterFor(id)` is deterministic across calls and bounded by `JITTER`.
- Jitter budget: `ITEM_DP + 2 * JITTER * CELL_DP <= CELL_DP`, so cells cannot collide.
- **Stability regression:** place a list of items, prepend a newer item, re-place — every
  original item keeps its exact position. This is the test that guards the whole design
  decision; if it ever fails the canvas has started sliding under the user.
- `visibleCells` returns exactly the cells intersecting a known viewport rect, plus the margin
  ring, for a handful of hand-computed cameras.
- `nearestRank` returns the centred item when the camera sits on it, and the expected neighbour
  when nudged toward it.

## Tuning knobs

Set by reasoning, not by feel; expect to move them once it is on a real device. Each gets a
`ponytail:` comment naming it as such.

| Constant | Value | What it controls |
|---|---|---|
| `CELL_DP` | 200.dp | Spacing — density of the scatter |
| `ITEM_DP` | 130.dp | Card's longest side at zoom 1 |
| `JITTER` | 0.16 | How un-grid-like the scatter reads |
| `SNAP_ZOOM` | 0.9f | Where survey mode becomes browse mode |
| `FOCUS_SCALE` | 1.18f | How much the focused item lifts |
| `MIN_ZOOM` / `MAX_ZOOM` | 0.35f / 2.5f | Zoom range |

`SNAP_ZOOM` is the one most likely to be wrong. If the canvas feels like it is fighting you,
raise it; if leaning in does not feel like it starts helping, lower it.

## Revisions after the first device run (2026-08-01)

**Camera state is saveable, not remembered.** `NavDisplay` composes only the top entry, so pushing
`ItemDetail` disposes the canvas. Held in a plain `remember`, the camera re-parked on the newest
item on the way back, putting the tapped card somewhere else on screen — or off it — and the
shared element had nothing to morph into. A `listSaver`-backed `CanvasCamera` restores the exact
view, which is what makes the return leg of the transition land. The `parked` flag is part of the
saved state, so first-open parking doesn't fight the restore.

**Opens in survey mode.** `INITIAL_ZOOM = 0.55f`, below `SNAP_ZOOM`, so you land looking at the
shape of your library rather than at one item. A test asserts the two constants stay in that
relationship.

**Zoom anchors on the pinch centroid.** Pinching used to zoom about the middle of the screen, so
whatever you were reaching for slid away from your fingers. `zoomAnchorShift` pins the plane point
under the centroid.

**The camera is clamped to the placed region** (`planeRadiusDp` / `clampCamera`), springing back on
release. This replaces a proposed wrap-around "spherical" canvas, which was rejected: wrapping puts
the same item at multiple points on the plane, which destroys the spatial memory that motivated
stable placement, breaks `nearestRank`'s one-position-one-item assumption, and — concretely — would
put two nodes on screen claiming the same `item-image-{id}` shared key. Clamping solves the actual
complaint (panning into a featureless void) without any of that.

**Fling momentum.** A `VelocityTracker` follows the *accumulated pan* rather than one pointer's
position, so lifting one finger out of a pinch doesn't register as a flick. On release the camera
coasts under `exponentialDecay`, then the clamp rubber-bands it back and the snap finishes the job.
Touching down stops a coasting canvas dead. The caption is suppressed while settling so it doesn't
strobe through a dozen titles on the way past them.

**Double tap toggles survey ↔ browse** (`doubleTapZoom`), anchored on the tap point — the anchor
shift is exact at any intermediate zoom, so stepping it alongside the zoom animation keeps the
tapped point pinned for the whole ride. It only works over empty canvas: a card's `clickable`
consumes the tap first, and it has to, since delaying every card tap by the double-tap timeout to
make the gesture reachable there would make opening an item feel broken.

## Deliberate limits

- No arranging, by design. If it is ever wanted it is a genuinely different feature with a
  Firestore field behind it.
- Backdated inserts shift newer items by one cell (see Placement).
- Deleted items leave gaps.
- The canvas composes from `LibraryViewModel.items`, which is this user's own items. Co-members'
  items in shared spaces are not on the Home canvas — consistent with the Home feed.
