package com.wolfeleo2.thingy.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.log10

/**
 * Records a voice note and ingests it, mirroring [ImageIngestor]'s local-first flow:
 * record straight to filesDir, create the Firestore doc pointing at that local file, then
 * background-upload to Cloudinary and patch imageUrl to the CDN URL.
 *
 * Unlike video there's no transcode step — AAC in an m4a container is already small and is a
 * format Gemini accepts directly, so the file the recorder writes is the file that gets classified
 * and the file that gets uploaded. That also means an audio item is never stuck waiting on a
 * transcode the way [VideoIngestor]'s items can be.
 */
class AudioIngestor(
    private val context: Context,
    private val items: ItemRepository = ItemRepository(),
    /** Outlives the caller so background uploads finish even after the compose scope ends. */
    private val uploadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    /** True between [start] and [stop]/[cancel]. */
    val isRecording: Boolean get() = recorder != null

    /**
     * Begins recording into a fresh file under `filesDir/audio`. Throws if the mic is unavailable
     * (in use by another app, or permission revoked between the check and here).
     */
    fun start() {
        check(recorder == null) { "Already recording" }
        val file = File(context.filesDir, "audio/${UUID.randomUUID()}.m4a")
            .also { it.parentFile?.mkdirs() }
        @Suppress("DEPRECATION") // the Context ctor is API 31+; minSdk is lower
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Voice, not music: mono at 32kbps is ~4KB/s, so even a long note stays a small upload
            // and a cheap Gemini request.
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(32_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        target = file
        startedAt = System.currentTimeMillis()
    }

    /**
     * Current mic level as 0f..1f, for a level meter. Returns 0f when not recording.
     *
     * MediaRecorder reports peak amplitude since the previous call, so this must be polled at a
     * steady interval or the values mean different things each time. Raw amplitude is close to
     * useless for a meter — loudness is perceived logarithmically, so a linear bar sits near zero
     * through all of normal speech. Mapping through dB and clamping at [QUIET_FLOOR_DB] is what
     * makes a voice fill the meter.
     */
    fun currentLevel(): Float {
        val rec = recorder ?: return 0f
        return amplitudeToLevel(runCatching { rec.maxAmplitude }.getOrDefault(0))
    }

    /** Stops recording and discards the file — nothing is saved. Safe to call when not recording. */
    fun cancel() {
        releaseRecorder()
        target?.delete()
        target = null
    }

    /**
     * Stops recording and ingests the result, returning the new item id.
     *
     * Recordings shorter than [MIN_DURATION_MS] are discarded and return null: that's a mistap, and
     * MediaRecorder often can't even finalize a container that short.
     */
    suspend fun stopAndIngest(spaceId: String? = null): String? = withContext(Dispatchers.IO) {
        val file = target
        val duration = System.currentTimeMillis() - startedAt
        releaseRecorder()
        target = null
        if (file == null) return@withContext null
        if (duration < MIN_DURATION_MS || !file.exists() || file.length() == 0L) {
            file.delete()
            return@withContext null
        }

        // 1. Firestore doc pointing at the local file — the note is usable offline immediately.
        val id = items.createAudio(
            imageUrl = file.absolutePath,   // swapped to the CDN URL once uploaded
            storagePath = file.absolutePath, // stays local — used for playback and deletion
            durationMillis = duration,
            capturedAt = System.currentTimeMillis(),
            latitude = null,
            longitude = null,
            spaceId = spaceId,
        )

        // 2. Background upload; the item stays local-only on this device if it fails.
        uploadScope.launch {
            runCatching {
                val (cloudUrl, _) = uploadToCloudinary(file.readBytes()) ?: return@runCatching
                items.updateImageUrl(id, cloudUrl)
                Log.i(TAG, "Cloudinary audio upload complete for $id → $cloudUrl")
            }.onFailure {
                SyncStatus.report("Voice note saved on this device, but couldn't back it up", it)
            }
        }
        id
    }

    private fun releaseRecorder() {
        val rec = recorder ?: return
        recorder = null
        // stop() throws if the recording was too short to produce a valid file — the caller's
        // duration check handles that case, so swallowing it here is the whole recovery.
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    /** Cloudinary handles audio under its `video` resource type — same endpoint as [VideoIngestor]. */
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
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                return json.getString("secure_url") to json.getString("public_id")
            }
            Log.w(TAG, "Cloudinary audio upload rejected: HTTP ${conn.responseCode}")
        } catch (e: IOException) {
            Log.w(TAG, "Cloudinary audio upload failed", e)
        } finally {
            conn.disconnect()
        }
        return null
    }

    companion object {
        private const val TAG = "AudioIngestor"
        private const val CLOUD_NAME = "cumjajjx"
        private const val UPLOAD_PRESET = "ml_default"
        private const val UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/video/upload"

        /** Below this a tap is a mistap, and MediaRecorder may not produce a valid container. */
        internal const val MIN_DURATION_MS = 1_000L

        /** MediaRecorder amplitude is a signed 16-bit peak. */
        private const val MAX_AMPLITUDE = 32_767.0

        /** Anything quieter than this reads as silence on the meter. See [currentLevel]. */
        internal const val QUIET_FLOOR_DB = -50.0

        /**
         * Peak amplitude to a 0f..1f meter level.
         *
         * ponytail: one fixed floor, tuned by ear against a phone mic at arm's length. Real mics
         * differ and a noisy room raises the floor — if the meter reads hot or dead on some device,
         * this is the knob, and an auto-gain that tracks a rolling minimum is the upgrade.
         */
        internal fun amplitudeToLevel(amplitude: Int): Float {
            if (amplitude <= 0) return 0f
            val db = 20.0 * log10(amplitude.toDouble() / MAX_AMPLITUDE)
            return ((db - QUIET_FLOOR_DB) / -QUIET_FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
