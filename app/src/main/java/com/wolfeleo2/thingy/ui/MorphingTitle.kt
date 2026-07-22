package com.wolfeleo2.thingy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Full Compose port of Amber's Skia AnimatedText: a character-diffing morph. Each glyph is keyed
// by char + occurrence, so on a text swap shared glyphs GLIDE to their new x, removed glyphs fly
// out (up+right, shrink, blur, fade) and added glyphs rise in (grow, sharpen, fade) — staggered.

private const val STAGGER = 25L
private const val ENTER_DELAY = 120L
private const val ENTER_RISE = 14f   // dp
private const val EXIT_UP = 12f
private const val EXIT_RIGHT = 8f
private const val SHRINK = 0.7f
private const val BLUR_MAX = 6f      // dp

private class Glyph(index: Int, x: Float) {
    var index by mutableIntStateOf(index)
    var x by mutableFloatStateOf(x)          // target center-x in px, relative to string center
    var present by mutableStateOf(true)
}

@Composable
fun MorphingTitle(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val cells = remember { mutableStateMapOf<String, Glyph>() }
    val chars = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(text, style) {
        val counts = HashMap<Char, Int>()
        val widths = text.map { measurer.measure(AnnotatedString(it.toString()), style).size.width.toFloat() }
        val total = widths.sum()
        var cursor = -total / 2f
        val present = HashSet<String>()
        text.forEachIndexed { i, ch ->
            val n = counts.getOrDefault(ch, 0); counts[ch] = n + 1
            val keyStr = "$ch#$n"; present.add(keyStr)
            val cx = cursor + widths[i] / 2f
            cursor += widths[i]
            val g = cells[keyStr]
            if (g == null) { cells[keyStr] = Glyph(i, cx); chars[keyStr] = ch.toString() }
            else { g.index = i; g.x = cx; g.present = true }
        }
        cells.forEach { (k, g) -> if (k !in present) g.present = false }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        cells.keys.toList().forEach { k ->
            key(k) {
                val g = cells[k]
                if (g != null) {
                    GlyphChar(chars[k] ?: "", g, style, color) { cells.remove(k); chars.remove(k) }
                }
            }
        }
    }
}

@Composable
private fun GlyphChar(char: String, glyph: Glyph, style: TextStyle, color: Color, onExited: () -> Unit) {
    val gx = remember { Animatable(glyph.x) }
    val ty = remember { Animatable(ENTER_RISE) }
    val sc = remember { Animatable(SHRINK) }
    val op = remember { Animatable(0f) }
    val bl = remember { Animatable(BLUR_MAX) }

    LaunchedEffect(Unit) {
        delay(ENTER_DELAY + glyph.index * STAGGER)
        coroutineScope {
            launch { ty.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
            launch { sc.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
            launch { op.animateTo(1f, tween(260)) }
            launch { bl.animateTo(0f, tween(260)) }
        }
    }
    LaunchedEffect(glyph.x) {
        if (gx.value != glyph.x) { delay(140); gx.animateTo(glyph.x, tween(320)) }
    }
    val tx = remember { Animatable(0f) }
    LaunchedEffect(glyph.present) {
        if (!glyph.present) {
            delay(glyph.index * STAGGER)
            coroutineScope {
                launch { ty.animateTo(-EXIT_UP, tween(240)) }
                launch { tx.animateTo(EXIT_RIGHT, tween(240)) }
                launch { sc.animateTo(SHRINK, tween(240)) }
                launch { op.animateTo(0f, tween(240)) }
                launch { bl.animateTo(BLUR_MAX, tween(240)) }
            }
            onExited()
        }
    }

    val blurMod = if (bl.value > 0.1f) Modifier.blur(bl.value.dp) else Modifier
    Text(
        text = char,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = blurMod.graphicsLayer {
            translationX = gx.value + tx.value * density
            translationY = (ty.value.dp).toPx()
            alpha = op.value
            scaleX = sc.value
            scaleY = sc.value
        },
    )
}
