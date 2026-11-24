package dev.libretv.android

import android.content.Context
import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class StoredSource(
    val id: String,
    val name: String,
    val url: String,
    val detail: String?,
    val isAdult: Boolean,
    val enabled: Boolean,
)

@Entity(tableName = "sources", primaryKeys = ["id"], indices = [Index(value = ["sortIndex"], unique = true)])
internal data class SourceRow(@Embedded val source: StoredSource, val sortIndex: Long)

@Entity(tableName = "completed_migrations")
internal data class CompletedSourceMigration(@PrimaryKey val name: String)

@Dao
internal interface SourceDao {
    @Query("SELECT id, name, url, detail, isAdult, enabled FROM sources ORDER BY sortIndex")
    fun observe(): Flow<List<StoredSource>>

    @Query("SELECT id FROM sources")
    suspend fun ids(): List<String>

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM sources")
    suspend fun lastIndex(): Long

    @Insert
    suspend fun insert(rows: List<SourceRow>)

    @Query("SELECT EXISTS(SELECT 1 FROM completed_migrations WHERE name = :name)")
    suspend fun migrated(name: String): Boolean

    @Query("SELECT name FROM completed_migrations WHERE name LIKE :pattern LIMIT 1")
    suspend fun warning(pattern: String): String?

    @Insert
    suspend fun complete(migration: CompletedSourceMigration)

    @Query("UPDATE sources SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [SourceRow::class, CompletedSourceMigration::class, WatchRecord::class, WatchPointer::class],
    version = 2, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class SourceDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: SourceDatabase? = null

        fun open(context: Context): SourceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SourceDatabase::class.java, "catalog.db")
                .build().also { instance = it }
        }
    }
}

private const val LEGACY_IMPORT = "catalog-sources-0.1.3"
private const val WARNING_PREFIX = "$LEGACY_IMPORT-warning:"

internal data class LegacyRecovery(
    val records: List<StoredSource>,
    val failedRows: List<Int> = emptyList(),
    val issue: String = "",
) {
    val warning: String get() = buildList {
        if (issue.isNotEmpty()) add(issue)
        if (failedRows.isNotEmpty()) {
            val positions = failedRows.take(10).joinToString("、")
            add("有 ${failedRows.size} 条旧片源无法迁移（第 ${positions} 项${if (failedRows.size > 10) "等" else ""}）")
        }
        if (isNotEmpty()) add("原始配置已保留")
    }.joinToString("。")
}

internal fun recoverLegacySources(raw: Any?, selection: Any?, existingIds: Set<String> = emptySet()): LegacyRecovery {
    if (raw == null) return LegacyRecovery(emptyList())
    val items = if (raw is String) try {
        val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
        gson.fromJson(raw.removePrefix("\uFEFF"), JsonObject::class.java)?.get("sources")?.takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: JsonParseException) {
        null
    } else null
    if (items == null) return LegacyRecovery(emptyList(), issue = "无法读取旧版片源列表")
    val invalidSelection = selection != null && (selection !is Set<*> || selection.any { it !is String })
    val selected = selection as? Set<*>
    val records = linkedMapOf<String, StoredSource>()
    val failed = mutableListOf<Int>()
    for ((index, element) in items.withIndex()) {
        val record = try {
            require(element.isJsonObject)
            val item = element.asJsonObject
            val name = item.get("name")
            val address = item.get("url")
            require(name != null && name.isJsonPrimitive && name.asJsonPrimitive.isString)
            require(address != null && address.isJsonPrimitive && address.asJsonPrimitive.isString)
            val original = address.asString
            // Legacy selection keys used the original text, even when OkHttp normalized the request URL.
            val url = requireNotNull(original.toHttpUrlOrNull())
            asStored(manualSource(name.asString, url.toString()), !invalidSelection && (selected == null || original in selected))
        } catch (_: IllegalArgumentException) {
            failed.add(index + 1)
            continue
        }
        if (record.id in existingIds) continue
        val previous = records[record.id]
        if (previous == null && existingIds.size + records.size >= SOURCE_LIMIT) {
            failed.add(index + 1)
        } else if (previous == null || (!previous.enabled && record.enabled)) {
            records[record.id] = record
        }
    }
    val issue = if (invalidSelection) "旧版启用状态无法读取，已将迁移的片源暂时停用" else ""
    return LegacyRecovery(records.values.toList(), failed, issue)
}

private fun asStored(item: SourceCandidate, enabled: Boolean): StoredSource {
    val identity = sourceIdentity(item.url)
    val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return StoredSource(id, item.name, item.url, item.detail, item.isAdult, enabled)
}

class SourceStore(context: Context) {
    private val app = context.applicationContext
    private val database = SourceDatabase.open(app)
    private val dao = database.sources()
    val sources: Flow<List<StoredSource>> = dao.observe().onStart { initialize() }

    suspend fun initialize() {
        database.withTransaction { migrateLegacy() }
    }

    suspend fun legacyWarning(): String = database.withTransaction {
        migrateLegacy()
        dao.warning("$WARNING_PREFIX%")?.removePrefix(WARNING_PREFIX).orEmpty()
    }

    suspend fun add(items: List<SourceCandidate>): Int = database.withTransaction {
        migrateLegacy()
        insertNew(items.map { asStored(it, enabled = !it.isAdult) })
    }

    suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入片源名称" }
        database.withTransaction { migrateLegacy(); dao.rename(id, trimmed) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        database.withTransaction { migrateLegacy(); dao.setEnabled(id, enabled) }
    }

    suspend fun delete(id: String) {
        database.withTransaction { migrateLegacy(); dao.delete(id) }
    }

    private suspend fun insertNew(items: List<StoredSource>): Int {
        val known = dao.ids().toMutableSet()
        val additions = items.filter { known.add(it.id) }
        require(known.size <= SOURCE_LIMIT) { "片源总数不能超过 $SOURCE_LIMIT 个" }
        if (additions.isNotEmpty()) {
            val nextIndex = dao.lastIndex() + 1
            dao.insert(additions.mapIndexed { index, item -> SourceRow(item, nextIndex + index) })
        }
        return additions.size
    }

    private suspend fun migrateLegacy() {
        if (dao.migrated(LEGACY_IMPORT)) return
        val prefs = app.getSharedPreferences("catalog-sources", Context.MODE_PRIVATE).all
        val recovered = recoverLegacySources(prefs["list"], prefs["selected"], dao.ids().toSet())
        insertNew(recovered.records)
        if (recovered.warning.isNotEmpty()) dao.complete(CompletedSourceMigration(WARNING_PREFIX + recovered.warning))
        dao.complete(CompletedSourceMigration(LEGACY_IMPORT))
    }
}
