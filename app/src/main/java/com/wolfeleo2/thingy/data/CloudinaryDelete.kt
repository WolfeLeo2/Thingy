package com.wolfeleo2.thingy.data

import com.wolfeleo2.thingy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val CLOUD_NAME = "cumjajjx"

/** public_id isn't persisted at upload time — recovered from the stored secure_url instead
 * (e.g. https://res.cloudinary.com/<cloud>/image/upload/v169.../abc123.webp -> "abc123"). */
private val PUBLIC_ID_REGEX = Regex("""/upload/(?:v\d+/)?([^?]+)\.[a-zA-Z0-9]+(?:\?.*)?$""")

fun cloudinaryPublicIdFrom(url: String): String? = PUBLIC_ID_REGEX.find(url)?.groupValues?.get(1)

/** Deletes the Cloudinary asset via the signed "destroy" API, using the api_key/secret baked
 * into BuildConfig (see build.gradle.kts comment). */
suspend fun deleteFromCloudinary(publicId: String, resourceType: String) = withContext(Dispatchers.IO) {
    runCatching {
        val apiKey = BuildConfig.CLOUDINARY_API_KEY
        val apiSecret = BuildConfig.CLOUDINARY_API_SECRET
        if (apiKey.isBlank() || apiSecret.isBlank()) return@runCatching

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val toSign = "public_id=$publicId&timestamp=$timestamp$apiSecret"
        val signature = MessageDigest.getInstance("SHA-1").digest(toSign.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val conn = URL("https://api.cloudinary.com/v1_1/$CLOUD_NAME/$resourceType/destroy")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use {
            it.write("public_id=$publicId&timestamp=$timestamp&api_key=$apiKey&signature=$signature".toByteArray())
        }
        conn.inputStream.use { it.readBytes() }
        conn.disconnect()
    }
}
