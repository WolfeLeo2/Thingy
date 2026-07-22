package com.wolfeleo2.thingy.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TidyPhoto(val id: Long, val uri: Uri)
data class TidyAlbum(val id: String, val name: String)

object TidyMedia {
    const val ALL_PHOTOS = "__all__"

    /** Distinct camera-roll albums (buckets), newest activity first, with "All Photos" pinned. */
    suspend fun loadAlbums(context: Context): List<TidyAlbum> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val seen = LinkedHashMap<String, String>()
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: continue
                if (id !in seen) seen[id] = name
            }
        }
        listOf(TidyAlbum(ALL_PHOTOS, "All Photos")) + seen.map { TidyAlbum(it.key, it.value) }
    }

    /** Newest-first page, optionally scoped to one album. */
    suspend fun load(context: Context, limit: Int, offset: Int, bucketId: String?): List<TidyPhoto> =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val scoped = bucketId != null && bucketId != ALL_PHOTOS
            val selection = if (scoped) "${MediaStore.Images.Media.BUCKET_ID} = ?" else null
            val args = if (scoped) arrayOf(bucketId) else null
            val out = ArrayList<TidyPhoto>(limit)
            context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                if (c.moveToPosition(offset)) {
                    do {
                        out += TidyPhoto(c.getLong(idCol), ContentUris.withAppendedId(collection, c.getLong(idCol)))
                    } while (out.size < limit && c.moveToNext())
                }
            }
            out
        }

    /**
     * IntentSender to confirm deleting [uris]. API 30+ uses the batch trash dialog; API 29 deletes
     * what it can directly and returns the first item's recovery sender (the OS grants one at a time).
     */
    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }
        // API 29: try direct deletes; surface the first RecoverableSecurityException for confirmation.
        for (uri in uris) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: RecoverableSecurityException) {
                return e.userAction.actionIntent.intentSender
            } catch (_: Exception) {
                // not deletable — skip
            }
        }
        return null
    }
}
