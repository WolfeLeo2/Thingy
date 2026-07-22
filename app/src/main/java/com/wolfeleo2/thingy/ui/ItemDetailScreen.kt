package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.data.Intent
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.ItemStatus
import com.wolfeleo2.thingy.data.ItemType
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.displayTitle
import com.wolfeleo2.thingy.lib.formatItemDate
import com.wolfeleo2.thingy.lib.runIntent
import com.wolfeleo2.thingy.reminders.ReminderManager
import com.wolfeleo2.thingy.ui.reminders.SnoozeSheet
import com.wolfeleo2.thingy.ui.reminders.formatSnoozeTime
import com.wolfeleo2.thingy.ui.theme.ThingyTheme
import kotlinx.coroutines.launch
import java.net.URI

private fun Item.headerDate(): Long? =
    if (type == ItemType.IMAGE.wire && capturedAt != null) capturedAt else createdAt?.time


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ItemDetailScreen(
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    classifier: com.wolfeleo2.thingy.data.Classifier,
    settings: SettingsRepository,
    itemIds: List<String>,
    startIndex: Int,
    spaceId: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (itemIds.size - 1).coerceAtLeast(0)),
    ) { itemIds.size }

    val scope = rememberCoroutineScope()
    var showSnooze by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSpaces by remember { mutableStateOf(false) }
    val activeId = itemIds.getOrNull(pagerState.currentPage)
    val activeItem by remember(activeId) {
        if (activeId != null) itemRepository.item(activeId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val snoozedItems by settings.snoozedItems.collectAsStateWithLifecycle(emptyMap())
    val snoozedAt = activeId?.let { snoozedItems[it] }
    val detailContext = LocalContext.current

    // Per-item ambient color schemes, generated from each hero image via Hct/dynamicColorScheme.
    val paletteSchemes = remember { mutableStateMapOf<String, ColorScheme>() }
    val baseScheme = MaterialTheme.colorScheme
    val targetScheme = activeId?.let { paletteSchemes[it] } ?: baseScheme

    // ColorScheme has no built-in lerp — animate just the roles this screen uses,
    // then rebuild a scheme from the base + those animated values.
    val colorSpec = tween<Color>(500)
    val surface by animateColorAsState(targetScheme.surface, colorSpec, label = "surface")
    val onSurface by animateColorAsState(targetScheme.onSurface, colorSpec, label = "onSurface")
    val surfaceVariant by animateColorAsState(targetScheme.surfaceVariant, colorSpec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(targetScheme.onSurfaceVariant, colorSpec, label = "onSurfaceVariant")
    val primary by animateColorAsState(targetScheme.primary, colorSpec, label = "primary")
    val primaryContainer by animateColorAsState(targetScheme.primaryContainer, colorSpec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(targetScheme.onPrimaryContainer, colorSpec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(targetScheme.secondary, colorSpec, label = "secondary")
    val onSecondary by animateColorAsState(targetScheme.onSecondary, colorSpec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(targetScheme.secondaryContainer, colorSpec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(targetScheme.onSecondaryContainer, colorSpec, label = "onSecondaryContainer")
    val tertiary by animateColorAsState(targetScheme.tertiary, colorSpec, label = "tertiary")
    val onTertiary by animateColorAsState(targetScheme.onTertiary, colorSpec, label = "onTertiary")
    val tertiaryContainer by animateColorAsState(targetScheme.tertiaryContainer, colorSpec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(targetScheme.onTertiaryContainer, colorSpec, label = "onTertiaryContainer")
    val outline by animateColorAsState(targetScheme.outline, colorSpec, label = "outline")
    val outlineVariant by animateColorAsState(targetScheme.outlineVariant, colorSpec, label = "outlineVariant")
    val surfaceContainer by animateColorAsState(targetScheme.surfaceContainer, colorSpec, label = "surfaceContainer")
    val surfaceContainerLow by animateColorAsState(targetScheme.surfaceContainerLow, colorSpec, label = "surfaceContainerLow")
    val surfaceContainerHigh by animateColorAsState(targetScheme.surfaceContainerHigh, colorSpec, label = "surfaceContainerHigh")

    MaterialTheme(
        colorScheme = baseScheme.copy(
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerHigh = surfaceContainerHigh,
            // error / errorContainer intentionally left at the base theme's fixed values.
        )
    ) {
        Scaffold(
            containerColor = surface,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Unspecified,
                        navigationIconContentColor = onSurface,
                        titleContentColor = onSurface,
                        actionIconContentColor = onSurface
                    ),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        val item = activeItem
                        if (item != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MorphingTitle(
                                    text = item.displayTitle(),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                item.headerDate()?.let {
                                    Text(formatItemDate(it), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (snoozedAt != null) "Change reminder" else "Remind me later") },
                                    onClick = { menu = false; showSnooze = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to space") },
                                    onClick = { menu = false; showSpaces = true },
                                )
                                DropdownMenuItem(
                                    // Errors/destructive actions stay on the app's fixed default red.
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (snoozedAt != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            "Reminder set for ${formatSnoozeTime(snoozedAt)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            val id = activeId ?: return@IconButton
                            ReminderManager.cancelSnooze(detailContext, id)
                            scope.launch { settings.unsnoozeItem(id) }
                        }) {
                            Icon(Icons.Filled.Close, "Cancel reminder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    itemIds.getOrNull(page)?.let {
                        val isActive = page == pagerState.currentPage
                        DetailPage(itemRepository, spaceRepository, it, spaceId, sharedTransitionScope, animatedVisibilityScope, isActive) { scheme ->
                            paletteSchemes[it] = scheme
                        }
                    }
                }
            }
        }
    }

    activeItem?.let { currentItem ->
        if (showSnooze) {
            SnoozeSheet(
                item = currentItem,
                settings = settings,
                onDismiss = { showSnooze = false }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this thingy?") },
            text = { Text("It's removed from Home and every space. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val target = activeId
                    if (target != null) scope.launch { itemRepository.delete(target); onBack() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showSpaces) {
        activeId?.let { id ->
            ManageSpacesDialog(id, spaceRepository, classifier, onDismiss = { showSpaces = false })
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DetailPage(
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    id: String,
    spaceId: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isActive: Boolean,
    onColorExtracted: ((ColorScheme) -> Unit)? = null,
) {
    val item by remember(id) { itemRepository.item(id) }.collectAsStateWithLifecycle(null)
    val i = item ?: return

    val steered by produceState(emptyList<Intent>(), id, spaceId) {
        value = if (spaceId != null) spaceRepository.membershipIntents(id, spaceId) else emptyList()
    }

    DetailPageContent(
        item = i,
        steeredIntents = steered,
        isActive = isActive,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        onColorExtracted = onColorExtracted,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailPageContent(
    item: Item,
    steeredIntents: List<Intent>,
    isActive: Boolean,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onColorExtracted: ((ColorScheme) -> Unit)? = null,
) {
    val id = item.id
    val context = LocalContext.current
    val motionSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val heroUrl: Any? = item.previewUrl()

    val imageShared = if (heroUrl != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "item-image-$id"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = BoundsTransform { _, _ -> motionSpec },
            )
        }
    } else Modifier
    val containerShared = if (heroUrl == null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "item-card-$id"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = BoundsTransform { _, _ -> motionSpec },
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)),
            )
        }
    } else Modifier

    Column(
        Modifier
            .fillMaxSize()
            .then(containerShared)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (heroUrl != null) Hero(item, heroUrl, imageShared, isActive, onColorExtracted)

        if (ItemStatus.from(item.status) == ItemStatus.PROCESSING) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularWavyProgressIndicator(modifier = Modifier.padding(2.dp).size(22.dp))
                Text("Thingy is reading this…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (item.type == ItemType.LINK.wire && item.url != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { runIntent(context, "open_url", item.url!!) },
            ) {
                Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(1.dp))
                Text(item.siteName ?: runCatching { URI(item.url!!).host?.removePrefix("www.") }.getOrNull() ?: item.url!!,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.NorthEast, null, tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 1.dp))
            }
        }

        val allIntents = run {
            val base = item.intents.orEmpty()
            val seen = base.map { "${it.kind}|${it.value.lowercase()}" }.toMutableSet()
            base + steeredIntents.filter { seen.add("${it.kind}|${it.value.lowercase()}") }
        }
        if (allIntents.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allIntents.forEach { intent ->
                    ElevatedAssistChip(onClick = { runIntent(context, intent.kind, intent.value) }, label = { Text(intent.label) })
                }
            }
        }

        item.description?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }

        if (item.type == ItemType.NOTE.wire) item.note?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }

        item.tags.takeIf { it.isNotEmpty() }?.let { tags ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { SuggestionChip(onClick = { }, label = { Text(it) }) }
            }
        }

        val paragraphs = item.content?.split(Regex("\\n{2,}"))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (paragraphs.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            SelectionContainer {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    paragraphs.forEach { p ->
                        Text(p, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    item: Item,
    url: Any,
    imageShared: Modifier = Modifier,
    isActive: Boolean = true,
    onColorExtracted: ((ColorScheme) -> Unit)? = null,
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    LaunchedEffect(url, isDark) {
        // Separate, tiny, software-decoded request — only for Palette.
        // Cheap because it's small; doesn't touch the hardware-accelerated requests below.
        val seed = extractPaletteSeed(context, url)
        if (seed != null) onColorExtracted?.invoke(seedColorScheme(seed, isDark))
    }

    val ratio = (item.aspectRatio?.toFloat() ?: if (item.type == ItemType.LINK.wire) 1.91f else 1f)
        .coerceIn(0.5f, 2.0f)
    if (item.sticker == true) {
        AsyncImage(model = item.heroImageRequest(url), contentDescription = item.title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio).then(imageShared))
        return
    }

    Surface(color = Color.White, shadowElevation = 3.dp, shape = RoundedCornerShape(16.dp),
        modifier = imageShared) {
        val innerModifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).aspectRatio(ratio)
        if (item.type == ItemType.VIDEO.wire) {
            VideoHero(item = item, url = url, isActive = isActive, modifier = innerModifier)
        } else {
            // Visible image: full-quality, hardware-accelerated — untouched by the palette pass above.
            // heroImageRequest reuses the feed's cached bitmap as a placeholder → no fade-from-blank flash.
            AsyncImage(model = item.heroImageRequest(url), contentDescription = item.title, contentScale = ContentScale.Crop,
                modifier = innerModifier)
        }
    }
}

@Composable
private fun VideoHero(item: Item, url: Any, isActive: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(item.id) {
        ExoPlayer.Builder(context).build()
    }

    fun prepareMedia() {
        val uri = if (url is java.io.File) android.net.Uri.fromFile(url) else url.toString().toUri()
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        exoPlayer.prepare()
    }

    LaunchedEffect(item.id) { prepareMedia() }

    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var playbackError by remember(item.id) { mutableStateOf<String?>(null) }

    fun retry() {
        playbackError = null
        prepareMedia()
        exoPlayer.playWhenReady = isActive
    }

    LaunchedEffect(isActive) {
        exoPlayer.playWhenReady = isActive && playbackError == null
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Couldn't play this video"
                isPlaying = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier.clickable(enabled = playbackError == null) {
            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
        },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (playbackError != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clickable { retry() },
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry",
                    tint = Color.White,
                    modifier = Modifier.padding(16.dp).fillMaxSize(),
                )
            }
        } else {
            AnimatedVisibility(
                visible = !isPlaying,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.padding(16.dp).fillMaxSize()
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailPageNotePreview() {
    val item = Item(
        id = "1",
        type = ItemType.NOTE.wire,
        status = ItemStatus.READY.wire,
        note = "This is a sample note with some content to see how it looks in the detail page.",
        tags = listOf("Sample", "Note", "Thingy")
    )
    ThingyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DetailPageContent(item = item, steeredIntents = emptyList(), isActive = true)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailPageLinkPreview() {
    val item = Item(
        id = "2",
        type = ItemType.LINK.wire,
        status = ItemStatus.READY.wire,
        title = "Jetpack Compose",
        description = "Modern toolkit for building native UI.",
        url = "https://developer.android.com/compose",
        siteName = "Android Developers",
        heroImageUrl = "https://developer.android.com/images/social/compose-hero.png",
        tags = listOf("Android", "UI", "Compose"),
        intents = listOf(Intent(kind = "open_url", label = "Open URL", value = "https://developer.android.com/compose"))
    )
    ThingyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DetailPageContent(item = item, steeredIntents = emptyList(), isActive = true)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailPageProcessingPreview() {
    val item = Item(
        id = "3",
        type = ItemType.LINK.wire,
        status = ItemStatus.PROCESSING.wire,
        url = "https://example.com"
    )
    ThingyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DetailPageContent(item = item, steeredIntents = emptyList(), isActive = true)
        }
    }
}