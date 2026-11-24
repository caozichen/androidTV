package dev.libretv.android

import java.text.Normalizer
import java.util.Locale

// These coordinates only locate an entry in the freshly loaded target details.
internal data class EpisodeMatch(val lineIndex: Int, val episodeIndex: Int)

private const val NUMBER = "[0-9]{1,3}|[一二三四五六七八九十]{1,3}"
private val seasonSuffix = Regex("""(?:第\s*($NUMBER)\s*季|\bseason\s*([0-9]{1,2})|(?<![a-z])s([0-9]{1,2}))$""")
private val cnEpisode = Regex("""第\s*($NUMBER)\s*[集话話]""")
private val enEpisode = Regex("""(?:ep(?:isode)?\.?\s*|e)([0-9]{1,3})""")
private val seasonEpisode = Regex("""s([0-9]{1,2})\s*e([0-9]{1,3})""")
private val yearPattern = Regex("""(?:19|20)[0-9]{2}""")
private val extras = Regex("预告|預告|花絮|先导|先導|幕后|幕後|特辑|特輯|番外|删减|刪減|trailer|teaser|preview|behind the scenes")
private val whitespace = Regex("""\s+""")
private data class WorkTitle(val title: String, val season: Int?)
private data class Segment(val season: Int?, val number: Int?)

private fun normalized(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        .replace(whitespace, " ")

private fun number(text: String): Int? {
    text.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    val digits = "一二三四五六七八九"
    fun digit(value: String): Int? = value.singleOrNull()?.let { digits.indexOf(it) + 1 }?.takeIf { it > 0 }
    if ('十' !in text) return digit(text)
    val parts = text.split('十')
    if (parts.size != 2) return null
    val tens = if (parts[0].isEmpty()) 1 else digit(parts[0]) ?: return null
    val ones = if (parts[1].isEmpty()) 0 else digit(parts[1]) ?: return null
    return tens * 10 + ones
}

private fun workTitle(raw: String): WorkTitle {
    val text = normalized(raw)
    val match = seasonSuffix.find(text) ?: return WorkTitle(text, null)
    val season = number(match.groupValues.drop(1).first { it.isNotEmpty() })
        ?: return WorkTitle(text, null)
    return WorkTitle(text.substring(0, match.range.first).trim(), season)
}

internal fun switchCandidates(origin: FilmHit, hits: List<FilmHit>): List<FilmHit> {
    val title = workTitle(origin.film.title).title
    if (title.isEmpty()) return emptyList()
    return hits.filter { it.source.id != origin.source.id && workTitle(it.film.title).title == title }
        .distinctBy { VideoRef(it.source.id, it.film.id) }
}

private fun segment(title: WorkTitle, line: PlayLine, episode: Episode): Segment? {
    if (!episode.explicitLabel || extras.containsMatchIn(normalized(line.name))) return null
    val label = normalized(episode.name)
    if (label == "正片" || label == "正片完整版") return Segment(title.season, null)
    seasonEpisode.matchEntire(label)?.let {
        val season = number(it.groupValues[1]) ?: return null
        val episodeNumber = number(it.groupValues[2]) ?: return null
        if (title.season != null && title.season != season) return null
        return Segment(season, episodeNumber)
    }
    val explicit = cnEpisode.matchEntire(label) ?: enEpisode.matchEntire(label) ?: return null
    // Without either an explicit season in the title or an SxxExx label, numbered episodes
    // from two catalogs may belong to differently numbered seasons.
    return Segment(title.season ?: return null, number(explicit.groupValues[1]) ?: return null)
}

internal fun suggestSwitchEpisode(
    origin: FilmHit,
    episode: Episode?,
    originLines: List<PlayLine>,
    target: FilmHit,
    refreshedLines: List<PlayLine>,
    candidates: List<FilmHit>,
): EpisodeMatch? {
    if (episode == null || origin.source.id == target.source.id) return null
    val from = workTitle(origin.film.title)
    val to = workTitle(target.film.title)
    if (from.title.isEmpty() || from.title != to.title || extras.containsMatchIn(from.title)) return null
    if (!yearPattern.matches(origin.film.year) || origin.film.year != target.film.year) return null
    if (from.season != null && to.season != null && from.season != to.season) return null
    val equivalent = switchCandidates(origin, candidates).filter {
        val identity = workTitle(it.film.title)
        it.source.id == target.source.id && (it.film.year == target.film.year || !yearPattern.matches(it.film.year)) &&
            (identity.season == to.season || identity.season == null || to.season == null)
    }
    if (equivalent.singleOrNull()?.film?.id != target.film.id) return null

    val originalLine = originLines.singleOrNull { episode in it.episodes } ?: return null
    val wanted = segment(from, originalLine, episode) ?: return null
    if (originLines.sumOf { line -> line.episodes.count { segment(from, line, it) == wanted } } != 1) return null
    var match: EpisodeMatch? = null
    for ((lineIndex, line) in refreshedLines.withIndex()) {
        for ((episodeIndex, candidate) in line.episodes.withIndex()) {
            if (segment(to, line, candidate) != wanted) continue
            if (match != null) return null
            match = EpisodeMatch(lineIndex, episodeIndex)
        }
    }
    return match
}
