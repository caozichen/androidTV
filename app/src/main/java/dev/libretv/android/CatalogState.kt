package dev.libretv.android

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
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
    val watch: HistorySession? = null,
    val startMs: Long = 0,
    val initiallyPaused: Boolean = false,
)
internal data class FilmHit(val source: CmsSource, val film: Film) {
    val ref get() = VideoRef(source.id, film.id)
}

internal class CatalogState(app: Application, private val savedState: SavedStateHandle) : SourceManagerState(app) {
    private val history = HistoryStore.open(app)
    private var latestDetail by mutableStateOf<WatchRecord?>(null)
    fun homeHistory(filter: Boolean, limit: Int) = history.recent(filter, limit)
    fun episodeHistory(video: VideoRef) = history.episodes(video)
    fun historyVisible(entry: WatchRecord): Boolean {
        if (!filterAdult) return true
        val source = managerRows.firstOrNull { it.id == entry.sourceId }
        return !entry.categoryAdult && source?.isAdult != true && (entry.sourceAdult ?: source?.isAdult) == false
    }
    private var playRequest: Job? = null
    private var historyResume: EpisodeRef? = null
    private var resumePaused = false
    private var manuallyChosenLine = false
    private var pendingEpisodeChoice by mutableStateOf(false)
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

    init {
        refreshSources()
        viewModelScope.launch {
            snapshotFlow { detail?.ref?.takeIf { player == null } }.collectLatest { video ->
                latestDetail = null
                if (video != null) history.latest(video).collect { latestDetail = it }
            }
        }
        val parts = savedState.get<ArrayList<String>>("playingRef")
        if (parts?.size == 4) viewModelScope.launch {
            snapshotFlow { busy }.first { !it }
            val ref = EpisodeRef(VideoRef(parts[0], parts[1]), parts[2], parts[3])
            val entry = history.progress(ref) ?: history.latest(ref.video).first()
            savedState.remove<ArrayList<String>>("playingRef")
            if (entry != null && detail == null && !managing && !environment) openHistory(entry, ref, true)
        }
    }

    fun deleteHistory(video: VideoRef) { viewModelScope.launch { history.delete(video) } }
    fun clearHistory() { viewModelScope.launch { history.clear() } }
    fun clearEpisode(ref: EpisodeRef) {
        history.deleteEpisode(ref)
        if (lastEpisode == ref) {
            lastEpisode = null
            selectedEpisode = null
        }
        if (historyResume == ref) {
            historyResume = null
            pendingEpisodeChoice = false
        }
    }

    fun openHistory(entry: WatchRecord, ref: EpisodeRef = entry.ref, paused: Boolean = false) {
        val source = managerRows.find { it.id == entry.sourceId }
        if (source == null || !source.enabled || !historyVisible(entry)) {
            notice = when {
                source == null -> "片源已移除，请在点播源管理重新添加原端点"
                !source.enabled -> "片源已停用，请在点播源管理启用后继续观看"
                else -> "当前分类过滤已隐藏此片源或影片"
            }
            return
        }
        val hit = FilmHit(CmsSource(source.name, source.url, source.id), Film(entry.vodId, entry.title, entry.year, entry.category))
        open(hit, refresh = true, resume = ref, paused = paused)
    }
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

    fun chooseOtherSource() {
        val hit = detail ?: return
        if (player != null) back()
        if (updates.isEmpty() || (historyResume != null && !sameFilmTitle(searchedTerm, hit.film.title))) {
            search(hit.film.title, refresh = true)
        }
        choosingSource = true
    }

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

