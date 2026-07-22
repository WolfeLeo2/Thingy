package com.wolfeleo2.thingy.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class VideoIngestor(
    private val context: Context,
    private val items: ItemRepository = ItemRepository(),
    private val uploadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    suspend fun ingestUri(uri: Uri, spaceId: String? = null): String = withContext(Dispatchers.IO) {
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
        if (size > 150_000_000L) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Video is too large (Limit: 150MB)", Toast.LENGTH_LONG).show() }
            throw IOException("Video exceeds 150MB limit")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            throw IOException("Could not read video metadata", e)
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val duration = durationStr?.toLongOrNull() ?: 0L
        if (duration > 31_000L) { // 31s to allow tiny overflows
            withContext(Dispatchers.Main) { Toast.makeText(context, "Video is too long (Limit: 30s)", Toast.LENGTH_LONG).show() }
            throw IOException("Video exceeds 30s limit")
        }

        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1.0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1.0
        val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val ratio = if (rot == 90 || rot == 270) height / width else width / height

        val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val capturedAt = dateStr?.let {
            runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).parse(it)?.time }.getOrNull()
        }
        val locationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        // ISO 6709 e.g. "+37.4220-122.0840/"
        var lat: Double? = null
        var lng: Double? = null
        if (locationStr != null) {
            val match = Regex("""([+-]\d+\.\d+)([+-]\d+\.\d+)""").find(locationStr)
            if (match != null) {
                lat = match.groupValues[1].toDoubleOrNull()
                lng = match.groupValues[2].toDoubleOrNull()
            }
        }
        retriever.release()

        // 1. Create Firestore doc instantly with original URI so it appears immediately
        val id = items.createVideo(
            imageUrl = uri.toString(),
            storagePath = uri.toString(),
            aspectRatio = ratio,
            durationMillis = duration,
            capturedAt = capturedAt,
            latitude = lat,
            longitude = lng,
            spaceId = spaceId
        )

        val uuid = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, "videos/$uuid.mp4").also { it.parentFile?.mkdirs() }

        // 2. Background transcode and upload
        uploadScope.launch {
            runCatching {
                // Transcode via Media3 Transformer to 720p H.264
                suspendCancellableCoroutine<ExportResult> { cont ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            cont.resume(result)
                        }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            cont.resumeWithException(exception)
                        }
                    }
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    mainHandler.post {
                        val transformer = Transformer.Builder(context)
                            .addListener(listener)
                            .build()
                        
                        val effect = Presentation.createForHeight(720)
                        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setEffects(Effects(emptyList(), listOf(effect)))
                            .build()
                        
                        try {
                            transformer.start(item, outputFile.absolutePath)
                            cont.invokeOnCancellation { transformer.cancel() }
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
                
                val bytes = outputFile.readBytes()
                val (cloudUrl, _) = uploadToCloudinary(bytes) ?: return@runCatching
                
                // Patch the item with the cloud URL and the transcoded local file
                items.updateImageUrl(id, cloudUrl, outputFile.absolutePath)
                Log.i(TAG, "Cloudinary video upload complete for $id → $cloudUrl")
            }.onFailure {
                Log.w(TAG, "Cloudinary video upload failed for $id", it)
            }
        }

        id
    }

    private fun uploadToCloudinary(bytes: ByteArray): Pair<String, String>? {
        val boundary = "ThingyBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n")
                out.writeBytes("$UPLOAD_PRESET\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"video.mp4\"\r\n")
                out.writeBytes("Content-Type: video/mp4\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                return json.getString("secure_url") to json.getString("public_id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        } finally {
            conn.disconnect()
        }
        return null
    }

    companion object {
        private const val TAG = "VideoIngestor"
        private const val CLOUD_NAME = "cumjajjx"
        private const val UPLOAD_PRESET = "ml_default"
        private const val UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/video/upload"
    }
}
