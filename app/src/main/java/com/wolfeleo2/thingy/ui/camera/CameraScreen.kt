package com.wolfeleo2.thingy.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import java.io.File
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.NoSubjectException
import kotlinx.coroutines.launch

private enum class Mode { PHOTO, STICKER }

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(ingestor: ImageIngestor, spaceId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    var mode by remember { mutableStateOf(Mode.PHOTO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Gallery shortcut — a picked image runs the SAME pipeline (sticker cutout if in STICKER mode).
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { ingestor.ingestUri(uri, asSticker = mode == Mode.STICKER, spaceId = spaceId) }
                .onSuccess { onDone() }
                .onFailure { error = if (it is NoSubjectException) "No subject found" else "Couldn't add image"; busy = false }
        }
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    LaunchedEffect(granted) { if (granted) controller.bindToLifecycle(lifecycleOwner) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount < -30) mode = Mode.STICKER else if (dragAmount > 30) mode = Mode.PHOTO
                    }
                },
            )
        } else {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera permission needed", color = Color.White)
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }, shapes = com.wolfeleo2.thingy.ui.expressiveButtonShapes()) { Text("Allow camera") }
                }
            }
        }

        // top bar
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            IconButton(onClick = {
                controller.cameraSelector =
                    if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
            }) { Icon(Icons.Filled.Cameraswitch, "Flip", tint = Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // PHOTO / STICKER labels
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ModeLabel("PHOTO", mode == Mode.PHOTO) { mode = Mode.PHOTO }
                ModeLabel("STICKER", mode == Mode.STICKER) { mode = Mode.STICKER }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pickLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.PhotoLibrary, "Library", tint = Color.White)
                }
                // shutter
                Box(
                    Modifier.size(72.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
                        .background(if (busy) Color.Gray else Color.White, CircleShape)
                        .clickable(enabled = !busy && granted) {
                            busy = true
                            capture(controller, context) { uri ->
                                scope.launch {
                                    runCatching { ingestor.ingestUri(uri, asSticker = mode == Mode.STICKER, spaceId = spaceId) }
                                        .onSuccess { onDone() }
                                        .onFailure { error = if (it is NoSubjectException) "No subject found" else "Couldn't save"; busy = false }
                                }
                            }
                        },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.padding(4.dp).clickable(onClick = onClick),
    )
}

private fun capture(controller: LifecycleCameraController, context: Context, onSaved: (Uri) -> Unit) {
    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
    
    val locManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || 
                 ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    
    var loc: Location? = null
    if (hasLoc) {
        loc = runCatching { locManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
            ?: runCatching { locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?: runCatching { locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
    }
    
    val metadata = ImageCapture.Metadata()
    if (loc != null) metadata.location = loc

    controller.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                onSaved(results.savedUri ?: Uri.fromFile(file))
            }
            override fun onError(exception: ImageCaptureException) { /* surfaced as no-save */ }
        },
    )
}
