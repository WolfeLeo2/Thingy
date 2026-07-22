package com.wolfeleo2.thingy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.ItemStatus
import com.wolfeleo2.thingy.data.Space
import com.wolfeleo2.thingy.data.SpaceItem
import com.wolfeleo2.thingy.data.SpaceItemStatus
import com.wolfeleo2.thingy.data.SpaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A tag shared by enough ungrouped saves to be worth its own space. */
data class SpaceSuggestion(val tag: String, val itemIds: List<String>)

// A cluster needs at least this many ungrouped items to surface as a suggestion.
private const val MIN_SUGGESTION_CLUSTER = 5

/**
 * Holds the library's live data as hot StateFlows scoped to the Home nav entry. Because it's
 * retained across tab switches + config changes (and kept warm 5s after the last collector),
 * the feed/spaces don't re-subscribe and never flash empty. `null` = still loading.
 */
class LibraryViewModel(
    itemRepository: ItemRepository = ItemRepository(),
    spaceRepository: SpaceRepository = SpaceRepository(),
) : ViewModel() {
    val items: StateFlow<List<Item>?> =
        itemRepository.items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val spaces: StateFlow<List<Space>?> =
        spaceRepository.spaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val memberships: StateFlow<List<SpaceItem>> =
        spaceRepository.memberships().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Frequency-cluster tags across ready, ungrouped items into candidate "make a space?" prompts.
    // Top 3 so a couple of dismissals still leave something to surface next.
    val spaceSuggestions: StateFlow<List<SpaceSuggestion>> =
        combine(items, spaces, memberships) { items, spaces, memberships ->
            val grouped = memberships.filter { it.status == null || it.status == SpaceItemStatus.SAVED.wire }
                .mapTo(mutableSetOf()) { it.itemId }
            val spaceNames = spaces.orEmpty().mapTo(mutableSetOf()) { it.name.trim().lowercase() }
            val byTag = mutableMapOf<String, MutableList<String>>()
            items.orEmpty().asSequence()
                .filter { it.status == ItemStatus.READY.wire && it.id !in grouped }
                .forEach { item -> item.tags.forEach { tag ->
                    if (tag.lowercase() !in spaceNames) byTag.getOrPut(tag) { mutableListOf() }.add(item.id)
                } }
            byTag.entries.filter { it.value.size >= MIN_SUGGESTION_CLUSTER }
                .sortedByDescending { it.value.size }
                .take(3)
                .map { SpaceSuggestion(it.key, it.value) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
