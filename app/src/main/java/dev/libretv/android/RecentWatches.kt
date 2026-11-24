package dev.libretv.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun watchTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

@Composable
internal fun RecentWatches(state: CatalogState) {
    var clearing by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WatchRecord?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近观看", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { clearing = true }) { Text("清空历史") }
        }
        val entries = if (expanded) state.recent else state.recent.take(3)
        entries.forEach { entry ->
            val source = state.managerRows.find { it.id == entry.sourceId }
            val sourceLabel = when {
                source == null -> "${entry.sourceName}（已移除）"
                !source.enabled -> "${source.name}（已停用）"
                else -> source.name
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.openHistory(entry) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text("$sourceLabel · ${entry.lineName} · ${entry.episodeName}", style = MaterialTheme.typography.bodySmall)
                        val duration = if (entry.durationMs > 0) " / ${watchTime(entry.durationMs)}" else ""
                        Text(if (entry.completed) "已看完" else watchTime(entry.positionMs) + duration)
                    }
                }
                TextButton(onClick = { deleting = entry }) { Text("删除") }
            }
        }
        if (state.recent.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起历史" else "查看全部历史（${state.recent.size}）")
        }
    }
    val entry = deleting
    if (clearing || entry != null) {
        val dismiss = { clearing = false; deleting = null }
        AlertDialog(onDismissRequest = dismiss,
            title = { Text(if (clearing) "清空观看历史？" else "删除观看记录？") },
            text = { Text(if (entry == null) "同时删除所有分集的播放进度。" else "同时删除此来源下《${entry.title}》所有分集的进度。") },
            confirmButton = { TextButton(onClick = {
                if (clearing) state.clearHistory() else entry?.let { state.deleteHistory(it.ref.video) }
                dismiss()
            }) { Text(if (clearing) "确认清空" else "确认删除") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
    }
}
