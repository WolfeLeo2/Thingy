package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import kotlinx.coroutines.launch

/** Two modes:
  - Smart search OFF (default): instant in-memory substring filter over the denormalized
    searchText (PRD §4.3). No AI, no network.
  - Smart search ON (+ model downloaded): keyboard "search" embeds the query on-device and
    cosine-ranks the feed's stored vectors — semantic, offline, no key. Typing still shows the
    substring filter as a live preview until you press search.
  -  ponytail: no debounce — in-memory work over one user's saves is instant; add if it ever lags.

*/
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    library: LibraryViewModel,
    itemRepository: ItemRepository,
    spaceRepository: com.wolfeleo2.thingy.data.SpaceRepository,
    classifier: com.wolfeleo2.thingy.data.Classifier,
    settings: SettingsRepository,
    embedder: Embedder,
    onOpenItem: (List<String>, Int) -> Unit,
    padding: PaddingValues,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val all by library.items.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val smart by settings.smartSearchEnabled.collectAsStateWithLifecycle(false)
    val semantic = smart && remember(smart) { embedder.isReady() }
    var addingToSpaceId by remember { mutableStateOf<String?>(null) }

    // Semantic query state: keyboard "search" fills askIds with ranked ids for askQuery.
    // Typing clears it and falls back to the substring filter.
    var asking by rememberSaveable { mutableStateOf(false) }
    var askQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var askIds by rememberSaveable { mutableStateOf<List<String>?>(null) }

    val q = query.trim().lowercase()
    val results = when {
        askIds != null -> all.orEmpty().associateBy { it.id }.let { byId -> askIds!!.mapNotNull { byId[it] } }
        q.isEmpty() -> emptyList()
        else -> all.orEmpty().filter { it.searchText.lowercase().contains(q) }
    }
    val ids = results.map { it.id }

    Column(Modifier.fillMaxSize().padding(padding)) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = { query = it; askIds = null; askQuery = null },
            onSearch = {
                val ask = query.trim()
                if (semantic && ask.isNotEmpty()) {
                    asking = true
                    askQuery = ask
                    scope.launch {
                        val qv = embedder.embed(ask)
                        val scored = if (qv == null) emptyList() else all.orEmpty()
                            .mapNotNull { item -> item.embedding?.takeIf { it.isNotEmpty() }?.let { item to Embedder.cosine(qv, it) } }
                            .sortedByDescending { it.second }

                        // Keep genuine matches: above an absolute floor AND close to the best hit — this
                        // adapts to the model's narrow score band so a weak query can't drag in the whole feed.
                        val top = scored.firstOrNull()?.second ?: 0.0
                        askIds = scored
                            .filter { it.second >= Embedder.MIN_SCORE && it.second >= top - Embedder.RELATIVE_GAP }
                            .take(20)
                            .map { it.first.id }
                        asking = false
                    }
                }
            },
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(if (semantic) "Search, or ask your stuff" else "Search your saves") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (semantic) {
                { Icon(Icons.Filled.AutoAwesome, contentDescription = "Ask your stuff") }
            } else null,
            colors = SearchBarDefaults.inputFieldColors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        when {
            asking -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularWavyProgressIndicator() }
            askQuery != null && results.isEmpty() -> ThingyEmptyState(MaterialShapes.Sunny, Icons.Filled.AutoAwesome, "No matches", "Nothing answered “$askQuery”.")
            q.isEmpty() -> ThingyEmptyState(MaterialShapes.Sunny, Icons.Filled.Search, "Find anything",
                if (semantic) "Search titles and tags, or ask a question and press search." else "Search titles, tags, notes and links.")
            results.isEmpty() -> ThingyEmptyState(MaterialShapes.Sunny, Icons.Filled.Search, "Nothing yet", "No saves match “$query”.")
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(results, key = { _, it -> it.id }) { index, item ->
                    ItemCard(item = item, onClick = { onOpenItem(ids, index) }, modifier = Modifier.animateItem(),
                        sharedTransitionScope = sharedTransitionScope, animatedVisibilityScope = animatedVisibilityScope,
                        onAddToSpace = { addingToSpaceId = item.id },
                        onDelete = { scope.launch { itemRepository.delete(item.id) } })
                }
            }
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
