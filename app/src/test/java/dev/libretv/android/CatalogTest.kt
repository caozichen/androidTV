package dev.libretv.android

import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/$name.json")).readText()

    @Test fun queryKeepsEndpointParametersAndEncodesSearchTerm() {
        val catalog = CmsSource("样本", "https://catalog.example.org/vod?tenant=a%2Bb&ac=list&pg=3&ids=9")
        val url = catalogUrl(catalog, mapOf("wd" to "A & 雪", "pg" to "1"))
        assertEquals("a+b", url.queryParameter("tenant"))
        assertEquals("A & 雪", url.queryParameter("wd"))
        assertEquals(listOf("1"), url.queryParameterValues("pg"))
        assertEquals(listOf("videolist"), url.queryParameterValues("ac"))
        assertTrue("ids" !in url.queryParameterNames)
    }

    @Test fun endpointsRequireHttpsWithoutUserInfo() {
        for (url in listOf("http://example.org/vod", "file:///vod", "https://u:p@example.org/vod")) {
            assertThrows(IllegalArgumentException::class.java) { catalogUrl(CmsSource("x", url), emptyMap()) }
        }
    }

    @Test fun searchNormalizesIdsAndRemovesDuplicates() {
        assertEquals(
            listOf(Film("17", "测试短片", "2024"), Film("018", "另一部短片", "")),
            parseFilms(fixture("search")),
        )
    }

    @Test fun emptyResultsAreDistinctFromInvalidResponses() {
        assertTrue(parseFilms("""{"code":1,"list":null}""").isEmpty())
        assertTrue(parseFilms("""{"list":[]}""").isEmpty())
        assertThrows(JSONException::class.java) { parseFilms("""{"list":"unexpected"}""") }
        assertThrows(IOException::class.java) { parseFilms("""{"code":0,"list":[]}""") }
    }

    @Test fun detailPreservesLineNamesAndEpisodeOrder() {
        val lines = parseLines(fixture("detail"), "17")
        assertEquals(listOf("高清", "备用"), lines.map { it.name })
        assertEquals(listOf("第一集", "第二集"), lines.first().episodes.map { it.name })
        assertEquals("https://media.example.org/a/master.m3u8?sig=x\$y", lines.first().episodes.first().url)
        assertEquals(Episode("第 1 集", "https://media.example.org/c.mp4"), lines.last().episodes.single())
    }

    @Test fun mismatchedMovieOrMissingPlaybackReturnsNoLines() {
        assertTrue(parseLines(fixture("detail"), "18").isEmpty())
        assertTrue(parseLines("""{"list":[{"vod_id":"17","vod_play_url":null}]}""", "17").isEmpty())
    }

    @Test fun oversizedChunkedResponseAndHttpFailureAreRejected() {
        fun response(code: Int, size: Int): Response = Response.Builder()
            .request(Request.Builder().url("https://catalog.example.org/vod").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Fixture")
            .body(Buffer().write(ByteArray(size)).asResponseBody(contentLength = -1))
            .build()

        response(200, 4 * 1024 * 1024 + 1).use {
            assertThrows(IOException::class.java) { readCatalogResponse(it) }
        }
        response(429, 0).use {
            assertEquals("点播源返回 HTTP 429", assertThrows(IOException::class.java) { readCatalogResponse(it) }.message)
        }
    }
}
