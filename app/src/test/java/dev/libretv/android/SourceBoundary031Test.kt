package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBoundary031Test {
    private val existing = (1..99).map { "https://s$it.example.org/vod" }.toSet()
    private val alias = """{"name":"相同端点","url":"https://S1.EXAMPLE.ORG:443/vod/"}"""
    private val north = """{"name":"北岸放映室","url":"https://north.example.org/vod"}"""
    private val south = """{"name":"南岸放映室","url":"https://south.example.org/vod"}"""

    @Test fun multibyteFixtureAtOneMibCanBeImportedButOneExtraByteIsRejected() {
        val limit = 1024 * 1024
        val json = "[$north]"
        val padded = json + " ".repeat(limit - json.toByteArray(Charsets.UTF_8).size)
        assertTrue(padded.length < limit)
        val bytes = padded.toByteArray(Charsets.UTF_8)
        assertEquals(limit, bytes.size)
        val read = bytes.inputStream().use(::readSourceText)
        assertEquals("北岸放映室", parseSourceList(read, emptySet()).additions.single().name)
        val error = assertThrows(IllegalArgumentException::class.java) {
            (bytes + 0x20.toByte()).inputStream().use(::readSourceText)
        }
        assertTrue(error.message.orEmpty().contains("1 MiB"))
    }

    @Test fun aliasUsesNoCapacityAndAllNewCandidatesRemainSelectableWhenOverLimit() {
        val preview = parseSourceList("[$alias,$north,$south]", existing)
        assertEquals(1, preview.duplicates.size)
        assertEquals(listOf("北岸放映室", "南岸放映室"), preview.additions.map { it.name })
        assertTrue(preview.overLimit)
        val onlySouth = parseSourceList("[$alias,$south]", existing)
        assertEquals(1, onlySouth.duplicates.size)
        assertEquals("南岸放映室", onlySouth.additions.single().name)
        assertFalse(onlySouth.overLimit)
    }
}
