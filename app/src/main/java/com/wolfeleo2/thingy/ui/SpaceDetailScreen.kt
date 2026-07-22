package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.ui.add.AddSheet
import com.wolfeleo2.thingy.ui.share.CollageShareSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SpaceDetailScreen(
    spaceId: String,
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    classifier: Classifier,
    ingestor: ImageIngestor,
    videoIngestor: VideoIngestor,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenItem: (List<String>, Int) -> Unit,
    onEdit: () -> Unit,
    onOpenCamera: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val space by remember(spaceId) { spaceRepository.space(spaceId) }.collectAsStateWithLifecycle(null)
    val memberships by remember(spaceId) { spaceRepository.membershipsForSpace(spaceId) }.collectAsStateWithLifecycle(emptyList())
    val items by remember { itemRepository.items() }.collectAsStateWithLifecycle(emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var showCollage by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addingToSpaceId by remember { mutableStateOf<String?>(null) }
    var burstTrigger by remember { mutableIntStateOf(0) }

    val itemById = items.associateBy { it.id }
    // saved (or legacy absent) first, then suggestions.
    val live = memberships.filter { it.status != SpaceItemStatus.DISMISSED.wire }
    val saved = live.filter { it.status != SpaceItemStatus.SUGGESTED.wire }.mapNotNull { m -> itemById[m.itemId]?.let { m to it } }
    val suggested = live.filter { it.status == SpaceItemStatus.SUGGESTED.wire }.mapNotNull { m -> itemById[m.itemId]?.let { m to it } }
    val ordered = saved + suggested
    val ids = ordered.map { it.second.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(space?.name ?: "Space", fontWeight = FontWeight.ExtraBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit shelf") },
                                onClick = { menu = false; onEdit() },
                            )
                            DropdownMenuItem(
                                text = { Text("Share collage") },
                                onClick = { menu = false; showCollage = true },
                                enabled = saved.isNotEmpty(),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add to space") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                if (suggested.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        ReviewHeader(
                            count = suggested.size,
                            onAcceptAll = {
                                burstTrigger++
                                scope.launch {
                                    spaceRepository.acceptAll(spaceId).forEach { classifier.steerItemForSpace(it, spaceId) }
                                }
                            },
                            onDismissAll = {
                                scope.launch { spaceRepository.dismissAll(spaceId) }
                            }
                        )
                    }
                }

                items(ordered, key = { it.first.id }) { (membership, item) ->
                    val isSuggested = membership.status == SpaceItemStatus.SUGGESTED.wire
                    val index = ids.indexOf(item.id)
                    ItemCard(
                        item = item,
                        onClick = { onOpenItem(ids, index.coerceAtLeast(0)) },
                        modifier = Modifier.animateItem(),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        suggested = isSuggested,
                        onAccept = if (isSuggested) {
                            {
                                burstTrigger++
                                scope.launch {
                                    spaceRepository.acceptSuggestion(membership.id)
                                    classifier.steerItemForSpace(item.id, spaceId)
                                }
                            }
                        } else null,
                        onAddToSpace = { addingToSpaceId = item.id },
                        onDismiss = if (isSuggested) {
                            { scope.launch { spaceRepository.dismissSuggestion(membership.id) } }
                        } else null,
                        onRemove = if (!isSuggested) {
                            { scope.launch { spaceRepository.removeMembership(membership.id) } }
                        } else null,
                        onDelete = { scope.launch { itemRepository.delete(item.id) } },
                    )
                }
            }
            if (showAdd) {
                AddSheet(
                    onSaveNote = { text -> scope.launch { itemRepository.createNote(text, spaceId) }; showAdd = false },
                    onSaveArticle = { url -> scope.launch { itemRepository.createLink(url, spaceId) }; showAdd = false },
                    onPhotosPicked = { uris ->
                        val cr = context.contentResolver
                        scope.launch {
                            uris.forEach { uri ->
                                runCatching {
                                    if (cr.getType(uri)?.startsWith("video/") == true) {
                                        videoIngestor.ingestUri(uri, spaceId = spaceId)
                                    } else {
                                        ingestor.ingestUri(uri, asSticker = false, spaceId = spaceId)
                                    }
                                }
                            }
                        }
                    },
                    onOpenCamera = onOpenCamera,
                    onDismiss = { showAdd = false },
                )
            }
            if (showCollage) {
                CollageShareSheet(
                    spaceName = space?.name ?: "Space",
                    items = saved.map { it.second },
                    onDismiss = { showCollage = false },
                )
            }

            addingToSpaceId?.let { id ->
                ManageSpacesDialog(
                    itemId = id,
                    spaceRepository = spaceRepository,
                    classifier = classifier,
                    onDismiss = { addingToSpaceId = null }
                )
            }

            ShapeBurstEffect(trigger = burstTrigger)
        }
    }
}

@Composable
private fun ReviewHeader(
    count: Int,
    onAcceptAll: () -> Unit,
    onDismissAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        "$count new suggestions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Review them for this shelf",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAcceptAll,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                    shapes = expressiveButtonShapes()
                ) {
                    Text("Accept all")
                }
                OutlinedButton(
                    onClick = onDismissAll,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                    shapes = expressiveButtonShapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text("Dismiss all")
                }
            }
        }
    }
}
