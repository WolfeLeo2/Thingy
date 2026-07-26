package com.wolfeleo2.thingy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.SpacesLayout
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.ui.add.AddSheet
import com.wolfeleo2.thingy.ui.share.CollageShareSheet
import com.wolfeleo2.thingy.ui.tidy.TidyScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.GridView),
    SPACES("Spaces", Icons.Filled.Dashboard),
    TIDY("Tidy", Icons.Filled.Style),
    SEARCH("Search", Icons.Filled.Search),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun MainShell(
    userId: String?,
    library: LibraryViewModel,
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    classifier: com.wolfeleo2.thingy.data.Classifier,
    settings: SettingsRepository,
    embedder: Embedder,
    ingestor: ImageIngestor,
    videoIngestor: VideoIngestor,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    avatarUrl: String?,
    onOpenItem: (List<String>, Int) -> Unit,
    onOpenSpace: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSpaceSettings: (String?) -> Unit,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showAdd by remember { mutableStateOf(false) }
    var sharingSpace by remember { mutableStateOf<com.wolfeleo2.thingy.data.Space?>(null) }
    var invitingSpaceId by remember { mutableStateOf<String?>(null) }
    var addingToSpaceId by remember { mutableStateOf<String?>(null) }
    // Home multi-select (long-press to start). Lives here so the floating toolbar can morph into the
    // contextual action bar — it's drawn above the tab content, so a bar inside HomeFeed would be buried.
    val selectedIds = remember { mutableStateSetOf<String>() }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var addingSelectionToSpace by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    // Collected here (not inside the Spaces tab branch) so the DataStore read warms up in the
    // background from first composition — avoids a GRID-then-SHELF flash on first tab switch.
    val spacesLayout by settings.spacesLayout.collectAsStateWithLifecycle(SpacesLayout.GRID)
    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom, // toolbar exits downward off-screen
    )
    // A selection is Home's alone — switching tabs drops it rather than leaving a stale count.
    LaunchedEffect(tab) { if (tab != Tab.HOME) selectedIds.clear() }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds.clear() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior),
        topBar = {
            TopAppBar(
                title = { Text(tab.let { if (it == Tab.HOME) "Thingy" else it.label }, fontWeight = FontWeight.ExtraBold) },
                actions = { 
                    IconButton(onClick = onOpenMap) { Icon(Icons.Filled.Map, contentDescription = "Map") }
                    AvatarButton(url = avatarUrl, onClick = onOpenSettings, modifier = Modifier.padding(end = 8.dp)) 
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Each tab keeps its own UI state (scroll, query) across tab switches.
            stateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    Tab.HOME -> HomeFeed(
                        library, itemRepository, spaceRepository, classifier, settings,
                        sharedTransitionScope, animatedVisibilityScope, onOpenItem,
                        onAddToSpace = { addingToSpaceId = it },
                        selectedIds = selectedIds,
                        onToggleSelect = { id -> if (!selectedIds.add(id)) selectedIds.remove(id) },
                    )
                    Tab.SPACES -> {
                        if (spacesLayout == SpacesLayout.SHELF) {
                            ShelfSpacesScreen(library, spaceRepository, itemRepository, onOpenSpace, onOpenSpaceSettings, onShare = { sharingSpace = it }, onInvite = { invitingSpaceId = it.id })
                        } else {
                            SpacesGrid(library, spaceRepository, itemRepository, onOpenSpace, onOpenSpaceSettings, onShare = { sharingSpace = it }, onInvite = { invitingSpaceId = it.id })
                        }
                    }
                    Tab.TIDY -> TidyScreen(ingestor, Modifier.padding(bottom = 88.dp))
                    Tab.SEARCH -> SearchScreen(library, itemRepository, spaceRepository, classifier, settings, embedder, onOpenItem, PaddingValues(), sharedTransitionScope, animatedVisibilityScope)
                }
            }

            // Expressive floating toolbar with a docked FAB — swaps to a vibrant contextual bar
            // (count + the two bulk actions) while a Home selection is live.
            AnimatedContent(
                targetState = selectedIds.isNotEmpty(),
                transitionSpec = { fadeIn() + scaleIn(initialScale = 0.85f) togetherWith fadeOut() },
                label = "toolbar",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) { selecting ->
                if (selecting) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    ) {
                        IconButton(onClick = { selectedIds.clear() }) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text("${selectedIds.size}")
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                            }
                        }
                        IconButton(onClick = { addingSelectionToSpace = true }) {
                            Icon(Icons.Filled.Dashboard, contentDescription = "Add to shelf")
                        }
                        IconButton(onClick = { confirmBulkDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                } else {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        scrollBehavior = scrollBehavior,
                        floatingActionButton = {
                            FloatingToolbarDefaults.StandardFloatingActionButton(
                                onClick = { if (tab == Tab.SPACES) onOpenSpaceSettings(null) else showAdd = true },
                            ) { Icon(Icons.Filled.Add, contentDescription = if (tab == Tab.SPACES) "New space" else "Add") }
                        },
                    ) {
                        Tab.entries.forEach { t -> NavIcon(t, selected = tab == t) { tab = t } }
                    }
                }
            }
        }

        if (showAdd) {
            AddSheet(
                onSaveNote = { text -> scope.launch { itemRepository.createNote(text) }; showAdd = false },
                onSaveArticle = { url -> scope.launch { itemRepository.createLink(url) }; showAdd = false },
                onPhotosPicked = { uris -> 
                    val cr = context.contentResolver
                    scope.launch { 
                        uris.forEach { uri -> 
                            runCatching { 
                                if (cr.getType(uri)?.startsWith("video/") == true) {
                                    videoIngestor.ingestUri(uri)
                                } else {
                                    ingestor.ingestUri(uri, asSticker = false)
                                }
                            } 
                        } 
                    } 
                },
                onOpenCamera = onOpenCamera,
                onDismiss = { showAdd = false },
            )
        }

        sharingSpace?.let { space ->
            val members by remember(space.id) { spaceRepository.membershipsForSpace(space.id) }.collectAsStateWithLifecycle(emptyList())
            val live = members.filter { it.status != SpaceItemStatus.DISMISSED.wire }
            val itemIds = remember(live) { live.map { it.itemId } }
            val spaceItems by remember(itemIds) { itemRepository.itemsByIds(itemIds) }.collectAsStateWithLifecycle(emptyList())

            CollageShareSheet(
                spaceName = space.name,
                items = spaceItems,
                onDismiss = { sharingSpace = null }
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

        invitingSpaceId?.let { id ->
            InviteSheet(spaceId = id, spaceRepository = spaceRepository, onDismiss = { invitingSpaceId = null })
        }

        if (addingSelectionToSpace) {
            // The writes run on MainShell's scope, not the dialog's: dismissing the dialog tears down a
            // rememberCoroutineScope inside it, which cancelled the Firestore batch before it committed.
            val fileSelection: (suspend () -> String) -> Unit = { resolveSpaceId ->
                val ids = selectedIds.toList()
                addingSelectionToSpace = false
                selectedIds.clear()
                scope.launch {
                    val spaceId = resolveSpaceId()
                    spaceRepository.addItemsToSpace(ids, spaceId)
                    ids.forEach { classifier.steerItemForSpace(it, spaceId) }
                }
            }
            AddManyToSpaceDialog(
                count = selectedIds.size,
                spaceRepository = spaceRepository,
                onPick = { spaceId -> fileSelection { spaceId } },
                onCreateSpace = { name -> fileSelection { spaceRepository.createSpace(name) } },
                onDismiss = { addingSelectionToSpace = false },
            )
        }

        if (confirmBulkDelete) {
            val count = selectedIds.size
            AlertDialog(
                onDismissRequest = { confirmBulkDelete = false },
                title = { Text("Delete $count ${if (count == 1) "thingy" else "thingies"}?") },
                text = { Text("They're removed from Home and every space. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        val ids = selectedIds.toList()
                        confirmBulkDelete = false
                        selectedIds.clear()
                        scope.launch { itemRepository.deleteAll(ids) }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun NavIcon(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilledIconButton(onClick = onClick) { Icon(tab.icon, contentDescription = tab.label) }
    } else {
        IconButton(onClick = onClick) {
            Icon(tab.icon, contentDescription = tab.label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpacesGrid(
    library: LibraryViewModel,
    spaceRepository: SpaceRepository,
    itemRepository: ItemRepository,
    onOpenSpace: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onShare: (com.wolfeleo2.thingy.data.Space) -> Unit,
    onInvite: (com.wolfeleo2.thingy.data.Space) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val myUid = spaceRepository.currentUserId
    val spaces by library.spaces.collectAsStateWithLifecycle()

    val spaceList = spaces ?: return // loading
    if (spaceList.isEmpty()) {
        ThingyEmptyState(
            shape = MaterialShapes.Clover4Leaf,
            icon = Icons.Filled.Dashboard,
            title = "No spaces yet",
            message = "Make a shelf — Thingy pulls in matching saves.",
            actionLabel = "Create a space",
            onAction = { onEdit(null) },
        )
        return
    }
    Grid {
        items(spaceList, key = { it.id }) { space ->
            // spaceId-scoped (not the owner-only library flows) so a shared space's cover shows
            // items added by any member, not just the ones this user personally saved.
            val members by remember(space.id) { spaceRepository.membershipsForSpace(space.id) }.collectAsStateWithLifecycle(emptyList())
            val live = members.filter { it.status != SpaceItemStatus.DISMISSED.wire }
            val itemIds = remember(live) { live.map { it.itemId } }
            val spaceItems by remember(itemIds) { itemRepository.itemsByIds(itemIds) }.collectAsStateWithLifecycle(emptyList())
            val preview = spaceItems.maxByOrNull { it.createdAt?.time ?: 0L }
            val hasSuggestion = live.any { it.status == SpaceItemStatus.SUGGESTED.wire }
            CoverStack(
                name = space.name, preview = preview, hasSuggestion = hasSuggestion,
                onClick = { onOpenSpace(space.id) },
                onEdit = { onEdit(space.id) },
                onShare = { onShare(space) },
                onInvite = { onInvite(space) },
                onDelete = { scope.launch { spaceRepository.deleteSpace(space.id) } },
                isOwner = space.userId == myUid,
                onLeave = { myUid?.let { u -> scope.launch { spaceRepository.removeMember(space.id, u) } } },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
internal fun Grid(content: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope.() -> Unit) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        // bottom padding clears the floating toolbar
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}
