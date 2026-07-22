package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.ItemType
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapScreen(
    itemRepository: ItemRepository,
    onOpenItem: (List<String>, Int, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val items by itemRepository.items().collectAsStateWithLifecycle(emptyList())
    
    val located = remember(items) {
        items.filter { it.latitude != null && it.longitude != null }
    }
    
    val clusters = remember(located) {
        located.groupBy { 
            // Round to ~100m precision (3 decimal places) to group nearby photos
            Pair((it.latitude!! * 1000).roundToInt() / 1000.0, (it.longitude!! * 1000).roundToInt() / 1000.0)
        }.values.toList()
    }
    
    var selectedCluster by remember { mutableStateOf<List<Item>?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0,0,0,0),
    ) { padding ->
        Box(modifier = Modifier.
            fillMaxSize()
            .consumeWindowInsets(padding)) {
            if (located.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ThingyEmptyState(
                        shape = MaterialShapes.Sunny,
                        icon = Icons.Filled.Place,
                        title = "Nothing on the map yet",
                        message = "Photos you save keep the place they were taken.\nNew saves with location data will show up here."
                    )
                }
            } else {
                val lats = located.map { it.latitude!! }
                val lons = located.map { it.longitude!! }
                val latMin = lats.minOrNull() ?: 0.0
                val latMax = lats.maxOrNull() ?: 0.0
                val lonMin = lons.minOrNull() ?: 0.0
                val lonMax = lons.maxOrNull() ?: 0.0
                val span = max(latMax - latMin, lonMax - lonMin)
                val zoom = if (span == 0.0) 13.0 else minOf(13.0, maxOf(2.0, log2(360.0 / span) - 0.5))
                val centerLat = (latMin + latMax) / 2
                val centerLon = (lonMin + lonMax) / 2

                MapboxMap(
                    modifier = Modifier.fillMaxSize(),
                    scaleBar = {}
                ) {
                    MapEffect(Unit) { mapView ->
                        mapView.mapboxMap.loadStyle(Style.STANDARD)
                        mapView.mapboxMap.setCamera(
                            CameraOptions.Builder()
                                .center(Point.fromLngLat(centerLon, centerLat))
                                .zoom(zoom)
                                .build()
                        )
                    }

                    clusters.forEach { cluster ->
                        val item = cluster.first()
                        ViewAnnotation(
                            options = viewAnnotationOptions {
                                geometry(Point.fromLngLat(item.longitude!!, item.latitude!!))
                                allowOverlap(true)
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable {
                                        if (cluster.size == 1) {
                                            val allIds = items.map { it.id }
                                            val index = allIds.indexOf(item.id).takeIf { it >= 0 } ?: 0
                                            onOpenItem(allIds, index, true)
                                        } else {
                                            selectedCluster = cluster
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val url = item.previewUrl()
                                if (url != null) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = item.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Place,
                                        contentDescription = "Marker",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                
                                if (cluster.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "${cluster.size}", 
                                            color = MaterialTheme.colorScheme.onPrimary, 
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = onBack,
                shape = FloatingActionButtonDefaults.mediumShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
    }
    
    val currentCluster = selectedCluster
    if (currentCluster != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedCluster = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(rememberMaterialShape(MaterialShapes.Sunny))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = "${currentCluster.size} thingies here",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(Modifier.height(24.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .weight(1f, fill = false),
            ) {
                items(currentCluster) { cItem ->
                    val curl = cItem.previewUrl()
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                selectedCluster = null
                                val allIds = items.map { it.id }
                                val index = allIds.indexOf(cItem.id).takeIf { it >= 0 } ?: 0
                                onOpenItem(allIds, index, true)
                            }
                    ) {
                        if (curl != null) {
                            AsyncImage(
                                model = curl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (cItem.type == ItemType.VIDEO.wire) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(32.dp)
                                    .background(Color.Black.copy(0.4f), CircleShape)
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
