package com.wolfeleo2.thingy.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
)

private const val RELEASES_URL = "https://api.github.com/repos/WolfeLeo2/Thingy/releases/latest"

class UpdateChecker(private val context: Context) {

    /** Returns the latest release iff it's newer than [currentVersion], with a direct APK download link. */
    suspend fun check(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name").removePrefix("v")
            if (compareVersions(tag, currentVersion) <= 0) return@withContext null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            AppUpdate(version = tag, notes = json.optString("body", ""), apkUrl = apkUrl ?: return@withContext null)
        }.getOrNull()
    }

    suspend fun download(update: AppUpdate, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            // filesDir, NOT cacheDir: the system reclaims cacheDir first when storage runs low, and it
            // will happily evict a 50 MB APK out from under us between this write and the install —
            // which surfaces as the installer's useless "There was a problem parsing this package."
            // install() also detours through the "allow unknown sources" screen, so that window is long.
            val dir = File(context.filesDir, "updates").also { it.mkdirs() }
            // Nothing else reaps these; without this every release leaves another ~50 MB behind forever.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "thingy-${update.version}.apk")
            val conn = URL(update.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            // Staging + dexopt need well over the APK's own size; failing here beats a parse error later.
            if (total > 0 && dir.usableSpace < total * 2) {
                throw java.io.IOException(
                    "Not enough free space: needs ~${total * 2 / 1_000_000} MB, " +
                        "${dir.usableSpace / 1_000_000} MB available",
                )
            }
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, total)
                    }
                    // A connection that drops mid-stream can still return EOF cleanly, silently
                    // truncating the file — that's what the installer later reports as "There was
                    // a problem parsing this package." Catch it here as a retryable download error.
                    if (total > 0 && bytesRead != total) {
                        throw java.io.IOException("Incomplete download: got $bytesRead of $total bytes")
                    }
                }
            }
            file
        }

    /**
     * Cheap structural check that [apkFile] is still a complete APK. Reading the zip central directory
     * catches truncation or eviction that happened after the download's byte-count check — the installer's
     * own diagnosis for that state is the unhelpful "problem parsing the package".
     */
    private fun isIntactApk(apkFile: File): Boolean = runCatching {
        java.util.zip.ZipFile(apkFile).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Fires the system installer for [apkFile], or routes to the "allow unknown sources" setting
     * first. Returns true iff the installer was actually launched — false means the caller should
     * hold onto [apkFile] and retry once the user grants the permission and returns to the app.
     */
    fun install(apkFile: File): Boolean {
        // Re-check here, not just after download: the permission detour can leave the file sitting for
        // minutes, and on a nearly-full device it may not survive that wait.
        if (!isIntactApk(apkFile)) {
            apkFile.delete()
            throw java.io.IOException("Update package was incomplete or removed before install — please retry")
        }
        if (!canInstallPackages()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return true
    }
}

private fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
