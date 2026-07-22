package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * One-time migration that pushes all image items whose imageUrl is NOT already a
 * Cloudinary URL up to Cloudinary, then patches the Firestore doc.
 *
 * Handles two legacy states:
 *  • imageUrl starts with '/'       → absolute local path (pre-cloud items)
 *  • imageUrl starts with 'https://firebasestorage' → old Firebase Storage URL
 *
 * Both cases download/read the bytes, upload to Cloudinary via the unsigned preset,
 * and update imageUrl. storagePath is left untouched.
 *
 * Safe to run repeatedly — items that already have a Cloudinary URL are skipped.
 * Remove this class (and its call in AppRoot) once all devices have run it.
 */
class CloudinaryMigration(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext

        // Fetch all image items for this user.
        val snap = db.collection("items")
            .whereEqualTo("userId", uid)
            .whereEqualTo("type", ItemType.IMAGE.wire)
            .get()
            .await()

        val candidates = snap.documents.filter { doc ->
            val url = doc.getString("imageUrl") ?: return@filter false
            // Skip anything already on Cloudinary.
            !url.startsWith("https://res.cloudinary.com")
        }

        if (candidates.isEmpty()) {
            Log.i(TAG, "All image items already on Cloudinary — nothing to migrate.")
            return@withContext
        }

        Log.i(TAG, "Migrating ${candidates.size} image item(s) to Cloudinary…")
        var ok = 0; var fail = 0

        for (doc in candidates) {
            val id       = doc.id
            val imageUrl = doc.getString("imageUrl") ?: continue
            val isSticker = doc.getBoolean("sticker") ?: false
            val ext = if (isSticker) "png" else "jpg"

            runCatching {
                val bytes = readBytes(imageUrl) ?: run {
                    Log.w(TAG, "[$id] Could not read bytes from $imageUrl — skipping")
                    fail++
                    return@runCatching
                }

                val (cloudUrl, _) = uploadToCloudinary(bytes, ext) ?: run {
                    Log.w(TAG, "[$id] Cloudinary upload failed — skipping")
                    fail++
                    return@runCatching
                }

                db.collection("items").document(id).update("imageUrl", cloudUrl).await()
                Log.i(TAG, "[$id] ✓ $imageUrl → $cloudUrl")
                ok++
            }.onFailure {
                Log.w(TAG, "[$id] Migration error", it)
                fail++
            }

            // Gentle throttle — Cloudinary free tier is generous but not unlimited.
            delay(300)
        }

        Log.i(TAG, "Migration complete: $ok succeeded, $fail failed.")
    }

    /**
     * Reads image bytes from whichever source the imageUrl points at:
     * - Local absolute path (starts with '/') → read from File
     * - HTTP(S) URL (Firebase Storage or other) → download via HttpURLConnection
     */
    private fun readBytes(imageUrl: String): ByteArray? {
        return when {
            imageUrl.startsWith("/") -> {
                runCatching { File(imageUrl).readBytes() }.getOrNull()
            }
            imageUrl.startsWith("http") -> {
                var conn: HttpURLConnection? = null
                runCatching {
                    conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout    = 60_000
                    }
                    if (conn!!.responseCode == 200) conn!!.inputStream.readBytes() else null
                }.getOrNull().also { conn?.disconnect() }
            }
            else -> null
        }
    }

    private fun uploadToCloudinary(bytes: ByteArray, ext: String): Pair<String, String>? {
        val boundary = "ThingyMigBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val mimeType = if (ext == "png") "image/png" else "image/jpeg"
        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput     = true
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.buffered().use { out ->
                fun part(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                part("upload_preset", UPLOAD_PRESET)
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

    companion object {
        private const val TAG           = "CloudinaryMigration"
        private const val CLOUD_NAME    = "cumjajjx"
        private const val UPLOAD_PRESET = "ml_default"
        private const val UPLOAD_URL    = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload"
    }
}