    fun open(hit: FilmHit, switch: Boolean = false, refresh: Boolean = false, resume: EpisodeRef? = null, paused: Boolean = false) {
        playRequest?.cancel()
        historyResume = resume
        resumePaused = paused
        manuallyChosenLine = false
        pendingEpisodeChoice = false
        val old = detail
        val previousEpisode = selectedEpisode
        val previousLines = lines
        val verifiedOrigin = verifiedDetail
        val originCategory = detailCategory
        verifiedDetail = false
        sameSourceVerified = false
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
                val canonical = hit.film.withVerifiedDetail(fresh.film)
                if (canonical != null) {
                    // Retain the original search category for the cross-source trailer checks.
                    detail = hit.copy(film = canonical.copy(category = hit.film.category.ifBlank { canonical.category }))
                }
                sameSourceVerified = canonical != null && fresh.film?.sameHistoryFilm(canonical) == true
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
                if (resume != null && lines.isNotEmpty()) {
                    val confirmed = canonical != null && canonical.sameHistoryFilm(hit.film)
                    val location = if (sameSourceVerified && confirmed) locateEpisode(lines, resume) else null
                    if (location != null) {
                        lineIndex = location.lineIndex
                        play(location.episodeIndex, paused, explicitSelection = false)
                    } else {
                        pendingEpisodeChoice = true
                        detailStatus = "上次剧集无法唯一确认，请重新选集"
                    }
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

    fun reloadDetail(hit: FilmHit) = open(hit, refresh = true, resume = historyResume, paused = resumePaused)

    private var sameSourceVerified = false

    fun detailHistory(): WatchRecord? {
        val hit = detail ?: return null
        if (manuallyChosenLine) return null
        return latestDetail?.takeIf { it.ref.video == hit.ref && historyVisible(it) &&
            hit.film.sameHistoryFilm(Film(it.vodId, it.title, it.year)) }
    }

    fun nextAfterCompletion(): EpisodeMatch? {
        if (requiresEpisodeSelection()) return null
        val previous = detailHistory()?.takeIf { it.completed } ?: return null
        if (!sameSourceVerified || (lastEpisode != null && lastEpisode != previous.ref)) return null
        val current = locateEpisode(lines, previous.ref) ?: return null
        val nextIndex = current.episodeIndex + 1
        return if (nextIndex in lines[current.lineIndex].episodes.indices) EpisodeMatch(current.lineIndex, nextIndex) else null
    }

    fun playNextAfterCompletion() {
        val next = nextAfterCompletion() ?: return
        lineIndex = next.lineIndex
        play(next.episodeIndex, explicitSelection = false)
    }

    fun requiresEpisodeSelection(): Boolean {
        if (pendingEpisodeChoice) return true
        val ref = lastEpisode ?: detailHistory()?.ref ?: return false
        return !sameSourceVerified || locateEpisode(lines, ref) == null
    }

    fun primaryPlay() {
        if (requiresEpisodeSelection()) return
        val preferred = lastEpisode
        val previous = detailHistory()
        val ref = preferred ?: previous?.ref
        if (ref == null) { play(0, explicitSelection = false); return }
        val loc = if (sameSourceVerified) locateEpisode(lines, ref) else null
        if (loc == null) {
            detailStatus = "上次剧集无法唯一确认，请重新选集"
            return
        }
        lineIndex = loc.lineIndex
        play(loc.episodeIndex, explicitSelection = false)
    }

    fun selectLine(index: Int) {
        if (index == lineIndex) return
        playRequest?.cancel()
        manuallyChosenLine = true
        lineIndex = index
        selectedEpisode = null
        lastEpisode = null
        if (lines.isNotEmpty()) detailStatus = ""
    }

    fun episodeRef(index: Int): EpisodeRef? {
        val video = detail?.ref ?: return null
        val line = lines.getOrNull(lineIndex) ?: return null
        val ep = line.episodes.getOrNull(index) ?: return null
        val lineKey = when {
            line.explicitName && lines.count { it.name == line.name } == 1 -> "name:${line.name}"
            !line.explicitName && lines.size == 1 -> "single"
            else -> "index:$lineIndex"
        }
        val epKey = if (ep.explicitLabel && line.episodes.count { it.name == ep.name } == 1) "name:${ep.name}" else "index:$index"
        return EpisodeRef(video, lineKey, epKey)
    }

    private fun rememberEpisode(index: Int) {
        lastEpisode = episodeRef(index)
        selectedEpisode = lines[lineIndex].episodes[index]
    }

    fun play(index: Int, paused: Boolean = false, explicitSelection: Boolean = true) {
        val hit = detail ?: return
        val film = hit.film
        val ref = episodeRef(index) ?: return
        rememberEpisode(index)
        val episode = selectedEpisode ?: return
        val line = lines[lineIndex]
        val label = line.episodeLabels()[index]
        val category = if (isAdultCategory(film.category)) film.category else detailCategory.ifBlank { film.category }
        val meta = WatchRecord(ref.video.sourceId, ref.video.vodId, ref.lineId, ref.episodeId,
            hit.source.name, film.title, film.year, category, line.name, label,
            sourceAdult = managerRows.find { it.id == ref.video.sourceId }?.isAdult)
        playRequest?.cancel()
        playRequest = viewModelScope.launch {
            val previous = if (sameSourceVerified && locateEpisode(lines, ref) != null) history.progress(ref) else null
            val savedFilm = previous?.let { Film(it.vodId, it.title, it.year) }
            if (!explicitSelection && savedFilm != null && !film.sameHistoryFilm(savedFilm) &&
                film.acceptsWatchRecord(savedFilm, explicitSelection = true)) {
                pendingEpisodeChoice = true
                detailStatus = "上次剧集无法唯一确认，请重新选集"
                return@launch
            }
            val start = if (previous != null && !previous.completed && savedFilm != null &&
                film.acceptsWatchRecord(savedFilm, explicitSelection)) previous.positionMs else 0L
            pendingEpisodeChoice = false
            player = PlaybackSelection("${film.title} · $label", episode.url, ref, index, history.begin(meta), start, paused)
            savedState["playingRef"] = arrayListOf(ref.video.sourceId, ref.video.vodId, ref.lineId, ref.episodeId)
        }
    }

    fun checkpoint(selection: PlaybackSelection, positionMs: Long, durationMs: Long, completed: Boolean) {
        selection.watch?.let { history.save(it, positionMs, durationMs, completed) }
    }

    suspend fun refreshEpisodeUrl(selection: PlaybackSelection): String? {
        val ref = selection.ref ?: return null
        val hit = detail ?: return null
        if (selection.watch != player?.watch || ref.video != hit.ref) return null
        val fresh = detailsCache.load(hit.ref, true) { loadCatalogDetail(hit.source, ref.video.vodId) }
        if (selection.watch != player?.watch || detail?.ref != ref.video) return null
        val safeFilm = fresh.film?.sameHistoryFilm(hit.film) == true && !(filterAdult && isAdultCategory(fresh.film.category))
        val match = if (safeFilm) locateEpisode(fresh.lines, ref) else null
        if (match == null) {
            back()
            lines = if (safeFilm) fresh.lines else emptyList()
            lineIndex = 0
            lastEpisode = null
            selectedEpisode = null
            historyResume = null
            verifiedDetail = safeFilm && fresh.confirms(hit.film)
            sameSourceVerified = safeFilm
            pendingEpisodeChoice = safeFilm
            detailCategory = fresh.film?.category.orEmpty()
            detailStatus = if (safeFilm) "来源的剧集已变化，请重新选集" else "来源的影片信息已变化，请返回搜索重新确认"
            return null
        }
        lines = fresh.lines
        lineIndex = match.lineIndex
        rememberEpisode(match.episodeIndex)
        val resolvedUrl = lines[lineIndex].episodes[match.episodeIndex].url
        player = selection.copy(index = match.episodeIndex, url = resolvedUrl)
        return resolvedUrl
    }

    fun playControl() {
        player = PlaybackSelection("HLS 对照样片", CONTROL_HLS_URL)
    }

    fun back() {
        playRequest?.cancel()
        savedState.remove<ArrayList<String>>("playingRef")
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
