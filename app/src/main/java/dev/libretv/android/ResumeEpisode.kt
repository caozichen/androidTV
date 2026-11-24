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
