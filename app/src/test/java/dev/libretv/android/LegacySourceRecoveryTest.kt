package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySourceRecoveryTest {
    @Test fun malformedRowsLeaveValidRowsAndPrivacyIntact() {
        val configuration = """{"sources":[
            {"name":"甲","url":"https://a.example.org/vod"},
            {"name":"坏","url":"http://private.example.org/?token=do-not-log"},
            null,
            {"name":"乙","url":"https://b.example.org/vod"}
        ]}"""
        val result = recoverLegacySources(configuration, setOf("https://b.example.org/vod"))
        assertEquals(listOf("甲", "乙"), result.records.map { it.name })
        assertEquals(listOf(false, true), result.records.map { it.enabled })
        assertEquals(listOf(2, 3), result.failedRows)
        assertTrue(result.warning.contains("2、3"))
        assertFalse(result.warning.contains("private.example.org"))
        assertFalse(result.warning.contains("do-not-log"))
    }

    @Test fun duplicateLegacyKeysKeepTheEnabledOriginalRequestPath() {
        val configuration = """{"sources":[
            {"name":"停用","url":"https://example.org/vod"},
            {"name":"保留","url":"https://example.org/vod/"}
        ]}"""
        val result = recoverLegacySources(configuration, setOf("https://example.org/vod/"))
        assertEquals("https://example.org/vod/", result.records.single().url)
        assertEquals("保留", result.records.single().name)
        assertTrue(result.records.single().enabled)
        assertEquals("", result.warning)
    }

    @Test fun corruptListCanCompleteWithDiagnosticAndFreshInstallHasNoWarning() {
        for (raw in listOf("{", "null", "[]", """{"sources":{}}""", 42)) {
            val result = recoverLegacySources(raw, null)
            assertTrue(result.records.isEmpty())
            assertTrue(result.warning.contains("无法读取"))
        }
        assertEquals("", recoverLegacySources(null, null).warning)
    }

    @Test fun corruptSelectionDoesNotSilentlyEnableAnySource() {
        val raw = """{"sources":[{"name":"甲","url":"https://a.example.org/vod"}]}"""
        val result = recoverLegacySources(raw, listOf("wrong preference type"))
        assertFalse(result.records.single().enabled)
        assertTrue(result.warning.contains("启用状态"))
        assertTrue(recoverLegacySources(raw, null).records.single().enabled)
    }

    @Test fun overCapacityLegacyRowsAreReportedAndDoNotBlockRecovery() {
        val entries = (1..101).joinToString(",") { """{"name":"行$it","url":"https://example.org/$it"}""" }
        val result = recoverLegacySources("""{"sources":[$entries]}""", null)
        assertEquals(100, result.records.size)
        assertEquals(listOf(101), result.failedRows)
    }
}
