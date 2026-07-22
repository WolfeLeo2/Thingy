package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemType
import com.wolfeleo2.thingy.data.Space
import com.wolfeleo2.thingy.data.SpaceItem
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import kotlinx.coroutines.launch

// Curated Expressive shapes used as the shelf's decorative "rivets" — picked per-space, per-slot
// via the same seeded jitter CoverStack uses, so they're varied but stable across recompositions.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun decorShapeFor(seed: String, slot: Int): RoundedPolygon {
    val shapes = listOf(MaterialShapes.Circle, MaterialShapes.Pill, MaterialShapes.Sunny, MaterialShapes.Cookie4Sided, MaterialShapes.Clover4Leaf)
    val pick = (((seededUnit("$seed-shape$slot") + 1f) / 2f) * shapes.size).toInt().coerceIn(0, shapes.size - 1)
    return shapes[pick]
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShelfSpacesScreen(
    library: LibraryViewModel,
    spaceRepository: SpaceRepository,
    onOpenSpace: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onShare: (Space) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spaces by library.spaces.collectAsStateWithLifecycle()
    val memberships by library.memberships.collectAsStateWithLifecycle()
    val items by library.items.collectAsStateWithLifecycle()

    val spaceList = spaces ?: return // loading
    if (spaceList.isEmpty()) {
        ThingyEmptyState(
            shape = MaterialShapes.Clover4Leaf,
            icon = Icons.Filled.Dashboard,
            title = "No spaces yet",
            message = "Make a shelf — Thingy pulls in matching saves.",
            actionLabel = "Create a space",
            onAction = { onEdit(null) }
        )
        return
    }
    val itemById = items.orEmpty().associateBy { it.id }

    LazyColumn(
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(spaceList, key = { it.id }) { space ->
            val spaceMemberships = memberships.filter { it.spaceId == space.id && it.status != SpaceItemStatus.DISMISSED.wire }
            val covers = spaceMemberships
                .mapNotNull { m -> itemById[m.itemId]?.let { item -> m to item } }
                .sortedByDescending { it.second.createdAt?.time ?: 0L }
            val hasSuggestions = spaceMemberships.any { it.status == SpaceItemStatus.SUGGESTED.wire }
            ShelfRow(
                space = space,
                covers = covers,
                hasSuggestions = hasSuggestions,
                spaceRepository = spaceRepository,
                onOpenSpace = onOpenSpace,
                onEdit = onEdit,
                onShare = onShare,
                onDelete = { scope.launch { spaceRepository.deleteSpace(space.id) } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfRow(
    space: Space,
    covers: List<Pair<SpaceItem, Item>>,
    hasSuggestions: Boolean,
    spaceRepository: SpaceRepository,
    onOpenSpace: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onShare: (Space) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val topItem = covers.firstOrNull()?.second

    // Precompute + persist the board color once per newest-item change — a plain read afterward,
    // so only the first view of a new top item pays for a live Palette extraction.
    LaunchedEffect(topItem?.id) {
        val item = topItem ?: return@LaunchedEffect
        if (space.shelfColorItemId == item.id) return@LaunchedEffect
        val model = item.previewModel(context) ?: return@LaunchedEffect
        val seed = extractPaletteSeed(context, model) ?: return@LaunchedEffect
        spaceRepository.setShelfColor(space.id, item.id, seed.toLong())
    }

    val boardScheme = space.shelfColor?.takeIf { space.shelfColorItemId == topItem?.id }
        ?.let { seedColorScheme(it.toInt(), isDark) }
    val boardColor = boardScheme?.primaryContainer ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val onBoard = boardScheme?.onPrimaryContainer ?: MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenSpace(space.id) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasSuggestions) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = "Has suggestions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                "${covers.size} ${if (covers.size == 1) "thingy" else "thingies"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit shelf") }, onClick = { menu = false; onEdit(space.id) })
                    DropdownMenuItem(text = { Text("Share collage") }, onClick = { menu = false; onShare(space) })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp)) {
            LazyRow(
                contentPadding = PaddingValues(start = 26.dp, end = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 18.dp),
            ) {
                items(covers, key = { it.second.id }) { (membership, item) ->
                    val isSuggested = membership.status == SpaceItemStatus.SUGGESTED.wire
                    val ratio = (item.aspectRatio?.toFloat() ?: if (item.type == ItemType.LINK.wire) 0.7f else 0.72f)
                        .coerceIn(0.55f, 0.9f)
                    val url = item.previewUrl()
                    Surface(
                        color = Color.White, shadowElevation = 4.dp, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxHeight(0.85f).aspectRatio(ratio).clickable { onOpenSpace(space.id) },
                    ) {
                        Box {
                            if (url != null) {
                                AsyncImage(model = url, contentDescription = item.title, contentScale = ContentScale.Crop,
                                    modifier = Modifier.padding(3.dp).clip(RoundedCornerShape(7.dp)).fillMaxSize())
                            }
                            if (isSuggested) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = "Suggested",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(4.dp).size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The shelf board itself — Glassmorphic: semi-transparent + background blur.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
                    .blur(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(boardColor.copy(alpha = 0.7f))
                    .border(BorderStroke(0.5.dp, onBoard.copy(alpha = 0.1f)), RoundedCornerShape(18.dp))
            )

            // Random "decals" (etched stickers) stuck to the glass.
            repeat(2) { i ->
                val xJitter = seededUnit("${space.id}-decal-x$i")
                val yJitter = seededUnit("${space.id}-decal-y$i")
                val rot = seededUnit("${space.id}-decal-r$i") * 15f
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(x = (xJitter * 100).dp, y = (yJitter * 10 - 20).dp)
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rot }
                        .clip(rememberMaterialShape(decorShapeFor(space.id, i + 10)))
                        .background(onBoard.copy(alpha = 0.12f))
                )
            }

            // Decorative "rivets" — now styled as physical hardware (screw heads).
            listOf(0, 1).forEach { slot ->
                val align = if (slot == 0) Alignment.BottomStart else Alignment.BottomEnd
                val jitter = (seededUnit("${space.id}-riv$slot") + 1f) / 2f
                val sizeDp = (18 + jitter * 8).dp
                Surface(
                    color = onBoard.copy(alpha = 0.25f),
                    shape = MaterialShapes.Clover4Leaf.toShape(),
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(align)
                        .padding(horizontal = 30.dp, vertical = 18.dp)
                        .size(sizeDp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // The screw head slot/plus
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = onBoard.copy(alpha = 0.4f),
                            modifier = Modifier.size(sizeDp * 0.7f).graphicsLayer { rotationZ = 45f }
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete \"${space.name}\"?") },
            text = { Text("Your saves stay in Home — only the shelf goes away.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
