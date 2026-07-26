package com.wolfeleo2.thingy.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.video.videoFramePercent
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Tiny software-decoded fetch + Palette pass — the dominant/vibrant seed color used to seed
 * ambient color schemes app-wide (item hero, shelf boards). Cheap: capped at 120x120.
 *
 * Videos go through the same path (the loader registers VideoFrameDecoder), sampled a third of the
 * way in rather than at frame 0 — plenty of clips open on black, which Palette can only read as a
 * drab dominantSwatch. Ignored by the image decoders, so it costs nothing for photos and stickers.
 */
suspend fun extractPaletteSeed(context: Context, model: Any): Int? = runCatching {
    val request = ImageRequest.Builder(context).data(model).size(Size(120, 120)).allowHardware(false)
        .videoFramePercent(0.33)
        .build()
    val bitmap = context.imageLoader.execute(request).image?.asDrawable(context.resources)?.toBitmap() ?: return@runCatching null
    suspendCancellableCoroutine<Int?> { cont ->
        Palette.from(bitmap).generate { palette ->
            val seed = palette?.vibrantSwatch?.rgb
                ?: palette?.darkVibrantSwatch?.rgb
                ?: palette?.lightVibrantSwatch?.rgb
                ?: palette?.mutedSwatch?.rgb
                ?: palette?.dominantSwatch?.rgb
            cont.resume(seed) {}
        }
    }
}.getOrNull()

fun seedColorScheme(seed: Int, isDark: Boolean) =
    dynamicColorScheme(seedColor = Color(seed), isDark = isDark, isAmoled = false, style = PaletteStyle.Vibrant)
