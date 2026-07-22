package com.wolfeleo2.thingy.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.graphics.shapes.RoundedPolygon
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.ui.expressiveButtonShapes
import com.wolfeleo2.thingy.ui.extractPaletteSeed
import com.wolfeleo2.thingy.ui.previewModel
import com.wolfeleo2.thingy.ui.rememberMaterialShape
import com.wolfeleo2.thingy.ui.seedColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

private const val MAX_TILES = 6

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val EMBLEM_SHAPES = listOf(
    MaterialShapes.Clover4Leaf to "Clover 4",
    MaterialShapes.Clover8Leaf to "Clover 8",
    MaterialShapes.Bun to "Bun",
    MaterialShapes.Cookie4Sided to "Cookie 4",
    MaterialShapes.Cookie6Sided to "Cookie 6",
    MaterialShapes.Cookie7Sided to "Cookie 7",
    MaterialShapes.SoftBoom to "Soft Bloom",
    MaterialShapes.Flower to "Flower",
    MaterialShapes.Sunny to "Sunny",
    MaterialShapes.Heart to "Heart",
)

/**
 * Share a space as a collage image. Preloads the space's thumbnails, lays them out as an Expressive
 * "card" (colored header pill + masonry mosaic + wordmark), captures that composable to a PNG via a
 * graphics layer, and hands it to the system share sheet through the app's FileProvider.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollageShareSheet(
    spaceName: String,
    items: List<Item>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    var tiles by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var headerSeed by remember { mutableStateOf<Int?>(null) }
    var selectedEmblemIndex by remember { mutableIntStateOf(Random.nextInt(EMBLEM_SHAPES.size)) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(items) {
        val firstItem = items.firstOrNull()
        if (firstItem != null) {
            val model = firstItem.previewModel(context)
            if (model != null) {
                headerSeed = extractPaletteSeed(context, model)
            }
        }
        tiles = items.take(MAX_TILES).mapNotNull { loadTile(context, it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Share this space", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            when (val loaded = tiles) {
                null -> Box(Modifier.fillMaxWidth().height(320.dp), Alignment.Center) { CircularWavyProgressIndicator() }
                else -> if (loaded.isEmpty()) {
                    Text(
                        "Nothing to make a collage from yet — add a few thingies with images first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The very composable we capture. drawWithContent records it into the layer each frame.
                    Box(Modifier.fillMaxWidth().drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                    }) {
                        CollageCard(
                            spaceName = spaceName,
                            count = items.size,
                            tiles = loaded,
                            headerSeed = headerSeed,
                            emblemShape = EMBLEM_SHAPES[selectedEmblemIndex].first,
                            isDark = isDark,
                        )
                    }

                    // Header Emblem picker chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Emblem Shape",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            itemsIndexed(EMBLEM_SHAPES) { index, (shape, label) ->
                                val isSelected = index == selectedEmblemIndex
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedEmblemIndex = index },
                                    label = { Text(label) },
                                    leadingIcon = {
                                        Box(
                                            Modifier
                                                .size(16.dp)
                                                .clip(rememberMaterialShape(shape))
                                                .background(if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (!sharing) {
                                sharing = true
                                scope.launch {
                                    shareCollage(context, graphicsLayer.toImageBitmap().asAndroidBitmap(), spaceName)
                                    sharing = false
                                    onDismiss()
                                }
                            }
                        },
                        enabled = !sharing,
                        shapes = expressiveButtonShapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (sharing) "Preparing…" else "Share collage")
                    }
                }
            }
        }
    }
}

private suspend fun loadTile(context: Context, item: Item): ImageBitmap? {
    val model = item.previewModel(context) ?: return null
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(1080, 1080))
            .allowHardware(false) // software bitmap so it draws cleanly into the capture layer
            .build()
        context.imageLoader.execute(request).image
            ?.asDrawable(context.resources)?.toBitmap()?.asImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollageCard(
    spaceName: String,
    count: Int,
    tiles: List<ImageBitmap>,
    headerSeed: Int?,
    emblemShape: RoundedPolygon,
    isDark: Boolean,
) {
    val cols = if (tiles.size <= 4) 2 else 3
    // Masonry: drop each tile into the currently-shortest column (heights in aspect units).
    val buckets = remember(tiles, cols) {
        val heights = FloatArray(cols)
        val b = List(cols) { mutableListOf<ImageBitmap>() }
        tiles.forEach { t ->
            val i = heights.indices.minByOrNull { heights[it] } ?: 0
            b[i].add(t)
            heights[i] += (t.height.toFloat() / t.width).coerceIn(0.7f, 1.4f)
        }
        b
    }

    val headerScheme = remember(headerSeed, isDark) {
        headerSeed?.let { seedColorScheme(it, isDark) }
    }
    val headerBg = headerScheme?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer
    val headerFg = headerScheme?.onPrimaryContainer ?: MaterialTheme.colorScheme.onPrimaryContainer

    val heroShape = remember { RoundedCornerShape(topStart = 32.dp, topEnd = 10.dp, bottomEnd = 26.dp, bottomStart = 10.dp) }
    val standardShape = remember { RoundedCornerShape(14.dp) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Colored header pill — carries space identity, matches hero palette color, and embeds Expressive emblem.
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(headerBg)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(rememberMaterialShape(emblemShape))
                    .background(headerFg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Stars,
                    contentDescription = null,
                    tint = headerBg,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    spaceName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = headerFg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$count ${if (count == 1) "thingy" else "thingies"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = headerFg.copy(alpha = 0.75f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buckets.forEach { column ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    column.forEach { t ->
                        val isHero = (t == tiles.first())
                        val aspect = (t.width.toFloat() / t.height).coerceIn(0.7f, 1.4f)
                        val shape = if (isHero) heroShape else standardShape
                        Image(
                            bitmap = t,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(shape),
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(rememberMaterialShape(emblemShape))
                    .background(headerScheme?.primary ?: MaterialTheme.colorScheme.primary)
            )
            Text(
                "Made with Thingy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun shareCollage(context: Context, bmp: Bitmap, spaceName: String) {
    runCatching {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "space-collage.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "“$spaceName” — my collection on Thingy")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share “$spaceName”"))
    }.onFailure { Log.w("Thingy", "collage share failed", it) }
}
