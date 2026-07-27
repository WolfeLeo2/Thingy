package com.wolfeleo2.thingy.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.wolfeleo2.thingy.BuildConfig
import com.wolfeleo2.thingy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Export my data": a .zip in Downloads holding a full JSON dump plus every media file we can
 * reach. The other half of self-service deletion — an app that lets you erase everything should
 * also let you take it with you.
 *
 * Media comes from the local copy when there is one and from Cloudinary otherwise. Anything that
 * exists on neither (saved on another device, never synced) is listed by id under `mediaMissing`
 * in the manifest rather than silently dropped, so a partial archive says so.
 */
class DataExporter(
    private val context: Context,
    private val items: ItemRepository = ItemRepository(),
    private val spaces: SpaceRepository = SpaceRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    data class Summary(val fileName: String, val itemCount: Int, val mediaMissing: Int)

    /** Writes the archive; [onProgress] reports (done, total) over the media pass. */
    suspend fun export(onProgress: (Int, Int) -> Unit = { _, _ -> }): Summary = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: error("Not signed in")

        val allItems = items.snapshotAllItems()
        val allSpaces = spaces.snapshotAllSpaces()
        val memberships = spaces.snapshotOwnMemberships()

        val refs = allItems.associateWith { mediaRefFor(it) { path -> File(path).exists() } }
        val missing = mutableListOf<String>()
        var written = 0

        val fileName = exportFileName(Date())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the export file in Downloads")

        try {
            resolver.openOutputStream(uri)!!.buffered().use { raw ->
                ZipOutputStream(raw).use { zip ->
                    val total = refs.values.count { it != null }
                    for ((item, ref) in refs) {
                        if (ref == null) continue
                        val local = ref.localPath?.let { File(it) }?.takeIf { it.exists() }
                        val bytes = when {
                            local != null -> runCatching { local.readBytes() }.getOrNull()
                            ref.remoteUrl != null -> download(ref.remoteUrl)
                            else -> null
                        }
                        if (bytes == null) {
                            missing += item.id
                        } else {
                            zip.putNextEntry(ZipEntry(ref.entryName))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                        written++
                        onProgress(written, total)
                    }

                    zip.putNextEntry(ZipEntry("thingy-export.json"))
                    zip.write(
                        buildManifest(uid, allItems, allSpaces, memberships, missing).toByteArray(),
                    )
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("README.txt"))
                    zip.write(READ_ME.toByteArray())
                    zip.closeEntry()
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            // A half-written pending file is worse than none — it stays invisible but eats space.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        Summary(fileName, allItems.size, missing.size)
    }

    private fun mediaRefFor(item: Item, exists: (String) -> Boolean) =
        mediaRefFor(item, context.filesDir.absolutePath, exists)

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
            }
            if (conn.responseCode == 200) conn.inputStream.readBytes() else null
        } catch (e: Exception) {
            Log.w(TAG, "export: couldn't fetch $url", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "DataExporter"

        private val READ_ME = """
            Thingy data export
            ==================

            thingy-export.json  Everything Thingy stores about your account: every saved item with
                                its AI-generated title, description, tags and intents; your spaces;
                                and which items belong to which space.
            media/              Your photos and videos, named by item id — the same id used in
                                thingy-export.json so you can match them up.

            Anything listed under "mediaMissing" in the JSON couldn't be included: those files were
            saved on another device and never synced to the cloud, so this device has no copy.
        """.trimIndent()
    }
}

/** Where one item's media can be read from, and what it's called inside the zip. */
internal data class MediaRef(val entryName: String, val localPath: String?, val remoteUrl: String?)

/**
 * Resolves an item's media to a local file, a remote URL, or neither. Mirrors `Item.previewModel`'s
 * precedence — on-device original first, then the synced copy, then the CDN — because that's the
 * order that avoids re-downloading what's already here.
 *
 * Returns null for items that have no media at all (notes), and a ref with **both** paths null for
 * media this device genuinely cannot reach; the caller reports those as missing rather than
 * pretending the archive is complete.
 */
internal fun mediaRefFor(item: Item, filesDirPath: String, exists: (String) -> Boolean): MediaRef? {
    val ext = when (item.type) {
        ItemType.VIDEO.wire -> "mp4"
        ItemType.IMAGE.wire, ItemType.LINK.wire -> if (item.sticker == true) "png" else "webp"
        else -> return null // notes carry no media
    }
    val remote = (if (item.type == ItemType.LINK.wire) item.heroImageUrl else item.imageUrl)
        ?.takeIf { it.startsWith("http") }
    // A link with no hero image is a link, not a broken download — nothing to export.
    if (item.type == ItemType.LINK.wire && remote == null) return null

    val native = item.storagePath?.takeIf { it.startsWith("/") && exists(it) }
    val synced = "$filesDirPath/saved/${item.id}.$ext".takeIf { exists(it) }
    return MediaRef("media/${item.id}.$ext", native ?: synced, remote)
}

/** `thingy-export-2026-07-27.zip` — sortable, and obvious in a Downloads folder. */
internal fun exportFileName(now: Date): String =
    "thingy-export-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)}.zip"

internal fun buildManifest(
    userId: String,
    items: List<Item>,
    spaces: List<Space>,
    memberships: List<SpaceItem>,
    mediaMissing: List<String>,
): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun date(d: Date?) = d?.let { iso.format(it) }

    val root = JSONObject()
        .put("formatVersion", 1)
        .put("app", "Thingy")
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("exportedAt", iso.format(Date()))
        .put("userId", userId)
        .put(
            "counts",
            JSONObject()
                .put("items", items.size)
                .put("spaces", spaces.size)
                .put("memberships", memberships.size)
                .put("mediaMissing", mediaMissing.size),
        )
        .put("mediaMissing", JSONArray(mediaMissing))

    root.put(
        "items",
        JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("type", item.type)
                        .put("status", item.status)
                        .put("title", item.title)
                        .put("description", item.description)
                        .put("note", item.note)
                        .put("url", item.url)
                        .put("siteName", item.siteName)
                        .put("content", item.content)
                        .put("tags", JSONArray(item.tags))
                        .put("ocrText", item.ocrText)
                        .put("searchText", item.searchText)
                        .put("imageUrl", item.imageUrl)
                        .put("heroImageUrl", item.heroImageUrl)
                        .put("sticker", item.sticker)
                        .put("aspectRatio", item.aspectRatio)
                        .put("durationMillis", item.durationMillis)
                        .put("latitude", item.latitude)
                        .put("longitude", item.longitude)
                        .put("capturedAt", item.capturedAt)
                        .put("createdAt", date(item.createdAt))
                        .put(
                            "intents",
                            JSONArray().apply {
                                item.intents?.forEach {
                                    put(
                                        JSONObject().put("kind", it.kind).put("label", it.label)
                                            .put("value", it.value),
                                    )
                                }
                            },
                        )
                        .put(
                            "products",
                            JSONArray().apply {
                                item.products?.forEach {
                                    put(
                                        JSONObject().put("title", it.title).put("url", it.url)
                                            .put("price", it.price).put("merchant", it.merchant),
                                    )
                                }
                            },
                        ),
                )
            }
        },
    )

    root.put(
        "spaces",
        JSONArray().apply {
            spaces.forEach { space ->
                put(
                    JSONObject()
                        .put("id", space.id)
                        .put("name", space.name)
                        .put("description", space.description)
                        .put("dynamic", space.dynamic)
                        .put("owner", space.userId)
                        .put("memberIds", JSONArray(space.memberIds))
                        .put("createdAt", date(space.createdAt)),
                )
            }
        },
    )

    root.put(
        "memberships",
        JSONArray().apply {
            memberships.forEach {
                put(
                    JSONObject().put("spaceId", it.spaceId).put("itemId", it.itemId)
                        .put("status", it.status ?: SpaceItemStatus.SAVED.wire),
                )
            }
        },
    )

    return root.toString(2)
}

