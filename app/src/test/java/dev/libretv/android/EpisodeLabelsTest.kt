package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeLabelsTest {
    private fun line(vararg names: String) = PlayLine("线路", names.mapIndexed { index, name ->
        Episode(name, "https://example.org/$index.m3u8")
    })

    @Test fun labelsStayUniqueWhenSourceSuppliesItsOwnNumbering() {
        val episodes = line("正片", "正片", "正片（第 1 项）", "1 · 正片")
        assertEquals(listOf("1 · 正片", "2 · 正片", "3 · 正片（第 1 项）", "4 · 1 · 正片"), episodes.episodeLabels())
        assertEquals("https://example.org/2.m3u8", episodes.episodes[2].url)
    }

    @Test fun ordinaryNamesAndSimpleDuplicatesKeepTheirExistingLabels() {
        assertEquals(listOf("序", "正片（第 2 项）", "正片（第 3 项）"),
            line("序", "正片", "正片").episodeLabels())
        assertEquals(listOf("上", "下"), line("上", "下").episodeLabels())
        assertEquals(emptyList<String>(), line().episodeLabels())
    }
}
