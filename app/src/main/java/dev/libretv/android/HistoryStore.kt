package dev.libretv.android

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Entity(tableName = "episode_progress", primaryKeys = ["sourceId", "vodId", "lineId", "episodeId"])
internal data class WatchRecord(
    val sourceId: String, val vodId: String, val lineId: String, val episodeId: String,
    val sourceName: String, val title: String, val year: String, val category: String,
    val lineName: String, val episodeName: String,
    val positionMs: Long = 0, val durationMs: Long = 0, val completed: Boolean = false,
    val updatedAt: Long = 0,
    val sourceAdult: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val categoryAdult: Boolean = isAdultCategory(category),
    @ColumnInfo(defaultValue = "0") val watchOrder: Long = 0,
) {
    val ref: EpisodeRef get() = EpisodeRef(VideoRef(sourceId, vodId), lineId, episodeId)
}

@Entity(tableName = "watch_history", primaryKeys = ["sourceId", "vodId"], indices = [Index("viewOrder")])
internal data class WatchPointer(val sourceId: String, val vodId: String, val lineId: String, val episodeId: String, val viewOrder: Long)

@Dao
internal interface HistoryDao {
    @Query("""SELECT p.* FROM watch_history h JOIN episode_progress p USING (sourceId, vodId, lineId, episodeId)
        LEFT JOIN sources s ON s.id = h.sourceId
        WHERE NOT :filterAdult OR (p.categoryAdult = 0 AND COALESCE(p.sourceAdult, s.isAdult, 1) = 0 AND COALESCE(s.isAdult, 0) = 0)
        ORDER BY h.viewOrder DESC LIMIT :limit""")
    fun recent(filterAdult: Boolean, limit: Int): Flow<List<WatchRecord>>

    @Query("""SELECT p.* FROM watch_history h JOIN episode_progress p USING (sourceId, vodId, lineId, episodeId)
        WHERE h.sourceId=:sourceId AND h.vodId=:vodId""")
    fun latest(sourceId: String, vodId: String): Flow<WatchRecord?>

    @Query("SELECT * FROM episode_progress WHERE sourceId=:sourceId AND vodId=:vodId")
    fun episodes(sourceId: String, vodId: String): Flow<List<WatchRecord>>

    @Query("SELECT COALESCE(MAX(viewOrder), 0) FROM watch_history")
    suspend fun newestOrder(): Long

    @Query("SELECT viewOrder FROM watch_history WHERE sourceId=:sourceId AND vodId=:vodId")
    suspend fun currentOrder(sourceId: String, vodId: String): Long?

    @Query("SELECT * FROM watch_history WHERE sourceId=:sourceId AND vodId=:vodId")
    suspend fun pointer(sourceId: String, vodId: String): WatchPointer?

    @Query("""SELECT * FROM episode_progress WHERE sourceId=:sourceId AND vodId=:vodId
        ORDER BY watchOrder DESC, updatedAt DESC, lineId, episodeId LIMIT 1""")
    suspend fun lastRemaining(sourceId: String, vodId: String): WatchRecord?

