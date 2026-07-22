package com.wolfeleo2.thingy.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background daemon that ensures all Cloudinary images are saved permanently to disk 
 * in `filesDir` for true offline-first capability on secondary devices.
 * 
 * It queries Firestore for all images belonging to the user. For any image where
 * `imageUrl` is set (Cloudinary) and `storagePath` is set (e.g. /data/user/0/.../123.jpg),
 * it checks if that file already exists on this device.
 * If not, it downloads the image from Cloudinary to that exact path.
 */
class OfflineImageSyncer(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext

        // Fetch all image/video/link items for this user.
        val snap = runCatching { 
            db.collection("items")
                .whereEqualTo("userId", uid)
                .whereIn("type", listOf(ItemType.IMAGE.wire, ItemType.VIDEO.wire, ItemType.LINK.wire))
                .get()
                .await()
        }.getOrNull() ?: return@withContext

        val items = snap.documents.mapNotNull { it.toObject(Item::class.java) }
        var syncedCount = 0

        val syncDir = File(context.filesDir, "saved")
        if (!syncDir.exists()) syncDir.mkdirs()

        for (item in items) {
            val url = if (item.type == ItemType.LINK.wire) item.heroImageUrl else item.imageUrl
            if (url.isNullOrBlank()) continue
            
            // If the native local path exists on this exact device, skip
            val nativePath = item.storagePath?.takeIf { it.startsWith("/") }?.let { File(it) }
            if (nativePath != null && nativePath.exists()) continue
            
            // Otherwise, check if we've already synced it
            val ext = if (item.type == ItemType.VIDEO.wire) "mp4" else "webp"
            val syncedFile = File(syncDir, "${item.id}.$ext")

            if (!syncedFile.exists()) {
                Log.d(TAG, "[${item.id}] Local file missing. Syncing from Cloudinary...")
                val downloaded = downloadImage(url, syncedFile)
                if (downloaded) {
                    syncedCount++
                    Log.d(TAG, "[${item.id}] Successfully synced offline.")
                    // Slight throttle to not blast the network
                    delay(200.milliseconds)
                } else {
                    Log.w(TAG, "[${item.id}] Failed to download.")
                }
            }
        }
        
        if (syncedCount > 0) {
            Log.i(TAG, "Offline sync complete: $syncedCount new image(s) downloaded.")
        }
    }

    private fun downloadImage(urlStr: String, destination: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (conn.responseCode == 200) {
                destination.outputStream().use { out ->
                    conn.inputStream.copyTo(out)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception downloading image: ${e.message}")
            // Clean up partial file on failure
            if (destination.exists()) destination.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "OfflineImageSyncer"
    }
}
