package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.ui.theme.ThingyTheme
import androidx.compose.material3.Surface
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.ui.previewUrl
import com.wolfeleo2.thingy.data.ItemType
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewSpaceScreen(
    spaceId: String?,
    spaceRepository: SpaceRepository,
    itemRepository: ItemRepository,
    classifier: Classifier,
    onDone: () -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val editing = spaceId != null
    val existing by remember(spaceId) {
        if (spaceId != null) spaceRepository.space(spaceId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)

    val allItems by itemRepository.items().collectAsStateWithLifecycle(emptyList())
    val recentItems = remember(allItems) {
        allItems.filter {
            it.type == ItemType.IMAGE.wire || it.type == ItemType.VIDEO.wire || it.type == ItemType.LINK.wire
        }.take(20)
    }

    var name by remember { mutableStateOf("") }
    var dynamic by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(!editing) }
    val selectedItems = remember { mutableStateListOf<String>() }

    LaunchedEffect(existing) {
        if (editing && existing != null && !loaded) {
            name = existing!!.name
            dynamic = existing!!.dynamic == true
            loaded = true
        }
    }

    NewSpaceContent(
        editing = editing,
        name = name,
        onNameChange = { name = it },
        dynamic = dynamic,
        onDynamicChange = { dynamic = it },
        recentItems = recentItems,
        selectedItems = selectedItems,
        onSelectItem = { id ->
            if (selectedItems.contains(id)) selectedItems.remove(id) else selectedItems.add(id)
        },
        onDoneClick = {
            val n = name.trim()
            if (n.isEmpty()) return@NewSpaceContent
            scope.launch {
                if (editing) {
                    spaceRepository.updateSpace(spaceId!!, n, dynamic)
                    if (dynamic && existing?.dynamic != true) classifier.recommendForSpace(spaceId)
                } else {
                    val id = spaceRepository.createSpace(n, dynamic)
                    selectedItems.forEach { itemId -> spaceRepository.addItemToSpace(itemId, id) }
                    if (dynamic) classifier.recommendForSpace(id)
                }
            }
            onDone()
        },
        onBack = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewSpaceContent(
    editing: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    dynamic: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    recentItems: List<com.wolfeleo2.thingy.data.Item>,
    selectedItems: List<String>,
    onSelectItem: (String) -> Unit,
    onDoneClick: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!editing) runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Edit space" else "New space") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                shape = OutlinedTextFieldDefaults.roundedShape,
                value = name, onValueChange = onNameChange, singleLine = true,
                label = { Text("Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .focusRequester(focus),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Dynamic", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Thingy keeps suggesting things that fit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = dynamic, onCheckedChange = onDynamicChange)
            }
            if (!editing && recentItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Start with some thingies...",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    val carouselState = rememberCarouselState { recentItems.size }
                    HorizontalMultiBrowseCarousel(
                        state = carouselState,
                        preferredItemWidth = 140.dp,
                        itemSpacing = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) { i ->
                        val item = recentItems[i]
                        val isSelected = selectedItems.contains(item.id)
                        val url = item.previewUrl()

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .clickable { onSelectItem(item.id) }
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (item.type == ItemType.VIDEO.wire) {
                                Box(
                                    Modifier.matchParentSize().padding(12.dp),
                                    contentAlignment = Alignment.TopEnd
                                ) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.padding(4.dp).size(16.dp)
                                        )
                                    }
                                }
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
            Button(
                shapes = expressiveButtonShapes(),
                onClick = onDoneClick,
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) { Text(if (editing) "Save changes" else "Create space") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NewSpaceScreenPreview() {
    val items = listOf(
        com.wolfeleo2.thingy.data.Item(id = "1", type = ItemType.IMAGE.wire, title = "Image 1"),
        com.wolfeleo2.thingy.data.Item(id = "2", type = ItemType.IMAGE.wire, title = "Image 2"),
        com.wolfeleo2.thingy.data.Item(id = "3", type = ItemType.IMAGE.wire, title = "Image 3"),
    )
    ThingyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NewSpaceContent(
                editing = false,
                name = "My New Space",
                onNameChange = {},
                dynamic = true,
                onDynamicChange = {},
                recentItems = items,
                selectedItems = listOf("1","2"),
                onSelectItem = {},
                onDoneClick = {},
                onBack = {},
            )
        }
    }
}