/**
 * Runs the export off the UI entirely. WorkManager rather than a ViewModel scope on purpose: this
 * outlives the Settings screen, the back stack, and the app being backgrounded mid-export.
 *
 * ponytail: no foreground service, so this lives inside WorkManager's ~10 minute execution window.
 * Fine at this app's scale; a library of thousands of un-synced videos would need `setForeground`
 * and the dataSync permission.
 */
class ExportWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ensureChannel(context)
        notify(NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Exporting your data…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build())

        val summary = runCatching {
            DataExporter(context).export { done, total ->
                notify(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Exporting your data…")
                        .setContentText("$done of $total files")
                        .setProgress(total, done, total == 0)
                        .setOngoing(true)
                        .setSilent(true)
                        .build(),
                )
            }
        }.getOrElse { e ->
            Log.w("Thingy", "export failed", e)
            notify(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Export failed")
                    .setContentText(e.message ?: "Something went wrong — try again.")
                    .setAutoCancel(true)
                    .build(),
            )
            return Result.failure()
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = if (summary.mediaMissing > 0) {
            " ${summary.mediaMissing} file(s) weren't on this device — see the README."
        } else ""
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Export ready")
                .setContentText("${summary.itemCount} items saved to Downloads.$note")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${summary.itemCount} items saved to Downloads as ${summary.fileName}.$note"),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    private fun notify(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "thingy_export"
        private const val NOTIFICATION_ID = 4242
        private const val WORK_NAME = "data_export"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Data export",
                NotificationManager.IMPORTANCE_LOW, // progress, not news — must not buzz
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        /** KEEP, not REPLACE: double-tapping the row shouldn't restart a running export. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ExportWorker>().build(),
            )
        }
    }
}
