package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.Item
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
) {
    val context = LocalContext.current
    val items by library.items.collectAsStateWithLifecycle()
    val suggestions by library.spaceSuggestions.collectAsStateWithLifecycle()
    val dismissed by settings.dismissedSuggestions.collectAsStateWithLifecycle(emptySet())
    val resurfacedId by settings.resurfacedItemId.collectAsStateWithLifecycle(null)
    var snoozeTarget by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
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
