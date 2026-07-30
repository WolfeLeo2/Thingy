package com.wolfeleo2.thingy.ui.add

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wolfeleo2.thingy.data.AudioIngestor
import com.wolfeleo2.thingy.data.formatDuration
import kotlinx.coroutines.delay

private enum class Mode { MENU, NOTE, ARTICLE, VOICE }

private fun clipboardUrl(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
    return if (Patterns.WEB_URL.matcher(text).matches()) text else ""
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddSheet(
    onSaveNote: (String) -> Unit,
    onSaveArticle: (String) -> Unit,
    onPhotosPicked: (List<Uri>) -> Unit,
    onOpenCamera: () -> Unit,
    audioIngestor: AudioIngestor,
    /** Invoked on stop; the caller ingests in a scope that outlives this sheet. */
    onVoiceRecorded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(Mode.MENU) }

    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> if (uris.isNotEmpty()) { onPhotosPicked(uris); onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(targetState = mode, label = "add-title") { m ->
                Text(
                    when (m) {
                        Mode.MENU -> "Save something"
                        Mode.NOTE -> "New note"
                        Mode.ARTICLE -> "Save an link"
                        Mode.VOICE -> "Voice note"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            when (mode) {
                Mode.MENU -> {
                    val actions = remember {
                        listOf(
                            Triple(Icons.AutoMirrored.Filled.Note, "Note") { mode = Mode.NOTE },
                            Triple(Icons.Filled.Link, "Link") { mode = Mode.ARTICLE },
                            Triple(Icons.Filled.PhotoLibrary, "Media") {
                                photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                            Triple(Icons.Filled.CameraAlt, "Camera") { onOpenCamera(); onDismiss() },
                            Triple(Icons.Filled.Mic, "Voice") { mode = Mode.VOICE }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val outerRadius = 24.dp
                        val innerRadius = 4.dp

                        actions.forEachIndexed { index, (icon, label, onClick) ->
                            val shape = when (index) {
                                0 -> RoundedCornerShape(
                                    topStart = outerRadius, topEnd = outerRadius,
                                    bottomStart = innerRadius, bottomEnd = innerRadius
                                )
                                actions.lastIndex -> RoundedCornerShape(
                                    topStart = innerRadius, topEnd = innerRadius,
                                    bottomStart = outerRadius, bottomEnd = outerRadius
                                )
                                else -> RoundedCornerShape(innerRadius)
                            }

                            SegmentedListItem(
                                onClick = onClick,
                                shapes = ListItemDefaults.shapes(shape = shape),
                                colors = ListItemDefaults.segmentedColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                leadingContent = { Icon(icon, contentDescription = null) }
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Mode.NOTE -> Composer("", "Write a note…", singleLine = false, onBack = { mode = Mode.MENU }, onSave = onSaveNote)
                Mode.ARTICLE -> Composer(clipboardUrl(context), "https://…", singleLine = true,
                    keyboard = KeyboardType.Uri, onBack = { mode = Mode.MENU }, onSave = onSaveArticle)
                Mode.VOICE -> Recorder(
                    ingestor = audioIngestor,
                    onBack = { mode = Mode.MENU },
                    onSave = { onVoiceRecorded(); onDismiss() },
                )
            }
        }
    }
}

/**
 * Tap to start, tap to stop — not hold-to-record. A press-and-hold gesture fights the bottom
 * sheet's own drag handling, and a voice note here can run for minutes, which is a long time to
 * keep a finger down.
 *
 * The recording itself lives in [AudioIngestor], not in composition state, so a recomposition
 * can't disturb it. Stopping hands off to the caller's scope: ingesting from a scope owned by
 * this sheet would be cancelled the instant the sheet dismisses.
 */
@Composable
private fun Recorder(ingestor: AudioIngestor, onBack: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    // Rolling window of recent mic levels — the newest is appended, the oldest drops off.
    val levels = remember { mutableStateListOf<Float>() }
    // Set when the recording has been handed to the caller, so the dispose below doesn't delete
    // the file that's already being ingested.
    var handedOff by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    // Abandoning the sheet mid-recording discards it. Saving requires the stop button — a swipe-away
    // shouldn't quietly commit something the user was in the middle of.
    DisposableEffect(Unit) {
        onDispose { if (!handedOff) ingestor.cancel() }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - startedAt
            levels.add(ingestor.currentLevel())
            if (levels.size > WAVEFORM_BARS) levels.removeAt(0)
            delay(POLL_INTERVAL_MS)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (recording) formatDuration(elapsedMs) else "Tap the mic and start talking",
            style = if (recording) MaterialTheme.typography.displaySmall else MaterialTheme.typography.bodyMedium,
            color = if (recording) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Waveform(levels)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { ingestor.cancel(); onBack() }) { Text("Back") }
            Button(
                shapes = ButtonShapes(ButtonDefaults.shape, ButtonDefaults.squareShape),
                onClick = {
                    when {
                        !granted -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        !recording -> {
                            // Mic busy, or permission revoked between the check and here.
                            if (runCatching { ingestor.start() }.isSuccess) {
                                levels.clear()
                                elapsedMs = 0L
                                recording = true
                            }
                        }
                        else -> {
                            recording = false
                            handedOff = true
                            onSave()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                Text(if (recording) "  Stop and save" else "  Record")
            }
        }
    }
}

/** Recent mic levels as bars. Purely decorative — it exists so silence looks different from speech. */
@Composable
private fun Waveform(levels: List<Float>) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(WAVEFORM_BARS) { i ->
            // Right-aligned: the newest sample is the rightmost bar, so it grows leftward.
            val level = levels.getOrNull(i - (WAVEFORM_BARS - levels.size)) ?: 0f
            val height by animateFloatAsState(level, label = "bar")
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight(0.08f + 0.92f * height)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val WAVEFORM_BARS = 32
private const val POLL_INTERVAL_MS = 60L

@Composable
private fun Composer(
    initial: String, placeholder: String, singleLine: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, onBack: () -> Unit, onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        shape = OutlinedTextFieldDefaults.roundedShape,
        value = text, onValueChange = { text = it }, placeholder = { Text(placeholder) },
        singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Button(
            shapes = ButtonShapes(ButtonDefaults.shape, ButtonDefaults.squareShape),
            onClick = { if (text.isNotBlank()) onSave(text.trim()) }, enabled = text.isNotBlank(),
            modifier = Modifier.weight(1f)) { Text("Save")
        }
    }
}
