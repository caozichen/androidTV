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
        player != null -> {
            val line = if (player.ref == null) null else state.lines.getOrNull(state.lineIndex)
            val labels = remember(line) { line?.episodeLabels().orEmpty() }
            PlaybackScreen(
                title = player.title,
                url = player.url,
                sessionKey = player.watch ?: CONTROL_HLS_URL,
                initialPositionMs = player.startMs,
                initiallyPaused = player.initiallyPaused,
                onCheckpoint = { position, duration, complete -> state.checkpoint(player, position, duration, complete) },
                refreshUrl = if (player.ref == null) null else suspend { state.refreshEpisodeUrl(player) },
                onSwitchSource = if (player.ref == null) null else state::chooseOtherSource,
                episodeNames = labels,
                episodeIndex = player.index,
                onEpisode = { index -> if (state.player == player && index in labels.indices) state.play(index) },
                onBack = state::back,
            )
        }
        state.managing -> SourceManagerScreen(state, state::back)
        state.environment -> EnvironmentScreen(onBack = state::back)
        else -> Surface(Modifier.fillMaxSize()) {
            val sources = remember(state.managerRows) { state.managerRows.associateBy { it.id } }
            val repeated = remember(sources) { sources.values.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys }
            val sourceLabel = { record: WatchRecord -> historySourceLabel(record, sources, repeated) }
            var limit by rememberSaveable(state.filterAdult) { mutableIntStateOf(3) }
            val records = if (hit == null) key(state.filterAdult) {
                val flow = remember(limit) { state.homeHistory(state.filterAdult, limit + 1) }
                flow.collectAsState(emptyList()).value
            } else emptyList()
            var clearing by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf<WatchRecord?>(null) }
            deleting?.let { record ->
                HistoryConfirmation("删除观看记录？", "删除《${record.title}》 ${record.year}\n${sourceLabel(record)} 下所有分集的进度。",
                    "确认删除", { state.deleteHistory(record.ref.video); deleting = null }, { deleting = null })
            }
            if (clearing) HistoryConfirmation("清空观看历史？", "同时删除所有来源及分集的播放进度，包括过滤隐藏的记录。",
                "确认清空", { state.clearHistory(); clearing = false }, { clearing = false })
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
                            TextButton(onClick = state::playControl) { Text("播放对照样片") }
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
                    if (records.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("最近观看", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { clearing = true }) { Text("清空历史") }
                            }
                        }
                        items(records.take(limit), key = { "history:${it.sourceId.length}:${it.sourceId}${it.vodId}" }) { record ->
                            RecentWatchRow(record, sourceLabel(record), !state.busy, { state.openHistory(record) }, { deleting = record })
                        }
                        item {
                            Row {
                                if (records.size > limit) TextButton(onClick = { limit += 20 }) { Text("查看更多历史") }
                                if (limit > 3) TextButton(onClick = { limit = 3 }) { Text("收起历史") }
                            }
                        }
                    }
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
                        val cap = if (u.truncated) " · 仅前 5 页" else ""
                        Text("${u.source.name} · ${u.pages} 页 · ${u.films.size} 条 · $outcome$cap", style = MaterialTheme.typography.bodySmall)
                        if (u.done && u.error != null) TextButton(onClick = { state.retrySource(u.source.id) }) {
                            Text("重试此源")
                        }
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
                            FilmButton(option, state.sourceLabel(option) + " · 条目 " + option.film.id) { state.open(option, switch = true) }
                        }
                    } else {
                        item { TextButton(onClick = state::chooseOtherSource) { Text("换源") } }
                        if (state.detailStatus.isNotBlank()) item { Text(state.detailStatus) }
                        if (!state.loadingDetail && state.lines.isEmpty()) item {
                            TextButton(onClick = { state.reloadDetail(hit) }) { Text("重新加载") }
                        }
                        item { EpisodeHistoryActions(state, hit, sourceLabel) }
                        if (state.lines.isNotEmpty()) {
                            if (!state.requiresEpisodeSelection()) item {
                                val previous = state.detailHistory()
                                val text = when {
                                    state.lastEpisode != null && state.lastEpisode != previous?.ref -> "播放已选剧集"
                                    previous == null -> "播放第一集"
                                    previous.completed -> "重播 ${previous.episodeName}"
                                    else -> "继续 ${previous.episodeName}"
                                }
                                Button(onClick = state::primaryPlay) { Text(text) }
                                if (state.nextAfterCompletion() != null) {
                                    TextButton(onClick = state::playNextAfterCompletion) { Text("播放下一集") }
                                }
                            }
                            item { Text("播放线路", style = MaterialTheme.typography.titleMedium) }
                            items(state.lines.size) { index ->
                                FilterChip(selected = state.lineIndex == index, onClick = { state.selectLine(index) }, label = { Text(state.lines[index].name) })
                            }
                            item { Text("选择剧集", style = MaterialTheme.typography.titleMedium) }
                            val labels = state.lines[state.lineIndex].episodeLabels()
                            items(labels.size) { index ->
                                OutlinedButton(onClick = { state.play(index) }) {
                                    Text(if (state.lastEpisode != null && state.lastEpisode == state.episodeRef(index)) "${labels[index]} · 已选" else labels[index])
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
