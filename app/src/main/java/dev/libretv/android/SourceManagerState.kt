package dev.libretv.android

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal abstract class SourceManagerState(private val application: Application) : AndroidViewModel(application) {
    private val store = SourceStore(application)
    var managerRows by mutableStateOf(emptyList<StoredSource>())
        private set
    var managerNotice by mutableStateOf("")
        private set
    var migrationWarning by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var preview by mutableStateOf<ImportPreview?>(null)
        private set
    val testing = mutableStateListOf<String>()
    val checkResults = mutableStateMapOf<String, String>()
    var editing by mutableStateOf(false)
        private set
    var editingId by mutableStateOf<String?>(null)
        private set
    var draftName by mutableStateOf("")
    var draftUrl by mutableStateOf("")
    private var reading: Job? = null

    protected abstract fun onSourcesChanged()

    private suspend fun load() {
        val updated = store.sources.first()
        if (managerRows != updated) {
            managerRows = updated
            onSourcesChanged()
        }
    }

    private fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        managerNotice = ""
        viewModelScope.launch {
            try {
                load()
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                managerNotice = e.message ?: "请检查输入后重试"
            } catch (_: Exception) {
                managerNotice = "无法访问本地片源数据，请重试"
            } finally {
                busy = false
            }
        }
    }

    fun refreshSources() = operation { migrationWarning = store.legacyWarning() }

    fun editSource(source: StoredSource? = null) {
        managerNotice = ""
        editingId = source?.id
        draftName = source?.name.orEmpty()
        draftUrl = ""
        editing = true
    }

    fun closeEditor() {
        editing = false
        editingId = null
        draftName = ""
        draftUrl = ""
    }

    fun saveSource() = operation {
        val id = editingId
        if (id == null) {
            val count = store.add(listOf(manualSource(draftName, draftUrl)))
            require(count > 0) { "此点播源已存在" }
        } else {
            store.rename(id, draftName)
        }
        load()
        closeEditor()
        managerNotice = if (id == null) "已添加点播源" else "已保存名称"
    }

    fun setEnabled(id: String, enabled: Boolean) = operation {
        store.setEnabled(id, enabled)
        load()
    }

    fun deleteSource(id: String) = operation {
        store.delete(id)
        checkResults.remove(id)
        load()
        managerNotice = "已删除点播源"
    }

    fun testSource(id: String) {
        val source = managerRows.find { it.id == id } ?: return
        if (id in testing) return
        testing.add(id)
        viewModelScope.launch {
            try {
                val result = try {
                    checkCatalog(CmsSource(source.name, source.url))
                    "目录可访问"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catalogErrorText(e)
                }
                if (managerRows.any { it.id == id }) {
                    val time = DateFormat.getTimeInstance().format(Date())
                    checkResults[id] = "$time · $result"
                }
            } finally {
                testing.remove(id)
            }
        }
    }

    fun importDocument(uri: Uri) {
        if (busy) return
        busy = true
        managerNotice = ""
        preview = null
        reading = viewModelScope.launch {
            try {
                load()
                val known = managerRows.map { it.url }.toSet()
                preview = withContext(Dispatchers.IO) {
                    val stream = application.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("无法打开文件，请重新选择")
                    parseSourceList(stream.use(::readSourceText), known)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                managerNotice = e.message ?: "请检查文件格式"
            } catch (_: Exception) {
                managerNotice = "读取失败，请检查文件权限后重试"
            } finally {
                busy = false
                reading = null
            }
        }
    }

    fun confirmImport(items: List<SourceCandidate>) {
        val current = preview ?: return
        if (items.isEmpty() || !current.additions.toHashSet().containsAll(items)) return
        operation {
            val count = store.add(items)
            load()
            preview = null
            managerNotice = "已导入 $count 个点播源"
        }
    }

    fun cancelImport() {
        if (busy) return
        preview = null
        managerNotice = ""
    }

    fun closeManager() {
        reading?.cancel()
        preview = null
        managerNotice = ""
    }
}
