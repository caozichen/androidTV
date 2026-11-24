package dev.libretv.android

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal const val SAMPLE = "航拍中国"
internal data class VideoRef(val sourceId: String, val vodId: String)
internal data class EpisodeRef(val video: VideoRef, val lineId: String, val episodeId: String)
internal data class PlaybackSelection(
    val title: String,
    val url: String,
    val ref: EpisodeRef? = null,
    val index: Int = -1,
)
internal data class FilmHit(val source: CmsSource, val film: Film) {
    val ref get() = VideoRef(source.id, film.id)
}

internal class CatalogState(app: Application) : SourceManagerState(app) {
    var managing by mutableStateOf(false)
    var environment by mutableStateOf(false)
    var query by mutableStateOf(SAMPLE)
    var filterAdult by mutableStateOf(true)
        private set
    var searchedTerm by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set
    val updates = mutableStateMapOf<String, SourceSearchUpdate>()
    var searchNumber by mutableIntStateOf(0)
        private set
    val hits by derivedStateOf {
        val exact = normalizeCatalogTitle(searchedTerm)
        updates.values.flatMap { update -> update.films.map { FilmHit(update.source, it) } }
            .sortedByDescending { normalizeCatalogTitle(it.film.title) == exact }
    }
    var detail by mutableStateOf<FilmHit?>(null)
        private set
    var lines by mutableStateOf(emptyList<PlayLine>())
        private set
    var lineIndex by mutableIntStateOf(0)
        private set
    var lastEpisode by mutableStateOf<EpisodeRef?>(null)
        private set
    var detailStatus by mutableStateOf("")
        private set
    var loadingDetail by mutableStateOf(false)
        private set
    var choosingSource by mutableStateOf(false)
    var player by mutableStateOf<PlaybackSelection?>(null)
        private set
    private var request: Job? = null
    private val retries = mutableMapOf<String, Job>()
    private var fullSearchRunning = false
    private var detailRequest: Job? = null
    private var detailGeneration = 0L
    private var lastSearchTime = 0L
    private val detailsCache = DetailCache(SystemClock::elapsedRealtime)
    private var verifiedDetail = false
    private var detailCategory = ""
    private var selectedEpisode: Episode? = null
    private val ttl = 60_000L

    init { refreshSources() }
    override fun onSourcesChanged() {
        stopSearch()
        searchedTerm = ""
        updates.clear()
        notice = ""
        detailsCache.clear()
        lastSearchTime = 0
    }

    fun stopSearch() {
        searchNumber++
        request?.cancel()
        retries.values.toList().forEach { it.cancel() }
        retries.clear()
        fullSearchRunning = false
        if (searching) notice = "已取消搜索"
        searching = false
    }

    fun updateFilter(enabled: Boolean) {
        if (enabled == filterAdult) return
        filterAdult = enabled
        onSourcesChanged()
    }

    fun search(keyword: String = query, refresh: Boolean = false) {
        if (busy) return
        // Public requests always carry the repository's stable source ID.
        val targets = managerRows.filter { it.enabled && (!filterAdult || !it.isAdult) }
            .map { CmsSource(it.name, it.url, it.id) }
            .distinctBy { catalogUrl(it, emptyMap()).toString().let(::sourceIdentity) }
        if (targets.isEmpty()) {
            if (managerRows.none { it.enabled }) managing = true
            else notice = "当前过滤条件下没有可搜索的片源"
            return
        }
        val term = keyword.trim()
        if (term.isEmpty() || term.length > 100) {
            stopSearch()
            updates.clear()
            searchedTerm = ""
            notice = "搜索词应为 1 至 100 个字符"
            return
        }
        query = term
        if (!refresh && term == searchedTerm && lastSearchTime > 0 && SystemClock.elapsedRealtime() - lastSearchTime < ttl) return
        stopSearch()
        val generation = searchNumber
        searchedTerm = term
        lastSearchTime = 0
        updates.clear()
        for (source in targets) updates[source.id] = SourceSearchUpdate(source, emptyList(), 0, false)
        notice = ""
        searching = true
        fullSearchRunning = true
        request = viewModelScope.launch {
            runCatalogSearch(targets, term, filterAdult) { update ->
                if (generation == searchNumber) updates[update.source.id] = update
            }
            if (generation == searchNumber) {
                fullSearchRunning = false
                finishSearch()
            }
        }
    }

    fun sourceOptions(): List<FilmHit> = detail?.let { switchCandidates(it, hits) }.orEmpty()

