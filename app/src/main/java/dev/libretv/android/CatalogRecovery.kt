package dev.libretv.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class DetailCache(private val now: () -> Long) {
    private val entries = linkedMapOf<VideoRef, Pair<Long, CatalogDetail>>()

    fun clear() = entries.clear()

    suspend fun load(ref: VideoRef, refresh: Boolean, fetch: suspend () -> CatalogDetail): CatalogDetail {
        val entry = entries[ref]
        if (!refresh && entry != null && now() - entry.first < 60_000L) return entry.second
        val result = fetch()
        currentCoroutineContext().ensureActive()
        entries.remove(ref)
        entries[ref] = now() to result
        if (entries.size > 12) entries.remove(entries.keys.first())
        return result
    }
}

internal suspend fun retryCatalogSource(
    previous: SourceSearchUpdate, term: String, filterAdult: Boolean,
    run: suspend (List<CmsSource>, String, Boolean, (SourceSearchUpdate) -> Unit) -> Unit,
    onUpdate: (SourceSearchUpdate) -> Unit,
) {
    onUpdate(previous.copy(done = false, error = null))
    run(listOf(previous.source), term, filterAdult) { fresh ->
        val update = if (fresh.done && fresh.error == null) fresh else {
            val retained = (fresh.films + previous.films).distinctBy { it.id }
            fresh.copy(films = retained, pages = maxOf(previous.pages, fresh.pages),
                truncated = previous.truncated || fresh.truncated)
        }
        onUpdate(update)
    }
}
