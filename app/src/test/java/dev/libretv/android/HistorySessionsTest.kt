package dev.libretv.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySessionsTest {
    private val sessions = HistorySessions()
    private fun begin(source: String, episode: String): HistorySession {
        val snapshot = WatchRecord(source, "film", "line", episode, "馆", "影片", "2025", "正片", "主线", episode)
        return HistorySession(snapshot).also(sessions::begin)
    }

    @Test fun switchedEpisodeCanFlushItsOwnPositionButCannotReplaceLatestEpisode() {
        val first = begin("s1", "one")
        val second = begin("s1", "two")
        assertTrue(sessions.ownsEpisode(first))
        assertFalse(sessions.ownsLatest(first))
        assertTrue(sessions.ownsEpisode(second))
        assertTrue(sessions.ownsLatest(second))
        begin("s1", "one")
        assertFalse(sessions.ownsEpisode(first))
    }

    @Test fun deleteThenReplayInvalidatesLateCallbacksAndPreservesOtherSource() {
        val deleted = begin("s1", "one")
        val other = begin("s2", "one")
        sessions.delete(deleted.snapshot.ref.video)
        val replay = begin("s1", "one")
        assertFalse(sessions.ownsEpisode(deleted))
        assertTrue(sessions.ownsEpisode(replay))
        assertTrue(sessions.ownsLatest(other))
        sessions.clear()
        assertFalse(sessions.ownsEpisode(other))
        assertFalse(sessions.ownsLatest(replay))
    }
}