    fun retrySource(id: String) {
        val previous = updates[id] ?: return
        if (!previous.done || previous.error == null || id in retries) return
        val generation = searchNumber
        val term = searchedTerm
        val filter = filterAdult
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                retryCatalogSource(previous, term, filter, ::runCatalogSearch) { update ->
                    if (generation == searchNumber) updates[id] = update
                }
            } finally {
                if (generation == searchNumber) {
                    retries.remove(id)
                    finishSearch()
                }
            }
        }
        retries[id] = job
        searching = true
        notice = ""
        job.start()
    }

    private fun finishSearch() {
        searching = fullSearchRunning || retries.isNotEmpty()
        if (searching) return
        val failed = updates.values.count { it.error != null }
        val hasPage = updates.values.any { it.pages > 0 }
        notice = when {
            updates.values.any { !it.done } -> "已取消搜索"
            !hasPage && updates.values.all { it.error?.contains("超时") == true } -> "全部片源请求超时"
            !hasPage -> "全部片源请求失败"
            failed > 0 -> "部分请求失败，已保留成功结果"
            hits.isEmpty() -> "未找到匹配影片"
            else -> "搜索完成 · ${hits.size} 个结果"
        }
        if (failed == 0 && updates.values.all { it.done }) lastSearchTime = SystemClock.elapsedRealtime()
    }

    fun open(hit: FilmHit, switch: Boolean = false, refresh: Boolean = false) {
        val old = detail
        val previousEpisode = selectedEpisode
        val previousLines = lines
        val verifiedOrigin = verifiedDetail
        val originCategory = detailCategory
        verifiedDetail = false
        detailCategory = ""
        detailRequest?.cancel()
        val generation = ++detailGeneration
        detail = hit
        lines = emptyList()
        lineIndex = 0
        lastEpisode = null
        selectedEpisode = null
        choosingSource = false
        loadingDetail = true
        detailStatus = "正在加载剧集…"
        detailRequest = viewModelScope.launch {
            try {
                val fresh = detailsCache.load(hit.ref, switch || refresh) { loadCatalogDetail(hit.source, hit.film.id) }
                if (generation != detailGeneration) return@launch
                if (filterAdult && isAdultCategory(fresh.film?.category.orEmpty())) {
                    detailStatus = "此影片已被当前分类过滤"
                    return@launch
                }
                if (fresh.film?.title?.takeIf { it.isNotBlank() }?.let { !sameFilmTitle(it, hit.film.title) } == true ||
                    (fresh.film?.year?.isNotBlank() == true && hit.film.year.isNotBlank() && fresh.film.year != hit.film.year)) {
                    detailStatus = "来源返回的影片信息已变化，请返回搜索重新确认"
                    return@launch
                }
                lines = fresh.lines
                verifiedDetail = fresh.confirms(hit.film)
                detailCategory = fresh.film?.category.orEmpty()
                val targetComplete = updates[hit.source.id]?.let { it.done && it.error == null && !it.truncated } == true
                val match = if (switch && old != null && verifiedOrigin && verifiedDetail && targetComplete) {
                    suggestSwitchEpisode(old, previousEpisode, previousLines, hit, lines, hits, originCategory, detailCategory)
                } else null
                detailStatus = when {
                    lines.isEmpty() -> "此来源暂无可播放的 HTTPS 剧集"
                    match != null -> {
                        lineIndex = match.lineIndex
                        rememberEpisode(match.episodeIndex)
                        "已匹配 ${selectedEpisode?.name}，请确认播放"
                    }
                    switch -> "已切换来源，请选择剧集"
                    else -> ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == detailGeneration) detailStatus = catalogErrorText(e)
            } finally {
                if (generation == detailGeneration) loadingDetail = false
            }
        }
    }

    fun selectLine(index: Int) {
        if (index == lineIndex) return
        lineIndex = index
        selectedEpisode = null
        lastEpisode = null
        if (lines.isNotEmpty()) detailStatus = ""
    }

    fun episodeRef(index: Int): EpisodeRef? {
        val video = detail?.ref ?: return null
        val line = lines.getOrNull(lineIndex) ?: return null
        val ep = line.episodes.getOrNull(index) ?: return null
        val lineKey = if (lines.count { it.name == line.name } == 1) "name:${line.name}" else "index:$lineIndex"
        val epKey = if (ep.explicitLabel && line.episodes.count { it.name == ep.name } == 1) "name:${ep.name}" else "index:$index"
        return EpisodeRef(video, lineKey, epKey)
    }

    private fun rememberEpisode(index: Int) {
        lastEpisode = episodeRef(index)
        selectedEpisode = lines[lineIndex].episodes[index]
    }

    fun play(index: Int) {
        val film = detail?.film ?: return
        val ref = episodeRef(index) ?: return
        rememberEpisode(index)
        val episode = selectedEpisode ?: return
        player = PlaybackSelection("${film.title} · ${lines[lineIndex].episodeLabels()[index]}", episode.url, ref, index)
    }

    fun playControl() {
        player = PlaybackSelection("HLS 对照样片", CONTROL_HLS_URL)
    }

    fun back() {
        when {
            player != null -> player = null
            choosingSource -> choosingSource = false
            managing -> { closeManager(); managing = false }
            environment -> environment = false
            else -> {
                detailGeneration++
                detailRequest?.cancel()
                detail = null
                lines = emptyList()
                selectedEpisode = null
                lastEpisode = null
            }
        }
    }
}
