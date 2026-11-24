package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogIdentity031Test {
    private val origin = FilmHit(CmsSource("片库甲", "https://a.example.org", "a"), Film("41", "海上＋陆地", "2025", "剧情片"))
    private val target = FilmHit(CmsSource("片库乙", "https://b.example.org", "b"), Film("92", "海上+陆地", "2025", "剧情片"))
    private val selected = Episode("正片", "https://a.example.org/full.m3u8")
    private val before = listOf(PlayLine("主线路", listOf(selected)))
    private val after = listOf(PlayLine("主线路", listOf(Episode("正片", "https://b.example.org/full.m3u8"))))

    private fun suggestion(a: FilmHit = origin, b: FilmHit = target, aCategory: String = "", bCategory: String = "") =
        suggestSwitchEpisode(a, selected, before, b, after, listOf(b), aCategory, bCategory)

    @Test fun trailerClassificationFromEitherSearchOrFreshDetailsRequiresManualEpisodeChoice() {
        assertEquals(EpisodeMatch(0, 0), suggestion(aCategory = "剧情", bCategory = "电影"))
        assertNull(suggestion(a = origin.copy(film = origin.film.copy(category = "预告片")), aCategory = "剧情片"))
        assertNull(suggestion(b = target.copy(film = target.film.copy(category = "预告片")), bCategory = "剧情片"))
        assertNull(suggestion(aCategory = "預告片"))
        assertNull(suggestion(bCategory = "Trailers"))
    }

    @Test fun verificationKeepsSemanticSymbolsEvenWhenSearchRankingTreatsTitlesAsEqual() {
        val original = Film("901", "海上 + 陆地", "2025")
        fun confirms(title: String) = CatalogDetail(original.copy(title = title), emptyList()).confirms(original)
        assertTrue(confirms("　海上   ＋　陆地  "))
        assertFalse(confirms("海上 # 陆地"))
        assertFalse(confirms("海上 陆地"))
        assertFalse(sameFilmTitle("系列²", "系列2"))
        assertEquals(normalizeCatalogTitle(original.title), normalizeCatalogTitle("海上 # 陆地"))
    }

    @Test fun candidateIdentityDoesNotBroadenWithSearchPunctuationFolding() {
        val different = target.copy(film = target.film.copy(title = "海上#陆地"))
        assertEquals(listOf(target), switchCandidates(origin, listOf(target, different)))
        assertNull(suggestion(b = different))
    }
}
