package dev.libretv.android

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SAMPLE = "航拍中国"
private const val LIMIT = 128 * 1024
internal data class FilmHit(val source: CmsSource, val film: Film)

internal fun selectionAfterImport(
    previous: List<CmsSource>, enabled: Set<CmsSource>, updated: List<CmsSource>,
): Set<CmsSource> {
    val existingUrls = previous.map { it.url }.toSet()
    val enabledUrls = enabled.map { it.url }.toSet()
    return updated.filterTo(mutableSetOf()) { it.url !in existingUrls || it.url in enabledUrls }
}

private fun decodeSources(text: String): List<CmsSource> {
    val rows = JSONObject(text.removePrefix("\uFEFF")).getJSONArray("sources")
    require(rows.length() in 1..50)
    val entries = (0 until rows.length()).map { index ->
        val item = rows.getJSONObject(index)
        val name = item.getString("name").trim()
        val address = item.getString("url").trim()
        require(name.isNotBlank() && name.length <= 80 && address.length <= 4096)
        CmsSource(name, address).also { catalogUrl(it, emptyMap()) }
    }
    return entries.distinctBy { it.url }
}

internal class CatalogState(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("catalog-sources", 0)
    var sources by mutableStateOf(
        runCatching { decodeSources(prefs.getString("list", "").orEmpty()) }.getOrDefault(emptyList())
    )
        private set
    var selected by mutableStateOf(
        sources.filter { it.url in (prefs.getStringSet("selected", null) ?: sources.map { s -> s.url }.toSet()) }.toSet()
    )
        private set
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
    private var importJob: Job? = null

    fun importDocument(uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val raw = requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= LIMIT)
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    decodeSources(raw)
                }
                val updated = (imported + sources).distinctBy { it.url }
                require(updated.size <= 50)
                clearSearch()
                selected = selectionAfterImport(sources, selected, updated)
                sources = updated
                val rows = JSONArray()
                for (source in updated) rows.put(JSONObject().put("name", source.name).put("url", source.url))
                prefs.edit().putString("list", JSONObject().put("sources", rows).toString())
                    .putStringSet("selected", selected.map { it.url }.toSet()).apply()
                notice = "已导入 ${imported.size} 个片源"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = "导入失败，请检查 JSON 格式、文件大小和 HTTPS 地址"
            }
        }
    }

    private fun clearSearch() {
        request?.cancel()
        searching = false
        groups.clear()
        errors.clear()
        notice = ""
    }

    fun toggle(source: CmsSource) {
        clearSearch()
        selected = if (source in selected) selected - source else selected + source
        prefs.edit().putStringSet("selected", selected.map { it.url }.toSet()).apply()
    }

    fun search(keyword: String = query) {
        clearSearch()
        query = keyword.trim()
        if (selected.isEmpty() || query.isBlank() || query.length > 100) {
            notice = if (selected.isEmpty()) "请先导入并选择片源" else "搜索词应为 1 至 100 个字符"
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) state.importDocument(it)
    }
    val homeScroll = rememberLazyListState()
    val detailScroll = key(state.detail) { rememberLazyListState() }
    val player = state.player
    val hit = state.detail
    BackHandler(enabled = player == null && (hit != null || state.environment), onBack = state::back)
    when {
        player != null -> PlaybackScreen(title = player.first, url = player.second, onBack = state::back)
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
                            Button(onClick = { state.search() }) { Text("搜索") }
                            TextButton(onClick = { state.search(SAMPLE) }) { Text(SAMPLE) }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入片源") }
                            TextButton(onClick = { state.player = "HLS 对照样片" to CONTROL_HLS_URL }) { Text("播放对照样片") }
                        }
                    }
                    if (state.sources.isNotEmpty()) {
                        item { Text("搜索片源", style = MaterialTheme.typography.titleMedium) }
                        items(state.sources, key = { "source:${it.url}" }) { source ->
                            FilterChip(selected = source in state.selected, onClick = { state.toggle(source) }, label = { Text(source.name) })
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
