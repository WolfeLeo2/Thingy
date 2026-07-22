package com.wolfeleo2.thingy.ui.add

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private enum class Mode { MENU, NOTE, ARTICLE }

private fun clipboardUrl(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
    return if (Patterns.WEB_URL.matcher(text).matches()) text else ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(
    onSaveNote: (String) -> Unit,
    onSaveArticle: (String) -> Unit,
    onPhotosPicked: (List<Uri>) -> Unit,
    onOpenCamera: () -> Unit,
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
                        Mode.ARTICLE -> "Save an article"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            when (mode) {
                Mode.MENU -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AddAction(Icons.AutoMirrored.Filled.Note, "Note", Modifier.weight(1f)) { mode = Mode.NOTE }
                        AddAction(Icons.Filled.Link, "Article", Modifier.weight(1f)) { mode = Mode.ARTICLE }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AddAction(Icons.Filled.PhotoLibrary, "Photos", Modifier.weight(1f)) {
                            photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        AddAction(Icons.Filled.CameraAlt, "Camera", Modifier.weight(1f)) { onOpenCamera(); onDismiss() }
                    }
                }
                Mode.NOTE -> Composer("", "Write a note…", singleLine = false, onBack = { mode = Mode.MENU }, onSave = onSaveNote)
                Mode.ARTICLE -> Composer(clipboardUrl(context), "https://…", singleLine = true,
                    keyboard = KeyboardType.Uri, onBack = { mode = Mode.MENU }, onSave = onSaveArticle)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shapes = com.wolfeleo2.thingy.ui.expressiveButtonShapes(), modifier = modifier) {
        Icon(icon, contentDescription = null); Text("  $label")
    }
}

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
