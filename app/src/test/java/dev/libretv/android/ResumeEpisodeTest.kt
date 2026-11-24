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
}
