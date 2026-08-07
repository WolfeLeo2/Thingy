package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.displayTitle
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.reminders.ReminderManager
import com.wolfeleo2.thingy.ui.reminders.ResurfaceCard
import com.wolfeleo2.thingy.ui.reminders.SnoozeSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeFeed(
    library: LibraryViewModel,
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    classifier: Classifier,
    settings: SettingsRepository,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenItem: (List<String>, Int) -> Unit,
    onAddToSpace: (String) -> Unit,
    // Selection is owned by MainShell so the floating toolbar (which lives there, above this content)
    // can morph into the contextual action bar.
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    // The lens itself is owned by MainShell, where the bar that drives it is drawn.
    filter: TypeFilter,
    sortField: SortField,
    sortAscending: Boolean,
    onClearFilter: () -> Unit,
) {
    val context = LocalContext.current
    val items by library.items.collectAsStateWithLifecycle()
    val suggestions by library.spaceSuggestions.collectAsStateWithLifecycle()
    val dismissed by settings.dismissedSuggestions.collectAsStateWithLifecycle(emptySet())
    val resurfacedId by settings.resurfacedItemId.collectAsStateWithLifecycle(null)
    var snoozeTarget by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notify = LocalNotify.current

    LaunchedEffect(Unit) {
        ReminderManager.scheduleDailyResurface(context)
    }

    val all = items ?: return // loading — blank for the frame before the cache resolves
    val list = remember(all, filter, sortField, sortAscending) {
        applyFilterSort(all, filter, sortField, sortAscending)
    }
    if (list.isEmpty()) {
        // A filter that matches nothing must not look like a lost library, so it says which lens is
        // on and offers the way back out.
        val filtered = filter != TypeFilter.ALL
        ThingyEmptyState(
            shape = MaterialShapes.Cookie9Sided,
            icon = filter.icon,
            title = filter.emptyMessage,
            message = if (filtered) "Nothing here matches this filter." else "Everything lands in one calm feed.",
            actionLabel = if (filtered) "Show all" else null,
            onAction = if (filtered) onClearFilter else null,
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
            LaunchedEffect(suggestion?.tag) {
                val s = suggestion ?: return@LaunchedEffect
                val result = snackbarHostState.showSnackbar(
                    message = "Create a \"${s.tag.replaceFirstChar { it.uppercase() }}\" space?",
                    actionLabel = "Create",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> {
                        burstTrigger++
                        val spaceId = spaceRepository.createSpace(name = s.tag.replaceFirstChar { it.uppercase() }, dynamic = true)
                        s.itemIds.forEach { id -> spaceRepository.addItemToSpace(id, spaceId) }
                        classifier.recommendForSpace(spaceId)
                    }
                    SnackbarResult.Dismissed -> settings.dismissSuggestion(s.tag)
                }
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
                            onDelete = { scope.launch { itemRepository.delete(item.id); notify("${item.displayTitle()} deleted") } },
                            selectionActive = selectedIds.isNotEmpty(),
                            selected = item.id in selectedIds,
                            onToggleSelect = { onToggleSelect(item.id) },
                        )
                    }
                }
            }
        }

        ShapeBurstEffect(trigger = burstTrigger)

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        snoozeTarget?.let { target ->
            SnoozeSheet(
                item = target,
                settings = settings,
                onDismiss = { snoozeTarget = null }
            )
        }
    }
}
