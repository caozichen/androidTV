package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchSourceTest {
    private fun hit(source: String, id: String, title: String, year: String = "2022") =
        FilmHit(CmsSource(source, "https://$source.example.org/vod", source), Film(id, title, year))

    private val a = hit("a", "42", "航拍中国 第四季")
    private val b = hit("b", "42", "航拍中国 S04")
    private val selected = Episode("第八集", "https://a.example.org/8.m3u8")
    private val otherEight = Episode("EP08", "https://b.example.org/8.m3u8")
    private fun line(vararg episodes: Episode, name: String = "线路一") = PlayLine(name, episodes.toList())

    private fun match(
        source: FilmHit = a, target: FilmHit = b, original: Episode = selected,
        before: List<PlayLine> = listOf(line(original)),
        after: List<PlayLine> = listOf(line(otherEight)),
        candidates: List<FilmHit> = listOf(target),
    ) = suggestSwitchEpisode(source, original, before, target, after, candidates)

    @Test fun candidatesKeepIndependentIdsAndRejectUnrelatedTitles() {
        val third = hit("c", "42", "航拍中国 第四季")
        val renamedSourceResult = hit("a", "88", "航拍中国 第四季")
        val otherTitle = hit("d", "42", "航拍中国幕后故事")
        assertEquals(listOf(b, third), switchCandidates(a, listOf(a, b, b, third, renamedSourceResult, otherTitle)))
    }

    @Test fun uniqueExplicitEpisodeMatchesFreshTargetPosition() {
        val refreshed = listOf(
            line(otherEight, name = "预告片"),
            line(otherEight.copy(name = "第九集"), otherEight),
        )
        assertEquals(EpisodeMatch(1, 1), match(after = refreshed))
        assertEquals(EpisodeMatch(0, 0), match(after = listOf(line(otherEight.copy(name = "Ｓ０４Ｅ０８")))))
    }

    @Test fun numericIdAloneCannotAlignDifferentWorksYearsOrSeasons() {
        assertNull(match(target = hit("b", "42", "中国地理 第四季")))
        assertNull(match(target = hit("b", "42", "航拍中国 第四季", "2021")))
        assertNull(match(target = hit("b", "42", "航拍中国 第三季")))
        assertNull(match(target = a))
        assertNull(match(target = hit("b", "42", "航拍中国 第四季", "")))
        assertNull(match(after = listOf(line(otherEight.copy(name = "S03E08")))))
    }

    @Test fun unknownSeasonRequiresExplicitSeasonOnBothEpisodeLabels() {
        val a0 = hit("a", "11", "航拍中国")
        val b0 = hit("b", "88", "航拍中国")
        assertNull(match(source = a0, target = b0))
        assertEquals(
            EpisodeMatch(0, 0),
            match(source = a0, target = b0, original = selected.copy(name = "S4E8"),
                after = listOf(line(otherEight.copy(name = "s04e08")))),
        )
    }

    @Test fun ambiguousWorkOrEpisodeRequiresManualSelection() {
        assertNull(match(candidates = listOf(b, hit("b", "99", "航拍中国 第四季"))))
        assertNull(match(candidates = listOf(b, hit("b", "99", "航拍中国 第四季", "未知"))))
        assertNull(match(after = listOf(line(otherEight), line(otherEight, name = "线路二"))))
        assertNull(match(before = listOf(line(selected, selected.copy(name = "EP08", url = "https://a.example.org/revised.m3u8")))))
        assertNull(match(candidates = emptyList()))
    }

    @Test fun missingSyntheticOrExtraEpisodesDoNotMatch() {
        assertNull(match(after = listOf(line(otherEight.copy(name = "第七集"), otherEight.copy(name = "第九集")))))
        assertNull(match(original = selected.copy(explicitLabel = false)))
        assertNull(match(after = listOf(line(otherEight.copy(explicitLabel = false)))))
        for (label in listOf("第8集 预告", "第8集上", "花絮", "正片", "8", "第0集")) {
            assertNull(match(after = listOf(line(otherEight.copy(name = label)))))
        }
        assertNull(match(before = listOf(line(selected, name = "预告片"))))
    }

    @Test fun uniqueFeatureCanMatchButTrailerAndGenericQualityLabelsCannot() {
        val a0 = hit("a", "120", "无名", "2023")
        val b0 = hit("b", "987", "无名", "2023")
        val feature = selected.copy(name = "正片")
        val trailer = otherEight.copy(name = "预告")
        assertEquals(EpisodeMatch(0, 1), match(a0, b0, feature, after = listOf(line(trailer, otherEight.copy(name = "正片")))))
        assertNull(match(a0, b0, feature, after = listOf(line(trailer))))
        assertNull(match(a0, b0, feature, after = listOf(line(otherEight.copy(name = "HD")))))
        assertNull(match(a0, b0, feature, after = listOf(line(otherEight.copy(name = "正片"), name = "预告片"))))
    }
}
