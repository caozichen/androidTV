package dev.libretv.android

// Index-only references identify the current selection, but cannot prove identity after a refresh.
internal fun locateEpisode(lines: List<PlayLine>, ref: EpisodeRef): EpisodeMatch? {
    if (!ref.episodeId.startsWith("name:")) return null
    val line = when {
        ref.lineId == "single" -> if (lines.size == 1 && !lines[0].explicitName) 0 else return null
        ref.lineId.startsWith("name:") -> lines.indices.singleOrNull {
            lines[it].explicitName && lines[it].name == ref.lineId.removePrefix("name:")
        } ?: return null
        else -> return null
    }
    val episodeName = ref.episodeId.removePrefix("name:")
    val episodes = lines[line].episodes
    val index = episodes.indices.singleOrNull { episodes[it].name == episodeName } ?: return null
    return if (episodes[index].explicitLabel) EpisodeMatch(line, index) else null
}

internal fun Film.sameHistoryFilm(other: Film): Boolean =
    id == other.id && title.isNotBlank() && sameFilmTitle(title, other.title) && year == other.year

// A summary or old watch record can omit metadata that is present in the current details.
internal fun Film.withVerifiedDetail(fresh: Film?): Film? {
    if (fresh == null || id != fresh.id || fresh.title.isBlank() || !sameFilmTitle(title, fresh.title)) return null
    if (year.isNotBlank() && fresh.year.isNotBlank() && year != fresh.year) return null
    val watchCategory = if (isAdultCategory(category)) category else fresh.category.ifBlank { category }
    return copy(year = fresh.year.ifBlank { year }, category = watchCategory)
}

internal fun Film.acceptsWatchRecord(saved: Film, explicitSelection: Boolean): Boolean =
    sameHistoryFilm(saved) || (explicitSelection && saved.year.isBlank() && saved.id == id &&
        title.isNotBlank() && sameFilmTitle(title, saved.title))
