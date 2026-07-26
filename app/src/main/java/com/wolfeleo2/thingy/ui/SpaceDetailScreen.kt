package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.ui.add.AddSheet
import com.wolfeleo2.thingy.ui.share.CollageShareSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
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
    val members by remember(spaceId) { spaceRepository.membersForSpace(spaceId) }.collectAsStateWithLifecycle(emptyList())
    val memberships by remember(spaceId) { spaceRepository.membershipsForSpace(spaceId) }.collectAsStateWithLifecycle(emptyList())
    val membershipItemIds = memberships.map { it.itemId }.distinct()
    val items by remember(membershipItemIds) { itemRepository.itemsByIds(membershipItemIds) }.collectAsStateWithLifecycle(emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var showCollage by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
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
                navigationIcon = { AppBarAction(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack) },
                actions = {
                    MemberAvatarStack(members = members, onClick = { showMembers = true })
                    if (suggested.isNotEmpty()) {
                        ButtonGroup(
                            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState = menuState) },
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                        ) {
                            customItem(
                                buttonGroupContent = {
                                    val interactionSource = remember { MutableInteractionSource() }
                                    FilledTonalIconButton(
                                        onClick = {
                                            burstTrigger++
                                            scope.launch {
                                                spaceRepository.acceptAll(spaceId).forEach { classifier.steerItemForSpace(it, spaceId) }
                                            }
                                        },
                                        interactionSource = interactionSource,
                                        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                                        modifier = Modifier.animateWidth(interactionSource)
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = "Accept all")
                                    }
                                },
                                menuContent = { menuState ->
                                    DropdownMenuItem(
                                        text = { Text("Accept all") },
                                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                                        onClick = {
                                            burstTrigger++
                                            scope.launch {
                                                spaceRepository.acceptAll(spaceId).forEach { classifier.steerItemForSpace(it, spaceId) }
                                            }
                                            menuState.dismiss()
                                        }
                                    )
                                }
                            )
                            customItem(
                                buttonGroupContent = {
                                    val interactionSource = remember { MutableInteractionSource() }
                                    FilledTonalIconButton(
                                        onClick = { scope.launch { spaceRepository.dismissAll(spaceId) } },
                                        interactionSource = interactionSource,
                                        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                                        modifier = Modifier.animateWidth(interactionSource)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Dismiss all")
                                    }
                                },
                                menuContent = { menuState ->
                                    DropdownMenuItem(
                                        text = { Text("Dismiss all") },
                                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                        onClick = {
                                            scope.launch { spaceRepository.dismissAll(spaceId) }
                                            menuState.dismiss()
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Box {
                        AppBarAction(icon = Icons.Filled.MoreVert, contentDescription = "More", onClick = { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit shelf") },
                                onClick = { menu = false; onEdit() },
                            )
                            DropdownMenuItem(
                                text = { Text("Invite") },
                                onClick = { menu = false; showInvite = true },
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
                        // Hard-delete (asset + all memberships) is owner-only — the rules block a
                        // non-owner's items delete, so others get "Remove from space" instead.
                        onDelete = if (item.userId == spaceRepository.currentUserId) {
                            { scope.launch { itemRepository.delete(item.id) } }
                        } else null,
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
                    itemRepository = itemRepository,
                    classifier = classifier,
                    onDismiss = { addingToSpaceId = null }
                )
            }

            ShapeBurstEffect(trigger = burstTrigger)

            if (showMembers) {
                MembersSheet(
                    spaceId = spaceId,
                    spaceRepository = spaceRepository,
                    onInvite = { showMembers = false; showInvite = true },
                    onDismiss = { showMembers = false },
                )
            }
            if (showInvite) {
                InviteSheet(
                    spaceId = spaceId,
                    spaceRepository = spaceRepository,
                    onDismiss = { showInvite = false },
                )
            }
        }
    }
}
