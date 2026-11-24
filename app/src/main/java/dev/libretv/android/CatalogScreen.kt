package dev.libretv.android

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val SAMPLE = "航拍中国"
internal data class FilmHit(val source: CmsSource, val film: Film)

internal class CatalogState(app: Application) : SourceManagerState(app) {
    val sources get() = managerRows.map { CmsSource(it.name, it.url) }
    val selected get() = managerRows.filter { it.enabled }.map { CmsSource(it.name, it.url) }.toSet()
    var managing by mutableStateOf(false)
    var query by mutableStateOf(SAMPLE)
    var notice by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set
    val groups = mutableStateMapOf<CmsSource, List<Film>>()
    val errors = mutableStateMapOf<CmsSource, String>()
    var detail by mutableStateOf<FilmHit?>(null)
        private set
    var lines by mutableStateOf(emptyList<PlayLine>())
        private set
    var lineIndex by mutableIntStateOf(0)
    var detailStatus by mutableStateOf("")
        private set
    var player by mutableStateOf<Pair<String, String>?>(null)
    var environment by mutableStateOf(false)
    private var request: Job? = null
    private var detailsRequest: Job? = null
    init { refreshSources() }
    override fun onSourcesChanged() = clearSearch()

    private fun clearSearch() {
        request?.cancel()
        searching = false
        groups.clear()
        errors.clear()
        notice = ""
    }

    fun search(keyword: String = query) {
        if (busy) return
        if (selected.isEmpty()) {
            managing = true
            return
        }
        clearSearch()
        query = keyword.trim()
        if (query.isBlank() || query.length > 100) {
            notice = "搜索词应为 1 至 100 个字符"
            return
        }
        val term = query
        val targets = sources.filter { it in selected }
        searching = true
        request = viewModelScope.launch {
            coroutineScope {
                for (source in targets) launch {
                    try {
                        groups[source] = searchCatalog(source, term)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors[source] = catalogErrorText(e)
                    }
                }
            }
            searching = false
            notice = if (groups.values.all { it.isEmpty() } && errors.isEmpty()) "未找到匹配影片" else ""
        }
    }

    fun open(hit: FilmHit) {
        detailsRequest?.cancel()
        detail = hit
        lines = emptyList()
        lineIndex = 0
        detailStatus = "正在加载剧集…"
        detailsRequest = viewModelScope.launch {
            try {
                lines = loadLines(hit.source, hit.film.id)
                detailStatus = if (lines.isEmpty()) "此来源暂无可播放的 HTTPS 剧集" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailStatus = catalogErrorText(e)
            }
        }
    }

    fun back() {
        when {
            player != null -> player = null
            managing -> { closeManager(); managing = false }
            environment -> environment = false
            else -> {
                detailsRequest?.cancel()
                detail = null
            }
        }
    }
}

@Composable
internal fun CatalogScreen(state: CatalogState) {
    val homeScroll = rememberLazyListState()
    val detailScroll = key(state.detail) { rememberLazyListState() }
    val player = state.player
    val hit = state.detail
    BackHandler(enabled = player == null && (hit != null || state.environment), onBack = state::back)
    when {
        player != null -> PlaybackScreen(title = player.first, url = player.second, onBack = state::back)
        state.managing -> SourceManagerScreen(state, state::back)
        state.environment -> EnvironmentScreen(onBack = state::back)
        else -> Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                state = if (hit == null) homeScroll else detailScroll,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hit == null) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LibreTV", style = MaterialTheme.typography.headlineMedium)
                            TextButton(onClick = { state.environment = true }) { Text("环境检查") }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = state.query, onValueChange = { state.query = it.take(100) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("片名") },
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { state.search() }, enabled = !state.busy) { Text("搜索") }
                            TextButton(onClick = { state.search(SAMPLE) }, enabled = !state.busy) { Text(SAMPLE) }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { state.managing = true }) { Text(if (state.sources.isEmpty()) "添加点播源" else "点播源管理") }
                            TextButton(onClick = { state.player = "HLS 对照样片" to CONTROL_HLS_URL }) { Text("播放对照样片") }
                        }
                    }
                    if (state.sources.isNotEmpty()) {
                        item { Text("搜索片源", style = MaterialTheme.typography.titleMedium) }
                        items(state.managerRows, key = { "source:${it.id}" }) { source ->
                            FilterChip(selected = source.enabled, enabled = !state.busy, onClick = { state.setEnabled(source.id, !source.enabled) }, label = { Text(source.name) })
                        }
                    }
                    if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.notice.isNotBlank()) item { Text(state.notice) }
                    for (source in state.sources) {
                        val films = state.groups[source]
                        val error = state.errors[source]
                        if (films != null || error != null) {
                            item {
                                HorizontalDivider()
                                Text("${source.name} · ${error ?: "${films.orEmpty().size} 个结果"}", style = MaterialTheme.typography.titleSmall)
                            }
                            items(films.orEmpty(), key = { "film:${source.url}:${it.id}" }) { film ->
                                TextButton(onClick = { state.open(FilmHit(source, film)) }) {
                                    Text(listOf(film.title, film.year).filter { it.isNotBlank() }.joinToString(" · "))
                                }
                            }
                        }
                    }
                } else {
                    item { TextButton(onClick = state::back) { Text("返回搜索") } }
                    item { Text(hit.film.title, style = MaterialTheme.typography.headlineSmall) }
                    item { Text(listOf(hit.source.name, hit.film.year).filter { it.isNotBlank() }.joinToString(" · ")) }
                    if (state.detailStatus.isNotBlank()) {
                        item { Text(state.detailStatus) }
                        if (state.lines.isEmpty() && state.detailStatus != "正在加载剧集…") {
                            item { TextButton(onClick = { state.open(hit) }) { Text("重新加载") } }
                        }
                    }
                    if (state.lines.isNotEmpty()) {
                        item { Text("播放线路", style = MaterialTheme.typography.titleMedium) }
                        items(state.lines.size) { index ->
                            FilterChip(selected = state.lineIndex == index, onClick = { state.lineIndex = index }, label = { Text(state.lines[index].name) })
                        }
                        item { Text("选择剧集", style = MaterialTheme.typography.titleMedium) }
                        items(state.lines[state.lineIndex].episodes) { ep ->
                            OutlinedButton(onClick = { state.player = "${hit.film.title} · ${ep.name}" to ep.url }) {
                                Text(ep.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
