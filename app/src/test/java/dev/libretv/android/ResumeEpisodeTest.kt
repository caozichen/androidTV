package dev.libretv.android

import org.junit.Assert.*
import org.junit.Test

class ResumeEpisodeTest {
    private val ref = EpisodeRef(VideoRef("source-a", "7"), "name:主线", "name:第二集")
    private fun line(vararg labels: String) = PlayLine("主线", labels.map { Episode(it, "https://example.org/fresh") })

    @Test fun freshOrderCanChangeWithoutChangingEpisodeIdentity() {
        val secondary = PlayLine("备用", listOf(Episode("第二集", "https://example.org/another")))
        assertEquals(EpisodeMatch(1, 0), locateEpisode(listOf(secondary, line("第二集", "第一集")), ref))
    }

    @Test fun missingDuplicateAndIndexOnlyEpisodesRequireManualSelection() {
        assertNull(locateEpisode(listOf(line("第一集")), ref))
        assertNull(locateEpisode(listOf(line("第二集", "第二集")), ref))
        assertNull(locateEpisode(listOf(line("第二集"), line("第二集")), ref))
        assertNull(locateEpisode(listOf(line("第二集")), ref.copy(episodeId = "index:0")))
        assertNull(locateEpisode(listOf(PlayLine("主线", listOf(Episode("第二集", "test", false)))), ref))
    }

    @Test fun unnamedLinesOnlyRestoreWhileSingleAndUnambiguous() {
        val noName = line("第二集").copy(name = "线路 1", explicitName = false)
        val implicit = ref.copy(lineId = "single")
        assertEquals(EpisodeMatch(0, 0), locateEpisode(listOf(noName), implicit))
        assertNull(locateEpisode(listOf(noName, line("第二集")), implicit))
        assertNull(locateEpisode(listOf(noName), ref.copy(lineId = "name:线路 1")))
    }

    @Test fun aReusedFilmIdOrDifferentYearMustNotInheritProgress() {
        val original = Film("7", "A+B", "2025")
        assertFalse(original.sameHistoryFilm(original.copy(title = "A-B")))
        assertFalse(original.sameHistoryFilm(original.copy(year = "2024")))
        assertFalse(original.sameHistoryFilm(original.copy(id = "8")))
        assertTrue(original.sameHistoryFilm(original.copy(title = "Ａ＋Ｂ")))
    }

    @Test fun freshDetailsSupplyYearAndStoredClassificationForANewWatch() {
        val summary = Film("77", "测试专辑", "", "合集")
        val detail = summary.copy(year = "2024", category = "Adult")
        val canonical = summary.withVerifiedDetail(detail)!!
        assertEquals("2024", canonical.year)
        assertEquals("Adult", canonical.category)
        assertTrue(canonical.acceptsWatchRecord(detail, explicitSelection = false))
        assertEquals("Adult", detail.withVerifiedDetail(detail.copy(category = "合集"))!!.category)
    }

    @Test fun aMissingYearRequiresManualSelectionToReuseAnOldCheckpoint() {
        val current = Film("99", "Series+", "2024")
        val legacy = current.copy(year = "")
        assertFalse(current.acceptsWatchRecord(legacy, explicitSelection = false))
        assertTrue(current.acceptsWatchRecord(legacy, explicitSelection = true))
        assertFalse(current.acceptsWatchRecord(legacy.copy(title = "Series-"), explicitSelection = true))
        assertFalse(current.acceptsWatchRecord(legacy.copy(id = "different"), explicitSelection = true))
        assertFalse(current.acceptsWatchRecord(current.copy(year = "2025"), explicitSelection = true))
        assertNull(current.withVerifiedDetail(current.copy(year = "2025")))
        assertNull(current.withVerifiedDetail(current.copy(id = "another")))
        assertNull(current.withVerifiedDetail(current.copy(title = "Series-")))
    }
}
