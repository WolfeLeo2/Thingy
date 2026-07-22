package com.wolfeleo2.thingy.ui.tidy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.TidyAlbum
import com.wolfeleo2.thingy.data.TidyMedia
import com.wolfeleo2.thingy.data.TidyPhoto
import com.wolfeleo2.thingy.ui.ThingyEmptyState
import com.wolfeleo2.thingy.ui.expressiveButtonShapes
import kotlin.math.abs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val BATCH = 30
private val photosPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private enum class Action { KEEP, DELETE, SAVE }

private fun hasFull(c: Context) = ContextCompat.checkSelfPermission(c, photosPermission) == PackageManager.PERMISSION_GRANTED
private fun hasPartial(c: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
    ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
private fun hasAccess(c: Context) = hasFull(c) || hasPartial(c)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TidyScreen(ingestor: ImageIngestor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAccess(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasAccess(context)
    }

    if (!granted) {
        ThingyEmptyState(
            shape = MaterialShapes.Sunny,
            icon = Icons.Filled.Delete,
            title = "Tidy your camera roll",
            message = "Swipe through your photos one by one. Keep, delete, or save into Thingy.",
            modifier = modifier,
            actionLabel = "Allow photo access",
            onAction = { permLauncher.launch(photosPermission) },
        )
        return
    }

    Deck(ingestor, modifier, limited = hasPartial(context) && !hasFull(context)) {
        permLauncher.launch(photosPermission) // "manage" → re-prompt selection
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Deck(ingestor: ImageIngestor, modifier: Modifier, limited: Boolean, onManageAccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    val albumId by remember { settings.tidyAlbumId.map { it ?: TidyMedia.ALL_PHOTOS } }
        .collectAsStateWithLifecycle(TidyMedia.ALL_PHOTOS)
    var albums by remember { mutableStateOf(listOf(TidyAlbum(TidyMedia.ALL_PHOTOS, "All Photos"))) }
    LaunchedEffect(Unit) { albums = TidyMedia.loadAlbums(context) }

    var offset by remember { mutableIntStateOf(0) }
    var batch by remember { mutableStateOf<List<TidyPhoto>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var topProgress by remember { mutableFloatStateOf(0f) }
    val decisions = remember { mutableStateListOf<Pair<TidyPhoto, Action>>() }
    val pendingDelete = remember { mutableStateListOf<TidyPhoto>() }
    var committing by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pendingDelete.clear()
    }
    fun commitDeletes() {
        val sender = TidyMedia.deleteRequest(context, pendingDelete.map { it.uri }) ?: run { pendingDelete.clear(); return }
        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    // Reset paging when the album changes.
    LaunchedEffect(albumId) { offset = 0 }
    LaunchedEffect(albumId, offset) {
        batch = null
        batch = TidyMedia.load(context, BATCH, offset, albumId)
        index = 0
        topProgress = 0f
        decisions.clear()
    }

    val photos = batch
    if (photos == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularWavyProgressIndicator() }
        return
    }

    fun decide(action: Action) {
        val photo = photos.getOrNull(index) ?: return
        decisions.add(photo to action)
        when (action) {
            Action.DELETE -> pendingDelete.add(photo)
            Action.SAVE -> scope.launch { runCatching { ingestor.ingestUri(photo.uri, asSticker = false) } }
            Action.KEEP -> {}
        }
        topProgress = 0f
        index++
    }
    fun undo() {
        if (decisions.isEmpty()) return
        val (photo, action) = decisions.removeAt(decisions.lastIndex)
        if (action == Action.DELETE) pendingDelete.remove(photo)
        index--
    }

    Column(modifier.fillMaxSize()) {
        val currentAlbum = albums.firstOrNull { it.id == albumId }?.name ?: "All Photos"
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { undo() }, enabled = decisions.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${minOf(index, photos.size)} / ${photos.size}", style = MaterialTheme.typography.labelLarge)
                Text(currentAlbum, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { albumMenu = true }) { Icon(Icons.Filled.PhotoLibrary, "Album") }
                DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                    albums.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.name) },
                            onClick = { albumMenu = false; scope.launch { settings.setTidyAlbum(a.id) } },
                        )
                    }
                }
            }
            BadgedBox(badge = { if (pendingDelete.isNotEmpty()) Badge { Text("${pendingDelete.size}") } }) {
                IconButton(onClick = { commitDeletes() }, enabled = pendingDelete.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, "Delete now")
                }
            }
        }

        if (limited) {
            Surface(
                onClick = onManageAccess,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "Thingy can only see some photos — tap to manage.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                )
            }
        }

        Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            if (index >= photos.size) {
                Done(
                    counts = decisions.groupingBy { it.second }.eachCount(),
                    pending = pendingDelete.size,
                    committing = committing,
                    onContinue = {
                        scope.launch {
                            committing = true
                            if (pendingDelete.isNotEmpty()) commitDeletes()
                            offset += photos.size
                            committing = false
                        }
                    },
                )
            } else {
                // Behind card — scales/reveals as the top card is dragged away.
                photos.getOrNull(index + 1)?.let { next ->
                    PhotoCard(next, Modifier.fillMaxSize().graphicsLayer {
                        val s = 0.9f + 0.1f * topProgress
                        scaleX = s; scaleY = s; alpha = 0.7f + 0.3f * topProgress
                    })
                }
                SwipeCard(photos[index], onProgress = { topProgress = it }, onDecide = ::decide)
            }
        }
    }
}

