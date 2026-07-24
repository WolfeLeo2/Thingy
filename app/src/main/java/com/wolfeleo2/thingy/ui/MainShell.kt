package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var addingToSpaceId by remember { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()
    // Keyed by uid so a sign-out/sign-in swap gets a fresh ViewModel instead of briefly showing
    // the previous account's stale spaceSuggestions/items before the new Firestore snapshot lands.
    val library: LibraryViewModel = key(userId) { viewModel { LibraryViewModel() } }
    // Collected here (not inside the Spaces tab branch) so the DataStore read warms up in the
    // background from first composition — avoids a GRID-then-SHELF flash on first tab switch.
    val spacesLayout by settings.spacesLayout.collectAsStateWithLifecycle(SpacesLayout.GRID)
    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom, // toolbar exits downward off-screen
    )

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
                    Tab.HOME -> HomeFeed(library, itemRepository, spaceRepository, classifier, settings, sharedTransitionScope, animatedVisibilityScope, onOpenItem, onAddToSpace = { addingToSpaceId = it })
                    Tab.SPACES -> {
                        if (spacesLayout == SpacesLayout.SHELF) {
                            ShelfSpacesScreen(library, spaceRepository, onOpenSpace, onOpenSpaceSettings, onShare = { sharingSpace = it })
                        } else {
                            SpacesGrid(library, spaceRepository, onOpenSpace, onOpenSpaceSettings, onShare = { sharingSpace = it })
                        }
                    }
                    Tab.TIDY -> TidyScreen(ingestor, Modifier.padding(bottom = 88.dp))
                    Tab.SEARCH -> SearchScreen(library, itemRepository, spaceRepository, classifier, settings, embedder, onOpenItem, PaddingValues(), sharedTransitionScope, animatedVisibilityScope)
                }
            }

            // Expressive floating toolbar with a docked FAB .
            HorizontalFloatingToolbar(
                expanded = true,
                scrollBehavior = scrollBehavior,
                floatingActionButton = {
                    FloatingToolbarDefaults.StandardFloatingActionButton(
                        onClick = { if (tab == Tab.SPACES) onOpenSpaceSettings(null) else showAdd = true },
                    ) { Icon(Icons.Filled.Add, contentDescription = if (tab == Tab.SPACES) "New space" else "Add") }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                Tab.entries.forEach { t -> NavIcon(t, selected = tab == t) { tab = t } }
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
            val members = library.memberships.collectAsStateWithLifecycle().value
                .filter { it.spaceId == space.id && it.status != SpaceItemStatus.DISMISSED.wire }
            val items = library.items.collectAsStateWithLifecycle().value.orEmpty().associateBy { it.id }
            val spaceItems = members.mapNotNull { items[it.itemId] }
            
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
    onOpenSpace: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onShare: (com.wolfeleo2.thingy.data.Space) -> Unit,
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
            onAction = { onEdit(null) },
        )
        return
    }
    val itemById = items.orEmpty().associateBy { it.id }
    Grid {
        items(spaceList, key = { it.id }) { space ->
            val members = memberships.filter { it.spaceId == space.id && it.status != SpaceItemStatus.DISMISSED.wire }
            val preview = members.mapNotNull { itemById[it.itemId] }.maxByOrNull { it.createdAt?.time ?: 0L }
            val hasSuggestion = members.any { it.status == SpaceItemStatus.SUGGESTED.wire }
            CoverStack(
                name = space.name, preview = preview, hasSuggestion = hasSuggestion,
                onClick = { onOpenSpace(space.id) },
                onEdit = { onEdit(space.id) },
                onShare = { onShare(space) },
                onDelete = { scope.launch { spaceRepository.deleteSpace(space.id) } },
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
