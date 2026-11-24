package dev.libretv.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun watchTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

internal fun historySourceLabel(entry: WatchRecord, sources: Map<String, StoredSource>, repeatedNames: Set<String>): String {
    val source = sources[entry.sourceId]
    val name = source?.name ?: entry.sourceName
    val label = if (source == null || name in repeatedNames) {
        val prefix = entry.sourceId.take(6)
        val ambiguous = sources.values.any { it.id != entry.sourceId && it.name == name && it.id.startsWith(prefix) }
        "$name · ${if (ambiguous || source == null) entry.sourceId else prefix}"
    } else name
    return when {
        source == null -> "$label（已移除）"
        !source.enabled -> "$label（已停用）"
        else -> label
    }
}

@Composable
internal fun RecentWatchRow(entry: WatchRecord, source: String, enabled: Boolean, open: () -> Unit, delete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = open, enabled = enabled, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(listOf(entry.year, source, entry.lineName, entry.episodeName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                val duration = if (entry.durationMs > 0) " / ${watchTime(entry.durationMs)}" else ""
                Text(if (entry.completed) "已看完" else watchTime(entry.positionMs) + duration)
            }
        }
        TextButton(onClick = delete) { Text("删除") }
    }
}

@Composable
internal fun HistoryConfirmation(title: String, message: String, action: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
internal fun EpisodeHistoryActions(state: CatalogState, hit: FilmHit, label: (WatchRecord) -> String) {
    val stored by remember(hit.ref) { state.episodeHistory(hit.ref) }.collectAsState(emptyList())
    val records = remember(stored, state.filterAdult, state.managerRows) { stored.filter(state::historyVisible) }
    var choosing by remember(hit.ref) { mutableStateOf(false) }
    var target by remember(hit.ref) { mutableStateOf<WatchRecord?>(null) }
    if (records.isNotEmpty()) TextButton(onClick = { choosing = true }) { Text("清除单集进度") }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择要清除进度的剧集") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(records) { record ->
                    TextButton(onClick = { choosing = false; target = record }) {
                        Text("${record.lineName} · ${record.episodeName} · ${watchTime(record.positionMs)}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } })
    target?.let { record ->
        HistoryConfirmation("清除单集进度？", "《${record.title}》 ${record.year}\n${label(record)} · ${record.lineName} · ${record.episodeName}",
            "确认清除", { state.clearEpisode(record.ref); target = null }, { target = null })
    }
}
