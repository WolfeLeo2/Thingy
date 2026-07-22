package com.wolfeleo2.thingy.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private val photosPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var cameraOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var photosOk by remember { mutableStateOf(granted(photosPermission)) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { cameraOk = it }
    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { photosOk = it }

    var locationOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { map ->
        locationOk = map[Manifest.permission.ACCESS_FINE_LOCATION] == true || map[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Below API 33 notifications need no runtime grant — treat as already on.
    var notificationsOk by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
        )
    }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOk = it }

    // Staggered reveal — one orchestrated entrance rather than per-element effects.
    var stage by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(6) { stage = it + 1; delay(70.milliseconds) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Staggered(visible = stage >= 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Thingy",
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
                    )
                    Text(
                        "Save it for later.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Staggered(visible = stage >= 2) {
                    Feature(
                        MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided, Icons.Filled.Link,
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                        "Links, notes & photos", "Throw anything in - Thingy reads it for you.",
                    )
                }
                Staggered(visible = stage >= 3) {
                    Feature(
                        MaterialShapes.Clover4Leaf, MaterialShapes.Clover8Leaf, Icons.AutoMirrored.Filled.Note,
                        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                        "Titles & tags, automatically", "A calm feed that organizes itself.",
                    )
                }
                Staggered(visible = stage >= 4) {
                    Feature(
                        MaterialShapes.Sunny, MaterialShapes.VerySunny, Icons.Filled.PhotoLibrary,
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        "Spaces & Tidy", "Themed shelves, plus a swipe to declutter.",
                    )
                }
            }

            Staggered(visible = stage >= 5) {
                PermissionGroup(
                    items = listOf(
                        Triple(Icons.Filled.CameraAlt, "Camera", cameraOk),
                        Triple(Icons.Filled.PhotoLibrary, "Photos", photosOk),
                        Triple(Icons.Filled.Place, "Location", locationOk),
                        Triple(Icons.Filled.Notifications, "Notifications", notificationsOk),
                    ),
                    onRequest = { index ->
                        when (index) {
                            0 -> cameraLauncher.launch(Manifest.permission.CAMERA)
                            1 -> photosLauncher.launch(photosPermission)
                            2 -> {
                                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                                locationLauncher.launch(perms.toTypedArray())
                            }
                            3 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                )
            }

            Staggered(visible = stage >= 6) {
                Button(
                    shapes = ButtonShapes(ButtonDefaults.shape, ButtonDefaults.squareShape),
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().size(56.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("Start saving", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun Staggered(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 4 },
    ) { content() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Feature(
    start: RoundedPolygon,
    end: RoundedPolygon,
    icon: ImageVector,
    containerColor: Color,
    onContainerColor: Color,
    title: String,
    subtitle: String,
) {
    val morph = remember(start, end) { Morph(start, end) }
    val progress = remember { Animatable(0f) }
    val burstSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val returnSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    
    LaunchedEffect(Unit) {
        // Slight desync to make the screen feel more organic
        delay((0..300).random().toLong().milliseconds)
        while (true) {
            progress.animateTo(1f, burstSpec)
            delay(1400.milliseconds)
            progress.animateTo(0f, returnSpec)
            delay(1400.milliseconds)
        }
    }

    val shape = remember(morph, progress.value) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val path = morph.toPath(progress = progress.value)
                val matrix = Matrix().apply { scale(size.width, size.height) }
                path.transform(matrix)
                path.translate(size.center - path.getBounds().center)
                return Outline.Generic(path)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = onContainerColor)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionGroup(
    items: List<Triple<ImageVector, String, Boolean>>,
    onRequest: (Int) -> Unit,
) {
    val outerRadius = 20.dp
    val innerRadius = 4.dp

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, (icon, label, granted) ->
            val shape = when (index) {
                0 -> RoundedCornerShape(
                    topStart = outerRadius, topEnd = outerRadius,
                    bottomStart = innerRadius, bottomEnd = innerRadius,
                )
                items.lastIndex -> RoundedCornerShape(
                    topStart = innerRadius, topEnd = innerRadius,
                    bottomStart = outerRadius, bottomEnd = outerRadius,
                )
                else -> RoundedCornerShape(innerRadius)
            }

            SegmentedListItem(
                onClick = { onRequest(index) },
                enabled = !granted,
                shapes = ListItemDefaults.shapes(shape = shape),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                leadingContent = {
                    Icon(
                        if (granted) Icons.Filled.Check else icon,
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    if (granted) "$label — Ready" else "Allow $label",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}