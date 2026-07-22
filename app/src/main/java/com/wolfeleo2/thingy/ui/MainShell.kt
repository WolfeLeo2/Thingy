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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.SpacesLayout
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.reminders.ReminderManager
import com.wolfeleo2.thingy.ui.add.AddSheet
import com.wolfeleo2.thingy.ui.reminders.ResurfaceCard
import com.wolfeleo2.thingy.ui.reminders.SnoozeSheet
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
@Composable
fun MainShell(
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
    val library: LibraryViewModel = viewModel { LibraryViewModel() }
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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeFeed(
    library: LibraryViewModel,
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    classifier: com.wolfeleo2.thingy.data.Classifier,
    settings: SettingsRepository,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenItem: (List<String>, Int) -> Unit,
    onAddToSpace: (String) -> Unit,
) {
    val context = LocalContext.current
    val items by library.items.collectAsStateWithLifecycle()
    val suggestions by library.spaceSuggestions.collectAsStateWithLifecycle()
    val dismissed by settings.dismissedSuggestions.collectAsStateWithLifecycle(emptySet())
    val resurfacedId by settings.resurfacedItemId.collectAsStateWithLifecycle(null)
    var snoozeTarget by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        ReminderManager.scheduleDailyResurface(context)
    }

    val list = items ?: return // loading — blank for the frame before the cache resolves
    if (list.isEmpty()) {
        ThingyEmptyState(
            shape = MaterialShapes.Cookie9Sided,
            icon = Icons.Filled.GridView,
            title = "Nothing saved yet",
            message = "Everything lands in one calm feed.",
        )
        return
    }
    val ids = list.map { it.id }
    val suggestion = suggestions.firstOrNull { it.tag.lowercase() !in dismissed }
    val resurfacedItem = remember(resurfacedId, list) {
        if (resurfacedId != null) list.firstOrNull { it.id == resurfacedId } else null
    }
    var burstTrigger by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            resurfacedItem?.let { item ->
                val index = ids.indexOf(item.id)
                ResurfaceCard(
                    item = item,
                    onOpen = { onOpenItem(ids, index.coerceAtLeast(0)) },
                    onSnooze = { snoozeTarget = item },
                    onDismiss = { scope.launch { settings.dismissResurfacing() } }
                )
            }
            suggestion?.let { s ->
                SpaceSuggestionCard(
                    suggestion = s,
                    onCreate = {
                        burstTrigger++
                        scope.launch {
                            val spaceId = spaceRepository.createSpace(name = s.tag.replaceFirstChar { it.uppercase() }, dynamic = true)
                            s.itemIds.forEach { id -> spaceRepository.addItemToSpace(id, spaceId) }
                            classifier.recommendForSpace(spaceId)
                        }
                    },
                    onDismiss = { scope.launch { settings.dismissSuggestion(s.tag) } },
                )
            }
            Box(Modifier.weight(1f)) {
                Grid {
                    itemsIndexed(list, key = { _, it -> it.id }) { index, item ->
                        ItemCard(
                            item = item,
                            onClick = { onOpenItem(ids, index) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            modifier = Modifier.animateItem(),
                            onAddToSpace = { onAddToSpace(item.id) },
                            onDelete = { scope.launch { itemRepository.delete(item.id) } },
                        )
                    }
                }
            }
        }

        ShapeBurstEffect(trigger = burstTrigger)

        snoozeTarget?.let { target ->
            SnoozeSheet(
                item = target,
                settings = settings,
                onDismiss = { snoozeTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpaceSuggestionCard(
    suggestion: SpaceSuggestion,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(rememberMaterialShape(MaterialShapes.Sunny))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Create a \"${suggestion.tag.replaceFirstChar { it.uppercase() }}\" space?",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${suggestion.itemIds.size} saves would fit right in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onCreate, shapes = expressiveButtonShapes()) { Text("Create") }
            }
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
private fun Grid(content: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope.() -> Unit) {
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

@Composable
private fun EmptyState(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
