package dev.libretv.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogScreen(state: CatalogState) {
    val homeScroll = rememberLazyListState()
    val detailScroll = key(state.detail?.ref) { rememberLazyListState() }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showProgress by rememberSaveable { mutableStateOf(false) }
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
                        OutlinedTextField(value = state.query, onValueChange = { state.query = it.take(100) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("片名") })
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { state.search() }, enabled = !state.busy) { Text("搜索") }
                            TextButton(onClick = { state.search(SAMPLE) }, enabled = !state.busy) { Text(SAMPLE) }
                            if (state.searching) TextButton(onClick = state::stopSearch) { Text("取消搜索") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { state.managing = true }) { Text(if (state.managerRows.isEmpty()) "添加点播源" else "点播源管理") }
                            TextButton(onClick = { state.player = "HLS 对照样片" to CONTROL_HLS_URL }) { Text("播放对照样片") }
                        }
                    }
                    if (state.managerRows.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showSources = !showSources }) {
                                Text("搜索片源 · 已启用 ${state.managerRows.count { it.enabled }} / ${state.managerRows.size}")
                            }
                        }
                        if (showSources) item {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = state.filterAdult, onCheckedChange = state::updateFilter)
                                Text("过滤成人源及成人分类")
                            }
                        }
                        if (showSources) items(state.managerRows, key = { "source:${it.id}" }) { source ->
                            FilterChip(selected = source.enabled, enabled = !state.busy,
                                onClick = { state.setEnabled(source.id, !source.enabled) },
                                label = { Text(if (source.isAdult) "${source.name}（成人源）" else source.name) })
                        }
                    }
                    if (state.searching) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("搜索中 · 已完成 ${state.updates.values.count { it.done }} / ${state.updates.size} 个片源")
                    }
                    if (state.notice.isNotBlank()) item { Text(state.notice) }
                    if (state.updates.isNotEmpty()) item {
                        Row {
                            TextButton(onClick = { showProgress = !showProgress }) { Text("各源状态") }
                            if (!state.searching) TextButton(onClick = { state.search(state.searchedTerm, refresh = true) }) { Text("重新搜索") }
                        }
                    }
                    if (showProgress || state.hits.isEmpty()) items(state.updates.values.toList(), key = { "status:${it.source.id}" }) { u ->
                        val outcome = u.error ?: when {
                            u.done -> "完成"
                            state.searching -> "加载中"
                            else -> "已取消"
                        }
                        Text("${u.source.name} · ${u.pages} 页 · ${u.films.size} 条 · $outcome", style = MaterialTheme.typography.bodySmall)
                    }
                    items(state.hits, key = { "film:${it.source.id}:${it.film.id}" }) { entry ->
                        FilmButton(entry, state.sourceLabel(entry)) { state.open(entry) }
                    }
                } else {
                    item { TextButton(onClick = state::back) { Text(if (state.choosingSource) "返回详情" else "返回搜索") } }
                    item { Text(hit.film.title, style = MaterialTheme.typography.headlineSmall) }
                    item { Text(listOf(state.sourceLabel(hit), hit.film.year, hit.film.category).filter { it.isNotBlank() }.joinToString(" · ")) }
                    if (state.choosingSource) {
                        item { Text("选择其他来源", style = MaterialTheme.typography.titleMedium) }
                        val options = state.sourceOptions()
                        if (options.isEmpty()) item { Text(if (state.searching) "正在等待其他片源" else "当前搜索没有其他来源，请返回搜索调整关键词或片源") }
                        items(options, key = { "switch:${it.source.id}:${it.film.id}" }) { option ->
                            FilmButton(option, state.sourceLabel(option)) { state.open(option, switch = true) }
                        }
                    } else {
                        item { TextButton(onClick = { state.choosingSource = true }) { Text("换源") } }
                        if (state.detailStatus.isNotBlank()) item { Text(state.detailStatus) }
                        if (!state.loadingDetail && state.lines.isEmpty()) item {
                            TextButton(onClick = { state.open(hit, refresh = true) }) { Text("重新加载") }
                        }
                        if (state.lines.isNotEmpty()) {
                            item { Text("播放线路", style = MaterialTheme.typography.titleMedium) }
                            items(state.lines.size) { index ->
                                FilterChip(selected = state.lineIndex == index, onClick = { state.selectLine(index) }, label = { Text(state.lines[index].name) })
                            }
                            item { Text("选择剧集", style = MaterialTheme.typography.titleMedium) }
                            items(state.lines[state.lineIndex].episodes.size) { index ->
                                val ep = state.lines[state.lineIndex].episodes[index]
                                OutlinedButton(onClick = { state.play(index) }) {
                                    Text(if (state.lastEpisode != null && state.lastEpisode == state.episodeRef(index)) "${ep.name} · 已选" else ep.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun CatalogState.sourceLabel(hit: FilmHit): String =
    if (managerRows.count { it.name == hit.source.name } > 1) "${hit.source.name} · ${hit.source.id.take(6)}" else hit.source.name

@Composable
private fun FilmButton(hit: FilmHit, sourceLabel: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(hit.film.title, style = MaterialTheme.typography.titleMedium)
            Text(listOf(sourceLabel, hit.film.year, hit.film.category).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
