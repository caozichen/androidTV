package dev.libretv.android

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogErrorTest {
    private val secret = "private-test-token"

    private fun httpFailure(status: Int, body: ByteArray = byteArrayOf()): IOException {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.org/search?token=$secret").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(secret)
            .body(body.toResponseBody())
            .build()
        return response.use { assertThrows(IOException::class.java) { readCatalogResponse(it) } }
    }

    @Test fun rateLimitingAndOtherStatusesExposeOnlyStatusCode() {
        assertEquals("请求过于频繁（HTTP 429），请稍后重试", catalogErrorText(httpFailure(429)))
        assertEquals("点播源返回 HTTP 503，可稍后重试或换源", catalogErrorText(httpFailure(503)))
    }

    @Test fun networkDnsAndTimeoutHideExceptionDetails() {
        assertEquals("网络连接失败，请检查网络后重试", catalogErrorText(ConnectException(secret)))
        assertEquals("无法解析点播源域名，请检查网络和片源地址", catalogErrorText(UnknownHostException(secret)))
        assertEquals("请求超时，请稍后重试或换源", catalogErrorText(SocketTimeoutException(secret)))
    }

    @Test fun malformedSearchAndDetailResponsesDoNotRevealRawJson() {
        val malformed = """{"private":"$secret","list":{"""
        val search = assertThrows(JSONException::class.java) { parseFilms(malformed) }
        val detail = assertThrows(JSONException::class.java) { parseLines(malformed, "5") }
        val expected = "点播源返回的数据格式无效，请检查片源或换源"
        assertEquals(expected, catalogErrorText(search))
        assertEquals(expected, catalogErrorText(detail))
    }

    @Test fun businessErrorSizeLimitAndUnknownFailureHaveSafeMessages() {
        val business = assertThrows(IOException::class.java) {
            parseFilms("""{"code":0,"msg":"$secret"}""")
        }
        assertEquals("点播源返回业务错误，请重试或换源", catalogErrorText(business))
        assertEquals("点播源响应超过 4 MiB，请换源", catalogErrorText(httpFailure(200, ByteArray(4 * 1024 * 1024 + 1))))
        assertEquals("请求失败，请重试或换源", catalogErrorText(IllegalStateException(secret)))
    }
}