@Composable
private fun SwipeCard(photo: TidyPhoto, onProgress: (Float) -> Unit, onDecide: (Action) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val w = with(density) { config.screenWidthDp.dp.toPx() }
    val h = with(density) { config.screenHeightDp.dp.toPx() }
    val thX = w / 4f
    val thY = h / 5f

    val ox = remember(photo.id) { Animatable(0f) }
    val oy = remember(photo.id) { Animatable(0f) }
    var crossed by remember(photo.id) { mutableStateOf(false) }

    PhotoCard(
        photo,
        Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch { ox.snapTo(ox.value + drag.x); oy.snapTo(oy.value + drag.y) }
                        val progress = minOf(1f, maxOf(abs(ox.value) / thX, maxOf(0f, -oy.value) / thY))
                        onProgress(progress)
                        if (progress >= 1f && !crossed) { crossed = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (progress < 1f) crossed = false
                    },
                    onDragEnd = {
                        val horizontal = abs(ox.value)
                        val upward = -oy.value
                        val commitUp = upward > thY && upward > horizontal
                        val commitSide = horizontal > thX && horizontal >= upward
                        when {
                            commitUp -> fling(scope, ox, oy, 0f, -h * 1.15f) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(Action.SAVE) }
                            commitSide && ox.value > 0 -> fling(scope, ox, oy, w * 1.25f, 0f) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(Action.KEEP) }
                            commitSide -> fling(scope, ox, oy, -w * 1.25f, 0f) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(Action.DELETE) }
                            else -> { scope.launch { ox.animateTo(0f) }; scope.launch { oy.animateTo(0f) }; onProgress(0f) }
                        }
                    },
                )
            }
            .graphicsLayer {
                translationX = ox.value; translationY = oy.value
                rotationZ = (ox.value / 40f).coerceIn(-12f, 12f)
            },
    )
}

private fun fling(
    scope: kotlinx.coroutines.CoroutineScope,
    ox: Animatable<Float, *>, oy: Animatable<Float, *>,
    toX: Float, toY: Float, onDone: () -> Unit,
) {
    scope.launch {
        val spec = androidx.compose.animation.core.tween<Float>(220)
        kotlinx.coroutines.coroutineScope {
            launch { ox.animateTo(toX, spec) }
            launch { oy.animateTo(toY, spec) }
        }
        onDone()
    }
}

@Composable
private fun PhotoCard(photo: TidyPhoto, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, color = Color.Black) {
        AsyncImage(model = photo.uri, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Done(counts: Map<Action, Int>, pending: Int, committing: Boolean, onContinue: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Batch done", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text("Kept ${counts[Action.KEEP] ?: 0} · Saved ${counts[Action.SAVE] ?: 0} · Deleted ${counts[Action.DELETE] ?: 0}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onContinue, enabled = !committing, shapes = expressiveButtonShapes(), modifier = Modifier.padding(top = 8.dp)) {
            if (committing) CircularWavyProgressIndicator(Modifier.size(18.dp))
            else Text(if (pending > 0) "Delete $pending & continue" else "Continue")
        }
    }
}
