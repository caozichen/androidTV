package dev.libretv.android

import androidx.media3.common.MimeTypes
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val PREFIX_BYTES = 16L
private val probeClient = OkHttpClient.Builder()
    .callTimeout(3, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

private val nativeExtensions = setOf(
    "mp4", "m4v", "m4a", "mov", "3gp", "3g2", "mkv", "webm", "mpd", "ism", "isml",
    "ts", "m2ts", "mts", "mp3", "aac", "ac3", "ac4", "ec3", "flac", "wav", "ogg", "oga",
    "ogv", "opus", "mpg", "mpeg", "m2p", "ps", "flv", "avi", "amr", "awb", "vob",
)

suspend fun resolvePlaybackMimeType(url: String): String? = resolvePlaybackMimeType(url, probeClient)

internal suspend fun resolvePlaybackMimeType(url: String, calls: Call.Factory): String? {
    currentCoroutineContext().ensureActive()
    val remote = url.toHttpUrlOrNull()
    val path = remote?.encodedPath ?: url.substringBefore('#').substringBefore('?')
    val name = path.substringAfterLast('/').lowercase()
    val extension = name.substringAfterLast('.', "")
    if (extension == "m3u8") return MimeTypes.APPLICATION_M3U8
    if (remote == null || !remote.isHttps || remote.username.isNotEmpty() || remote.password.isNotEmpty()) return null
    if (extension in nativeExtensions || path.contains(".ism/manifest", ignoreCase = true) ||
        path.contains(".isml/manifest", ignoreCase = true)) return null

    val request = Request.Builder().url(remote)
        .header("Range", "bytes=0-${PREFIX_BYTES - 1}")
        .header("Accept-Encoding", "identity")
        .build()
    return suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val type = try {
                    response.use(::probeResponse)
                } catch (_: IOException) {
                    null
                }
                continuation.resume(type)
            }
        })
    }
}

private fun probeResponse(response: Response): String? {
    if (!response.isSuccessful || response.body == null) return null
    val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
    if (mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl") {
        return MimeTypes.APPLICATION_M3U8
    }
    // Generic playlist and binary MIME types require an actual extended playlist header.
    val prefix = response.peekBody(PREFIX_BYTES).bytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
    val firstLine = prefix.takeWhile { it != '\r' && it != '\n' }
    return if (firstLine == "#EXTM3U") MimeTypes.APPLICATION_M3U8 else null
}
