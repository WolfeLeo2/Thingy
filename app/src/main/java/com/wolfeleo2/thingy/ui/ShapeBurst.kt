package com.wolfeleo2.thingy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

private data class ParticleState(
    val id: Int,
    val x: Animatable<Float, *>, // dp
    val y: Animatable<Float, *>, // dp
    val rotation: Animatable<Float, *>,
    val alpha: Animatable<Float, *>,
    val scale: Animatable<Float, *>,
    val shape: RoundedPolygon,
    val icon: ImageVector,
    val color: Color,
    val size: Float, // dp
    val spin: Float, // deg/sec, independent of trajectory
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeBurstEffect(trigger: Int) {
    val particles = remember { mutableStateListOf<ParticleState>() }
    val haptics = LocalHapticFeedback.current

    val shapes = listOf(
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Sunny,
        MaterialShapes.VerySunny,
        MaterialShapes.Clover4Leaf,
    )
    val icons = listOf(Icons.Filled.Check, Icons.Filled.AutoAwesome, Icons.Filled.Favorite)
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primaryContainer,
    )
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect

        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

        particles.clear()

        repeat(18) { i ->
            val angle = Random.nextFloat() * 120f - 150f // upward arc, -150..-30 degrees
            val angleRad = Math.toRadians(angle.toDouble())
            val speed = Random.nextFloat() * 160f + 120f // dp/sec
            val originX = (Random.nextFloat() - 0.5f) * 40f // dp — cannon-mouth spread at spawn
            val spawnDelay = Random.nextLong(0, 120) // ms — desyncs the burst into a spray

            val p = ParticleState(
                id = i,
                x = Animatable(originX),
                y = Animatable(0f),
                rotation = Animatable(0f),
                alpha = Animatable(1f),
                scale = Animatable(0.2f),
                shape = shapes.random(),
                icon = icons.random(),
                color = colors.random(),
                size = Random.nextFloat() * 16f + 20f, // 20-36dp
                spin = (Random.nextFloat() - 0.5f) * 900f, // deg/sec
            )
            particles.add(p)

            launch {
                delay(spawnDelay.milliseconds)
                launch { p.scale.animateTo(1f, tween(200)) }

                val durationMs = 1200 + Random.nextInt(400)
                val gravity = 260f // dp/sec^2

                val velX = (cos(angleRad) * speed).toFloat()
                var velY = (sin(angleRad) * speed).toFloat()
                var curX = originX
                var curY = 0f

                var lastFrameNanos = -1L
                var elapsedMs = 0L

                while (elapsedMs < durationMs) {
                    var newRotation = p.rotation.value
                    withFrameNanos { frameNanos ->
                        if (lastFrameNanos < 0) {
                            lastFrameNanos = frameNanos
                            return@withFrameNanos
                        }
                        val dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
                        lastFrameNanos = frameNanos
                        elapsedMs += (dt * 1000).toLong()

                        velY += gravity * dt
                        curX += velX * dt
                        curY += velY * dt
                        newRotation += p.spin * dt
                    }
                    p.rotation.snapTo(newRotation)
                    p.x.snapTo(curX)
                    p.y.snapTo(curY)

                    if (elapsedMs > durationMs * 0.7f) {
                        val fadeProgress = (elapsedMs - durationMs * 0.7f) / (durationMs * 0.3f)
                        p.alpha.snapTo((1f - fadeProgress).coerceIn(0f, 1f))
                    }
                }
                particles.remove(p)
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        particles.forEach { p ->
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = p.x.value.dp.toPx()
                        translationY = p.y.value.dp.toPx()
                        rotationZ = p.rotation.value
                        scaleX = p.scale.value
                        scaleY = p.scale.value
                        alpha = p.alpha.value
                    }
                    .size(p.size.dp)
                    .clip(rememberMaterialShape(p.shape))
                    .background(p.color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    p.icon,
                    contentDescription = null,
                    modifier = Modifier.size(p.size.dp * 0.55f),
                    tint = if (p.color == primaryContainer) onPrimaryContainer else Color.White,
                )
            }
        }
    }
}