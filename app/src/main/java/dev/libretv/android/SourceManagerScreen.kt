package dev.libretv.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
internal fun SourceManagerScreen(state: CatalogState, onBack: () -> Unit) {
    val preview = state.preview
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importDocument(uri)
    }
    val back: () -> Unit = {
        if (preview == null) onBack() else if (!state.busy) state.cancelImport()
    }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding().padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, enabled = preview == null || !state.busy) { Text("返回") }
                Text(if (preview == null) "点播源管理" else "导入预览", style = MaterialTheme.typography.titleLarge)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.migrationWarning.isNotBlank()) Text(state.migrationWarning)
            if (state.managerNotice.isNotBlank()) Text(
                state.managerNotice, modifier = Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (preview != null) {
                SourceImportPreview(
                    preview, state.managerRows.size, state.busy, state::confirmImport, state::cancelImport,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("从文件导入") }
                    OutlinedButton(onClick = { state.editSource() }, enabled = !state.busy) { Text("手动添加") }
                }
                val rows = state.managerRows
                Text(
                    if (rows.isEmpty()) "尚无点播源" else "已启用 ${rows.count { it.enabled }} / ${rows.size} 个片源",
                    modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(rows, key = { it.id }) { source ->
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                if (source.isAdult) Text("成人源", style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { state.setEnabled(source.id, it) },
                                modifier = Modifier.semantics { contentDescription = "启用${source.name}" },
                                enabled = !state.busy,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val testing = source.id in state.testing
                            TextButton(onClick = { state.testSource(source.id) }, enabled = !testing && !state.busy) {
                                Text(if (testing) "测试中…" else "测试")
                            }
                            TextButton(onClick = { state.editSource(source) }, enabled = !state.busy) { Text("改名") }
                            TextButton(onClick = { deleteId = source.id }, enabled = !state.busy) { Text("删除") }
                        }
                        state.checkResults[source.id]?.let { result ->
                            Text(result, modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (state.editing) SourceNameDialog(state)
    val delete = state.managerRows.find { it.id == deleteId }
    if (delete != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("删除点播源？") },
        text = { Text("将从点播源列表移除「${delete.name}」。") },
        confirmButton = {
            TextButton(onClick = { state.deleteSource(delete.id); deleteId = null }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
    )
}

@Composable
private fun SourceImportPreview(
    preview: ImportPreview,
    existingCount: Int,
    busy: Boolean,
    confirm: (List<SourceCandidate>) -> Unit,
    cancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable(preview) {
        mutableStateOf(BooleanArray(preview.additions.size) { true })
    }
    val selectedCount = selected.count { it }
    val tooMany = existingCount + selectedCount > SOURCE_LIMIT
    Column(modifier.fillMaxWidth()) {
        Text(
            "可导入 ${preview.additions.size} · 重复 ${preview.duplicates.size} · 无效 ${preview.invalid.size} · 直播跳过 ${preview.liveCount}",
            modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall,
        )
        if (tooMany) Text("当前选择将超过 ${SOURCE_LIMIT} 个点播源，请取消部分勾选。",
            color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(preview.additions) { index, source ->
                val checked = selected[index]
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = checked, role = Role.Checkbox, enabled = !busy,
                        onValueChange = { enabled -> selected = selected.copyOf().also { it[index] = enabled } },
                    ).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(if (source.isAdult) "可添加 · ${source.name}（成人源，默认停用）" else "可添加 · ${source.name}")
                }
            }
            items(preview.duplicates) { reason -> Text("重复 · $reason") }
            items(preview.invalid) { reason -> Text("无效 · $reason") }
            items(preview.skippedLive) { name ->
                Text("跳过 · $name（直播源）")
            }
        }
        HorizontalDivider()
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { confirm(preview.additions.filterIndexed { index, _ -> selected[index] }) },
                enabled = !busy && !tooMany && selectedCount > 0,
            ) {
                Text("确认导入（$selectedCount）")
            }
            OutlinedButton(onClick = cancel, enabled = !busy) { Text("取消") }
        }
    }
}

@Composable
private fun SourceNameDialog(state: CatalogState) {
    val adding = state.editingId == null
    val dismiss = { if (!state.busy) state.closeEditor() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (adding) "添加点播源" else "修改名称") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.draftName, onValueChange = { state.draftName = it }, label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                )
                if (adding) OutlinedTextField(
                    value = state.draftUrl, onValueChange = { state.draftUrl = it }, label = { Text("HTTPS 点播源地址") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (state.managerNotice.isNotBlank()) Text(state.managerNotice, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = state::saveSource, enabled = !state.busy) {
                Text(if (adding) "添加" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("取消") } },
    )
}
