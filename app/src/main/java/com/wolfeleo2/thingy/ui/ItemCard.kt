package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemStatus
import com.wolfeleo2.thingy.data.ItemType

/**
 * Returns the best available preview for this item.
 * - If offline-synced or natively captured, returns a local File (instant, no network).
 * - Otherwise fall back to imageUrl (Cloudinary HTTPS URL, requires connectivity or Coil cache).
 * Returning Any? lets Coil handle File, String, and Uri uniformly.
 */
@Composable
fun Item.previewUrl(): Any? = previewModel(LocalContext.current)

// Feed and detail share a per-item memory-cache key so the detail hero can show the feed's
// already-decoded bitmap instantly (as a placeholder) instead of fading in from blank — kills the
// flash when opening/scrolling the detail pager. Feed writes the key; hero reads it as placeholder.
@Composable
fun Item.feedImageRequest(url: Any): ImageRequest =
    ImageRequest.Builder(LocalContext.current).data(url).memoryCacheKey(id).build()

@Composable
fun Item.heroImageRequest(url: Any): ImageRequest =
    ImageRequest.Builder(LocalContext.current).data(url).placeholderMemoryCacheKey(id).build()

/** Non-composable variant of [previewUrl] — resolvable off the composition thread (e.g. from a coroutine). */
fun Item.previewModel(context: android.content.Context): Any? = when (type) {
    ItemType.IMAGE.wire, ItemType.VIDEO.wire, ItemType.LINK.wire -> {
        val native = storagePath?.takeIf { it.startsWith("/") }
            ?.let { java.io.File(it) }?.takeIf { it.exists() }
        val ext = if (type == ItemType.VIDEO.wire) "mp4" else "webp"
        val synced = java.io.File(context.filesDir, "saved/${id}.$ext")
        val legacy = java.io.File(context.filesDir, "saved/${id}.media")
        native
            ?: synced.takeIf { it.exists() }
            ?: legacy.takeIf { it.exists() }
            ?: if (type == ItemType.LINK.wire) heroImageUrl else imageUrl
    }
    else -> null
}

private fun Item.previewRatio(): Float {
    val default = if (type == ItemType.LINK.wire) 1.91f else 1f
    return (aspectRatio?.toFloat() ?: default).coerceIn(0.5f, 2.0f)
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ItemCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    suggested: Boolean = false,
    onAccept: (() -> Unit)? = null,
    onAddToSpace: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasMenu = onAccept != null || onAddToSpace != null || onDismiss != null || onRemove != null || onDelete != null
    val motionSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val hasImage = item.previewUrl() != null

    // Image items: morph the framed image (white border / die-cut and all) card↔hero.
    // Text items (no image): morph the whole card container, so they animate in too.
    // Each item uses exactly ONE shared node — sharing both made the image detach mid-flight.
    val imageShared: Modifier = if (hasImage && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "item-image-${item.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = BoundsTransform { _, _ -> motionSpec },
            )
        }
    } else Modifier
    val containerShared: Modifier = if (!hasImage && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "item-card-${item.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = BoundsTransform { _, _ -> motionSpec },
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)),
            )
        }
    } else Modifier

    Box {
        Column(
            modifier
                .then(containerShared)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = { if (hasMenu) menu = true }),
        ) {
            Box {
                val url: Any? = item.previewUrl()
                if (url != null) ImageFace(item, url, imageShared) else TextFace(item)
                if (ItemStatus.from(item.status) == ItemStatus.PROCESSING) {
                    Box(
                        Modifier.matchParentSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                }
                if (suggested) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .combinedClickable(onClick = { onAccept?.invoke() }),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome, contentDescription = "Accept suggestion",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(6.dp).size(18.dp),
                        )
                    }
                }
            }
            Caption(item, onMore = { if (hasMenu) menu = true })
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            onAccept?.let { DropdownMenuItem(text = { Text("Add to shelf") }, onClick = { menu = false; it() }) }
            onAddToSpace?.let { DropdownMenuItem(text = { Text("Add to shelf") }, onClick = { menu = false; it() }) }
            onDismiss?.let { DropdownMenuItem(text = { Text("Dismiss") }, onClick = { menu = false; it() }) }
            onRemove?.let {
                DropdownMenuItem(
                    text = { Text("Remove from space", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; it() },
                )
            }
            onDelete?.let {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this thingy?") },
            text = { Text("It's removed from Home and every space. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ImageFace(item: Item, url: Any, imageShared: Modifier = Modifier) {
    if (item.sticker == true) {
        AsyncImage(model = item.feedImageRequest(url), contentDescription = item.title, contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .then(imageShared))
    } else {
        Surface(color = Color.White, shadowElevation = 2.dp, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().then(imageShared)) {
            Box {
                AsyncImage(model = item.feedImageRequest(url), contentDescription = item.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp))
                        .fillMaxWidth().aspectRatio(item.previewRatio()))

                if (item.type == ItemType.VIDEO.wire) {
                    Box(
                        Modifier.matchParentSize().padding(12.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextFace(item: Item) {
    val isNote = item.type == ItemType.NOTE.wire
    Surface(
        color = if (isNote) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = item.note ?: item.title ?: item.url ?: "…",
            style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Caption(item: Item, onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.type == ItemType.LINK.wire && item.siteName != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.siteName, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.NorthEast, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(12.dp))
                }
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
