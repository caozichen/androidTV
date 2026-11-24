package dev.libretv.android

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceImportTest {
    @Test fun sourceListPreviewsNewDuplicateAndLiveEntries() {
        val input = """{"sources":[
            {"name":"片库","url":"https://CMS.Example.org:443/catalog/","detail":"https://pages.example.org/films/","isAdult":true},
            {"name":"另一个名称","url":"https://cms.example.org/catalog?#"}
          ],"liveSources":[{"url":"https://live.example.org/channels.m3u"}]}"""
        val preview = parseSourceList("\uFEFF$input", emptySet())
        assertEquals(
            listOf(SourceCandidate("片库", "https://cms.example.org/catalog/", "https://pages.example.org/films/", true)),
            preview.additions,
        )
        assertEquals(1, preview.duplicates.size)
        assertEquals(0, preview.invalid.size)
        assertEquals(1, preview.liveCount)
        assertFalse(preview.overLimit)
    }

    @Test fun bareArrayDedupePreservesMeaningfulQueryAndDefaults() {
        val file = """[
          {"url":"https://cms.example.org/vod/?tenant=a%2Bb&part=1"},
          {"url":"https://cms.example.org/vod/?tenant=a%2Bb&part=2"}
        ]"""
        val preview = parseSourceList(file, setOf("https://CMS.example.org:443/vod?tenant=a%2Bb&part=1"))
        assertEquals(1, preview.duplicates.size)
        assertEquals(
            SourceCandidate("cms.example.org", "https://cms.example.org/vod/?tenant=a%2Bb&part=2"),
            preview.additions.single(),
        )
    }

    @Test fun invalidRowsAreIsolatedAndReasonsOmitPrivateAddresses() {
        val file = """[null, {"url":"http://private.example.org/?key=sample-secret"},
          {"url":"https://ok.example.org","detail":"file:///sample-secret"},
          {"url":"https://user:sample-secret@example.org/vod"},
          {"url":"https://good.example.org/vod"}]"""
        val preview = parseSourceList(file, emptySet())
        assertEquals(1, preview.additions.size)
        assertEquals(4, preview.invalid.size)
        assertFalse(preview.invalid.any { "sample-secret" in it || "example.org" in it })
    }

    @Test fun liveOnlyIsAValidPreviewAndOtherFileShapesAreRejected() {
        val live = parseSourceList("""{"liveSources":[{"name":"测试直播"}]}""", emptySet())
        assertEquals(1, live.liveCount)
        assertTrue(live.additions.isEmpty())
        for (wrong in listOf("{", "[]", "{}", """{"name":"LibreTV-Settings","data":{}}""",
            """{"sources":{}}""", """{"sources":[]} trailing""")) {
            assertThrows(IllegalArgumentException::class.java) { parseSourceList(wrong, emptySet()) }
        }
    }

    @Test fun combinedLimitUsesUniqueEndpointsAndRetainsAllOverflowRows() {
        val existing = (1..99).map { "https://example.org/vod/$it" }.toSet()
        val duplicate = """{"url":"https://example.org/vod/1/"}"""
        val first = """{"url":"https://example.org/vod/100"}"""
        val next = """{"url":"https://example.org/vod/101"}"""
        assertFalse(parseSourceList("[$duplicate,$first]", existing).overLimit)
        val preview = parseSourceList("[$duplicate,$first,$next]", existing)
        assertTrue(preview.overLimit)
        assertEquals(2, preview.additions.size)
        assertEquals(1, preview.duplicates.size)
    }

    @Test fun byteLimitAppliesOnStreamAndDirectTextInputs() {
        val limit = 1024 * 1024
        val prefix = """[{"url":"https://sample.example.org/vod"}]"""
        val exact = prefix.padEnd(limit)
        assertEquals(exact, readSourceText(ByteArrayInputStream(exact.toByteArray())))
        assertEquals(1, parseSourceList(exact, emptySet()).additions.size)
        assertThrows(IllegalArgumentException::class.java) {
            readSourceText(ByteArrayInputStream((exact + " ").toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSourceList("汉".repeat(limit / 3 + 1), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            readSourceText(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test fun endpointIdentityMatchesButRequestPathRetainsOriginalSlashAndEncodedQuery() {
        val candidate = manualSource("  我的片库 ", " HTTPS://EXAMPLE.org:443/a///?q=1%2F2 ")
        val file = """[{"name":"我的片库","url":"https://example.org/a?q=1%2F2"}]"""
        val imported = parseSourceList(file, emptySet()).additions.single()
        assertEquals(sourceIdentity(candidate.url), sourceIdentity(imported.url))
        assertEquals("https://example.org/a///?q=1%2F2", candidate.url)
        val request = catalogUrl(CmsSource(candidate.name, candidate.url), mapOf("pg" to "1"))
        assertEquals("/a///", request.encodedPath)
        assertEquals("1/2", request.queryParameter("q"))
        assertThrows(IllegalArgumentException::class.java) { manualSource("测试", "https:/example.org/vod") }
    }

    @Test fun strictGrammarRejectsCommentsTrailingCommasUnquotedKeysAndTrailingValues() {
        for (input in listOf(
            """{sources:[{"url":"https://example.org/vod"}]}""",
            """[{'url':'https://example.org/vod'}]""",
            """[{"url":"https://example.org/vod"},]""",
            """[/*comment*/{"url":"https://example.org/vod"}]""",
            """[{"url":"https://example.org/vod"}] {}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parseSourceList(input, emptySet()) }
        }
    }

    @Test fun invalidRowFlagsAndTypedFieldsDoNotDiscardValidNeighbours() {
        val input = """[
          {"url":"https://example.org/bad1","isAdult":"false"},
          {"url":"https://example.org/bad2","isAdult":1},
          {"url":"https://example.org/bad3","isAdult":null},
          {"url":"https://example.org/bad4","name":42},
          {"url":"https://example.org/bad5","detail":[]},
          {"url":"https://example.org/good","name":null,"isAdult":true}
        ]"""
        val preview = parseSourceList(input, emptySet())
        assertEquals(5, preview.invalid.size)
        assertEquals(3, preview.invalid.count { "isAdult" in it })
        assertEquals(SourceCandidate("example.org", "https://example.org/good", isAdult = true), preview.additions.single())
    }
}
