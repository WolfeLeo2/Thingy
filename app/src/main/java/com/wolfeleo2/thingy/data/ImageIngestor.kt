package com.wolfeleo2.thingy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** No subject found by ML Kit — the caller alerts and stays (PRD camera behavior). */
class NoSubjectException : Exception("No subject found in image")

/**
 * Ingests an image (camera capture or gallery pick).
 *
 * Flow:
 *  1. Process on-device (ML Kit cutout, sticker outline, compress).
 *  2. Save processed bytes to filesDir immediately — item is visible offline.
 *  3. Create Firestore doc with local path as imageUrl.
 *  4. Background-upload to Cloudinary; on success patch imageUrl to the CDN URL.
 *     storagePath stays as the local absolute path throughout (used for local deletion).
 *
 * If upload fails the item gracefully stays local-only on this device.
 */
class ImageIngestor(
    private val context: Context,
    private val items: ItemRepository = ItemRepository(),
    /** Outlives the caller so background uploads finish even after the compose scope ends. */
    private val uploadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .enableForegroundBitmap()
                .build(),
        )
    }

    /** From a gallery/content Uri — reads EXIF date + GPS. */
    suspend fun ingestUri(uri: Uri, asSticker: Boolean, spaceId: String? = null): String = withContext(Dispatchers.IO) {
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
        if (size > 20_000_000L) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Image is too large (Limit: 20MB)", android.widget.Toast.LENGTH_LONG).show()
            }
            throw IOException("File exceeds 20MB limit")
        }

        val (capturedAt, lat, lng) = readExif(uri)
        val bitmap = decodeBitmap(uri) ?: throw IOException("Could not decode image")
        finish(bitmap, asSticker, spaceId, capturedAt, lat, lng)
    }

    /** From an in-memory bitmap (camera capture) — no EXIF, captured now. */
    suspend fun ingestBitmap(bitmap: Bitmap, asSticker: Boolean, spaceId: String? = null): String =
        withContext(Dispatchers.IO) { finish(bitmap, asSticker, spaceId, System.currentTimeMillis(), null, null) }

    private suspend fun finish(
        source: Bitmap, asSticker: Boolean, spaceId: String?,
        capturedAt: Long?, lat: Double?, lng: Double?,
    ): String {
        var bitmap = source
        var sticker = false
        if (asSticker) {
            val cut = cropToContent(cutout(source) ?: throw NoSubjectException())
            val outlineWidth = (maxOf(cut.width, cut.height) * 0.012f).coerceIn(8f, 64f)
            bitmap = addStickerOutline(cut, outlineWidth)
            sticker = true
        }

        val ext = if (sticker) "png" else "webp"
        val uuid = UUID.randomUUID().toString()

        // Encode once — reused for both local save and Cloudinary upload.
        val bytes = ByteArrayOutputStream().use { out ->
            if (sticker) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
                }
            }
            out.toByteArray()
        }

        // 1. Save locally — item is immediately usable, even if offline.
        val file = File(context.filesDir, "images/$uuid.$ext").also { it.parentFile?.mkdirs() }
        FileOutputStream(file).use { it.write(bytes) }

        val ratio = if (bitmap.height > 0) bitmap.width.toDouble() / bitmap.height else 1.0

        // 2. Write Firestore doc with local path so the item appears instantly.
        val id = items.createImage(
            imageUrl  = file.absolutePath,   // will be swapped to CDN URL after upload
            storagePath = file.absolutePath, // stays as local path — used for local file deletion
            isSticker = sticker,
            aspectRatio = ratio,
            capturedAt = capturedAt,
            latitude = lat,
            longitude = lng,
            spaceId = spaceId,
        )

        // 3. Background upload — patches imageUrl to Cloudinary CDN URL on success.
        uploadScope.launch {
            runCatching {
                val (cloudUrl, _) = uploadToCloudinary(bytes, ext) ?: return@runCatching
                items.updateImageUrl(id, cloudUrl)
                Log.i(TAG, "Cloudinary upload complete for $id → $cloudUrl")
            }.onFailure {
                Log.w(TAG, "Cloudinary upload failed for $id — item stays local", it)
            }
        }

        return id
    }

    /**
     * Uploads [bytes] to Cloudinary via a multipart POST using an unsigned upload preset.
     * Returns (secureUrl, publicId) on success, null on any failure.
     * Must be called on a background thread.
     */
    private fun uploadToCloudinary(bytes: ByteArray, ext: String): Pair<String, String>? {
        val boundary = "ThingyBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val mimeType = if (ext == "png") "image/png" else "image/webp"
        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout  = 60_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.buffered().use { out ->
                fun part(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                part("upload_preset", UPLOAD_PRESET)
                // Binary file part
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.$ext\"\r\nContent-Type: $mimeType\r\n\r\n").toByteArray())
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            return if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                json.getString("secure_url") to json.getString("public_id")
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "Cloudinary HTTP $code: $err")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Tight-crop to the subject's opaque bounds (+small margin) so a cutout fills its frame. */
    private fun cropToContent(bmp: Bitmap, margin: Int = 6): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        val stride = maxOf(1, minOf(w, h) / 512)
        var y = 0
        while (y < h) {
            val row = y * w; var x = 0
            while (x < w) {
                if ((px[row + x] ushr 24) > 12) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
                x += stride
            }
            y += stride
        }
        if (maxX < minX || maxY < minY) return bmp
        val x0 = (minX - margin).coerceAtLeast(0); val y0 = (minY - margin).coerceAtLeast(0)
        val x1 = (maxX + margin).coerceAtMost(w - 1); val y1 = (maxY + margin).coerceAtMost(h - 1)
        return Bitmap.createBitmap(bmp, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /**
     * Die-cut sticker look (Amber's recipe): grow the subject's silhouette and fill it white,
     * then composite the subject on top. Disc dilation is approximated by stamping the alpha
     * silhouette around a circle — no CoreImage morphology on Android.
     */
    private fun addStickerOutline(subject: Bitmap, outlineWidth: Float): Bitmap {
        val shadowRadius = 14f
        val shadowDy = 8f
        val pad = Math.ceil(outlineWidth.toDouble()).toInt() + shadowRadius.toInt() + 8
        val out = Bitmap.createBitmap(subject.width + pad * 2, subject.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val alpha = subject.extractAlpha()
        
        // 1. Build the solid white outline on an intermediate canvas
        val outlineBmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        val outlineCanvas = Canvas(outlineBmp)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val steps = 32
        for (i in 0 until steps) {
            val a = 2.0 * Math.PI * i / steps
            outlineCanvas.drawBitmap(alpha, pad + (Math.cos(a) * outlineWidth).toFloat(), pad + (Math.sin(a) * outlineWidth).toFloat(), white)
        }
        outlineCanvas.drawBitmap(alpha, pad.toFloat(), pad.toFloat(), white)  // fill interior

        // 2. Extract the alpha of the combined outline to create a drop shadow
        val outlineAlpha = outlineBmp.extractAlpha()
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38000000") // 22% black
            maskFilter = android.graphics.BlurMaskFilter(shadowRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        // 3. Composite everything onto the final canvas
        canvas.drawBitmap(outlineAlpha, 0f, shadowDy, shadowPaint) // Shadow
        canvas.drawBitmap(outlineBmp, 0f, 0f, null)                // White outline
        canvas.drawBitmap(subject, pad.toFloat(), pad.toFloat(), null) // Subject

        outlineBmp.recycle()
        outlineAlpha.recycle()
        alpha.recycle()
        
        return out
    }

    private suspend fun cutout(bitmap: Bitmap): Bitmap? {
        val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width
        val h = bitmap.height
        val mask = result.foregroundConfidenceMask
        if (mask != null && mask.capacity() == w * h) {
            val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            var kept = 0
            for (idx in 0 until w * h) {
                if (mask.get(idx) < 0.5f) pixels[idx] = 0 else kept++
            }
            if (kept < w * h * 0.004) return null
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        return result.foregroundBitmap
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val max = maxOf(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (max > 2048) Integer.highestOneBit(max / 2048) else 1
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return rotateByExif(uri, bmp)
    }

    private fun rotateByExif(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return bmp
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }

    private data class Exif(val capturedAt: Long?, val lat: Double?, val lng: Double?)

    private fun readExif(uri: Uri): Exif = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLng = exif.latLong
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
            }
            Exif(date, latLng?.getOrNull(0), latLng?.getOrNull(1))
        } ?: Exif(null, null, null)
    } catch (e: Exception) {
        Exif(null, null, null)
    }

    companion object {
        private const val TAG          = "ImageIngestor"
        private const val CLOUD_NAME   = "cumjajjx"
        private const val UPLOAD_PRESET = "ml_default"
        private const val UPLOAD_URL   = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload"
    }
}
