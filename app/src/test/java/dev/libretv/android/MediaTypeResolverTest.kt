package dev.libretv.android

import androidx.media3.common.MimeTypes
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeResolverTest {
    private val unexpected = Call.Factory { error("Unexpected network request") }

    @Test fun knownPathExtensionsAndLocalAssetsNeedNoProbe() = runBlocking {
        assertEquals(MimeTypes.APPLICATION_M3U8, resolvePlaybackMimeType("https://example.org/live.M3U8?name=movie.mp4", unexpected))
        assertEquals(MimeTypes.APPLICATION_M3U8, resolvePlaybackMimeType("asset:///control/index.m3u8", unexpected))
        for (path in listOf("asset:///example", "https://example.org/clip.MP4?other=.m3u8", "https://example.org/live.mpd", "https://example.org/live.ism/Manifest", "http://example.org/player.php")) {
            assertNull(resolvePlaybackMimeType(path, unexpected))
        }
    }

    private suspend fun probe(path: String, mime: String, body: String, status: Int = 200): String? {
        val calls = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("bytes=0-15", request.header("Range"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").header("Content-Type", mime)
                .body(body.toResponseBody()).build()
        }.build()
        return resolvePlaybackMimeType("https://example.org/$path", calls)
    }

    @Test fun ambiguousPathsUseContentEvidence() = runBlocking {
        assertEquals(MimeTypes.APPLICATION_M3U8, probe("view.php?name=movie.mp4", "application/octet-stream", "\uFEFF#EXTM3U\r\n#EXT-X-VERSION:3"))
        assertEquals(MimeTypes.APPLICATION_M3U8, probe("relay", "Application/Vnd.Apple.Mpegurl; charset=utf-8", ""))
        assertEquals(MimeTypes.APPLICATION_M3U8, probe("relay", "application/x-mpegurl", ""))
        for (mime in listOf("audio/mpegurl", "audio/x-mpegurl", "application/mpegurl", "text/plain")) {
            assertEquals(MimeTypes.APPLICATION_M3U8, probe("relay", mime, "#EXTM3U\n#EXT-X-TARGETDURATION:5"))
            assertNull(probe("relay", mime, "song.mp3\n"))
        }
    }

    @Test fun misleadingQueryInvalidHeaderAndHttpFailureKeepNativeRouting() = runBlocking {
        assertNull(probe("relay?name=movie.m3u8", "video/mp4", "\u0000\u0000\u0000\u0018ftypmp42"))
        assertNull(probe("relay?name=movie.m3u8", "text/plain", "#EXTM3U-NOT-A-PLAYLIST\n"))
        assertNull(probe("relay", "text/html", "<p>#EXTM3U</p>"))
        assertNull(probe("relay", "application/vnd.apple.mpegurl", "#EXTM3U\n", status = 403))
        val failing = OkHttpClient.Builder().addInterceptor { throw IOException("private-token") }.build()
        assertNull(resolvePlaybackMimeType("https://example.org/private?secret=value", failing))
    }

    @Test fun leavingScreenCancelsOutstandingCallAndDiscardsLateResponse() = runBlocking {
        lateinit var callback: Callback
        lateinit var probeCall: Call
        val calls = Call.Factory { request: Request ->
            val delegate = OkHttpClient().newCall(request)
            object : Call by delegate {
                override fun enqueue(responseCallback: Callback) {
                    callback = responseCallback
                    probeCall = this
                }
            }
        }
        var applied = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            resolvePlaybackMimeType("https://example.org/relay", calls)
            applied = true
        }
        job.cancelAndJoin()
        assertTrue(probeCall.isCanceled())
        callback.onFailure(probeCall, IOException("cancelled"))
        assertFalse(applied)
    }
}
