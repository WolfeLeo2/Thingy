package com.wolfeleo2.thingy.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.graphics.shapes.Morph
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wolfeleo2.thingy.data.AuthRepository
import androidx.compose.material3.toPath
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    auth: AuthRepository,
    serverClientId: String?,
    viewModel: LoginViewModel = viewModel { LoginViewModel(auth) }
) {
    val context = LocalContext.current
    val busy = viewModel.busy
    val error = viewModel.error

    var stage by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(3) { stage = it + 1; delay(90.milliseconds) }
    }

    // Nudge, don't scold: one quick horizontal settle when a new error arrives.
    val shake = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(error) {
        if (error != null) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(60))
            shake.animateTo(-1f, tween(90))
            shake.animateTo(0.5f, tween(90))
            shake.animateTo(0f, tween(80))
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // One calm, breathing shape behind the wordmark — the resting state
            // that everything you throw in eventually settles into.
            BreathingBlob()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Thingy",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
                )
                Text(
                    "Save it for later.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (serverClientId == null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Google sign-in isn't configured yet. Enable the Google provider in the " +
                                "Firebase console and re-download google-services.json.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = stage >= 2,
                    enter = fadeIn(tween(280)) + slideInVertically(tween(340)) { it / 3 },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { viewModel.signInWithGoogle(context, serverClientId) },
                            enabled = !busy,
                            shapes = ButtonShapes(ButtonDefaults.shape, ButtonDefaults.squareShape),
                            modifier = Modifier
                                .fillMaxWidth()
                                .size(56.dp)
                                .graphicsLayer { translationX = shake.value * 10f },
                        ) {
                            if (busy) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp)
                                )
                            }
                            Text(
                                "Continue with Google",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        AnimatedVisibility(
                            visible = error != null,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(150)),
                        ) {
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BreathingBlob() {
    val morph = remember {
        Morph(MaterialShapes.Sunny, MaterialShapes.VerySunny)
    }
    val progress = remember { Animatable(0f) }
    val burstSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val returnSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            progress.animateTo(1f, burstSpec)
            delay(2400.milliseconds)
            progress.animateTo(0f, returnSpec)
            delay(1200.milliseconds)
        }
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer { /* keep shape crisp under animation */ }
            .clip(MorphShape(morph, progress.value))
            .background(MaterialTheme.colorScheme.primaryContainer),
    )
}

@ExperimentalMaterial3ExpressiveApi
private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress = progress)
        val matrix = Matrix().apply {
            scale(size.width, size.height)
        }
        path.transform(matrix)
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }
}