    @Query("SELECT * FROM episode_progress WHERE sourceId=:sourceId AND vodId=:vodId AND lineId=:lineId AND episodeId=:episodeId")
    suspend fun get(sourceId: String, vodId: String, lineId: String, episodeId: String): WatchRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(record: WatchRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(pointer: WatchPointer)

    @Query("DELETE FROM episode_progress WHERE sourceId=:sourceId AND vodId=:vodId")
    suspend fun deleteProgress(sourceId: String, vodId: String)

    @Query("DELETE FROM episode_progress WHERE sourceId=:sourceId AND vodId=:vodId AND lineId=:lineId AND episodeId=:episodeId")
    suspend fun deleteEpisode(sourceId: String, vodId: String, lineId: String, episodeId: String)

    @Query("DELETE FROM watch_history WHERE sourceId=:sourceId AND vodId=:vodId")
    suspend fun deleteHistory(sourceId: String, vodId: String)

    @Query("DELETE FROM episode_progress")
    suspend fun clearProgress()

    @Query("DELETE FROM watch_history")
    suspend fun clearHistory()
}

internal class HistorySession internal constructor(internal val snapshot: WatchRecord) {
    internal var viewOrder: Long = 0
}

// Callers enqueue these operations on the UI thread, including the last callback during player disposal.
// The worker and its queue live with the process so that Activity teardown cannot cancel a pending write.
internal class HistoryStore private constructor(private val db: SourceDatabase) {
    private val dao = db.history()
    fun recent(filterAdult: Boolean, limit: Int) = dao.recent(filterAdult, limit.coerceAtLeast(1)).distinctUntilChanged()
    fun latest(video: VideoRef) = dao.latest(video.sourceId, video.vodId).distinctUntilChanged()
    fun episodes(video: VideoRef) = dao.episodes(video.sourceId, video.vodId).distinctUntilChanged()
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val sessions = HistorySessions()
    private var lastOrder: Long? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (operation in queue) try {
                operation()
            } catch (e: Exception) {
                Log.e("LibreTVHistory", "Local history operation failed", e)
            }
        }
    }

    private fun enqueue(operation: suspend () -> Unit) { queue.trySend(operation).getOrThrow() }

    fun begin(snapshot: WatchRecord): HistorySession {
        val session = HistorySession(snapshot)
        enqueue {
            session.viewOrder = (lastOrder ?: dao.newestOrder()) + 1
            lastOrder = session.viewOrder
            sessions.begin(session)
        }
        return session
    }

    fun save(session: HistorySession, positionMs: Long, durationMs: Long, completed: Boolean) = enqueue {
        val snapshot = session.snapshot
        if (sessions.ownsEpisode(session)) {
            val duration = durationMs.coerceAtLeast(0)
            val position = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
            val record = snapshot.copy(positionMs = position, durationMs = duration, completed = completed,
                updatedAt = System.currentTimeMillis(), watchOrder = session.viewOrder)
            db.withTransaction {
                dao.saveProgress(record)
                // A newly opened episode may fail before it has any progress. Keep a previous
                // episode reachable until a newer session actually publishes a checkpoint.
                if (sessions.ownsLatest(session) || (dao.currentOrder(record.sourceId, record.vodId) ?: 0) <= session.viewOrder) {
                    dao.select(WatchPointer(record.sourceId, record.vodId, record.lineId, record.episodeId, session.viewOrder))
                }
            }
        }
    }

    suspend fun progress(ref: EpisodeRef): WatchRecord? {
        val result = CompletableDeferred<WatchRecord?>()
        enqueue {
            try {
                result.complete(dao.get(ref.video.sourceId, ref.video.vodId, ref.lineId, ref.episodeId))
            } catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result.await()
    }

    fun delete(video: VideoRef) = enqueue {
        db.withTransaction {
            dao.deleteHistory(video.sourceId, video.vodId)
            dao.deleteProgress(video.sourceId, video.vodId)
        }
        sessions.delete(video)
    }

    fun deleteEpisode(ref: EpisodeRef) = enqueue {
        val (source, film) = ref.video
        db.withTransaction {
            val pointer = dao.pointer(source, film)
            dao.deleteEpisode(source, film, ref.lineId, ref.episodeId)
            if (pointer?.lineId == ref.lineId && pointer.episodeId == ref.episodeId) {
                val remaining = dao.lastRemaining(source, film)
                if (remaining == null) dao.deleteHistory(source, film)
                else dao.select(pointer.copy(lineId = remaining.lineId, episodeId = remaining.episodeId,
                    viewOrder = remaining.watchOrder.takeIf { it > 0 } ?: pointer.viewOrder))
            }
        }
        sessions.delete(ref)
    }

    fun clear() = enqueue {
        db.withTransaction {
            dao.clearHistory()
            dao.clearProgress()
        }
        sessions.clear()
    }

    companion object {
        @Volatile private var instance: HistoryStore? = null
        fun open(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(SourceDatabase.open(context)).also { instance = it }
        }
    }
}

internal class HistorySessions {
    private val episodeOwners = mutableMapOf<EpisodeRef, HistorySession>()
    private val latestOwners = mutableMapOf<VideoRef, HistorySession>()
    fun begin(session: HistorySession) {
        val ref = session.snapshot.ref
        episodeOwners[ref] = session
        latestOwners[ref.video] = session
    }
    fun ownsEpisode(session: HistorySession) = episodeOwners[session.snapshot.ref] === session
    fun ownsLatest(session: HistorySession) = latestOwners[session.snapshot.ref.video] === session
    fun delete(ref: EpisodeRef) {
        val owner = episodeOwners.remove(ref) ?: return
        if (latestOwners[ref.video] === owner) latestOwners.remove(ref.video)
    }
    fun delete(video: VideoRef) {
        latestOwners.remove(video)
        episodeOwners.keys.removeAll { it.video == video }
    }
    fun clear() {
        episodeOwners.clear()
        latestOwners.clear()
    }
}
