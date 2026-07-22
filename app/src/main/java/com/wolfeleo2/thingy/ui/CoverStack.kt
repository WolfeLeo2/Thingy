package com.wolfeleo2.thingy.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemType



// Each card fills this fraction of the (ratio-shaped) stack area; the margin lets the
// tilted cards behind peek out without clipping (Amber's COVER_SCALE).
private const val COVER_SCALE = 0.82f

private data class Back(val rot: Float, val tx: Float, val ty: Float)
private val BACKS = listOf(Back(-4.5f, -6f, 5f), Back(4f, 7f, -3f))

// Stable pseudo-random in [-1, 1) from a seed (FNV-1a) — jitter fixed per space (Amber).
internal fun seededUnit(seed: String): Float {
    var h = -2128831035 // 2166136261 (0x811C9DC5) as a signed Int
    for (ch in seed) { h = h xor ch.code; h *= 16777619 }
    return ((h.toLong() and 0xFFFFFFFFL).toFloat() / 4294967295f) * 2f - 1f
}

// A hand-dropped pile of three same-size cards: two tilted white blanks behind and the real
// cover, barely rotated, on top. The box takes the cover's aspect ratio so the masonry packs
// spaces at their true proportions. Empty space → dashed placeholder.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CoverStack(
    name: String,
    preview: Item?,
    hasSuggestion: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val ratio = preview?.let {
        (it.aspectRatio?.toFloat() ?: if (it.type == ItemType.LINK.wire) 1.91f else 1f).coerceIn(0.6f, 1.9f)
    } ?: 1f

    // Springy press feedback (Expressive tactility).
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            BACKS.forEachIndexed { i, back ->
                // Backing cards tinted warm so the pile reads with depth (cover stays white matte on top).
                val backTint = if (i == 0) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.primaryContainer
                Slot(rot = back.rot + seededUnit("$name-b$i") * 1.5f, tx = back.tx, ty = back.ty) {
                    Surface(color = backTint, shadowElevation = 3.dp, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxSize()) {}
                }
            }
            val topRot = seededUnit("$name-top") * 2f
            Slot(rot = topRot, tx = 0f, ty = 0f) {
                val url = preview?.previewUrl()
                if (url != null) {
                    Surface(color = Color.White, shadowElevation = 6.dp, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxSize()) {
                        AsyncImage(model = url, contentDescription = name, contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).fillMaxSize())
                    }
                } else if (preview != null) {
                    // Text/note cover: a tinted matte card (no image to show).
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxSize()) {}
                } else {
                    DashedCover(Modifier.fillMaxSize())
                }
            }
            if (hasSuggestion) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                    Icon(Icons.Filled.AutoAwesome, "Has suggestions", tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(6.dp).size(18.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit shelf") },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share collage") },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("Your saves stay in Home — only the shelf goes away.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** One card slot: 82% of the stack area, centered, with a tilt + shove. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Slot(
    rot: Float, tx: Float, ty: Float, content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize(COVER_SCALE)
            .align(Alignment.Center)
            .graphicsLayer {
                rotationZ = rot
                translationX = tx.dp.toPx()
                translationY = ty.dp.toPx()
            },
    ) { content() }
}

@Composable
private fun DashedCover(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.outline
    Box(
        modifier.drawBehind {
            drawRoundRect(
                color = color,
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f))),
                cornerRadius = CornerRadius(36f, 36f),
            )
        },
    )
}
