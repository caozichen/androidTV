package dev.libretv.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRecovery031Test {
    @Test fun repeatedReadsDoNotExtendTheLifetimeOfASignedDetail() = runBlocking {
        var clock = 8_000L
        val cache = DetailCache { clock }
        val ref = VideoRef("archive-river", "805")
        var reads = 0
        suspend fun request(force: Boolean = false) = cache.load(ref, force) {
            CatalogDetail(Film("805", "潮汐·补给站", "2030", "签名版本 ${++reads}"), emptyList())
        }.film?.category
        assertEquals("签名版本 1", request())
        clock += 42_000
        assertEquals("签名版本 1", request())
        clock += 17_999
        assertEquals("签名版本 1", request())
        clock++
        assertEquals("签名版本 2", request())
        assertEquals("签名版本 3", request(true))
    }

    private val healthy = CmsSource("城市", "https://city.example/vod", "city")
    private val failed = CmsSource("乡村", "https://village.example/vod", "village")
    private val shared = Film("912", "岸线纪事", "2027", "纪录")
    private val previous = SourceSearchUpdate(failed, listOf(shared), 1, true, "分页断开")

    @Test fun selectiveRetryKeepsTheOtherSourceAndPreviouslyReadPages() = runBlocking {
        val healthyResult = SourceSearchUpdate(healthy, listOf(shared), 1, true)
        val table = mutableMapOf(healthy.id to healthyResult, failed.id to previous)
        val states = mutableListOf<SourceSearchUpdate>()
        val newFilm = shared.copy(id = "913")
        retryCatalogSource(previous, "岸线", true, { calls, term, filter, emit ->
            assertEquals(listOf(failed), calls)
            assertEquals("岸线", term)
            assertEquals(true, filter)
            emit(SourceSearchUpdate(failed, listOf(newFilm), 1, false))
            emit(SourceSearchUpdate(failed, listOf(newFilm, shared), 2, true))
        }) {
            states += it
            table[it.source.id] = it
            assertEquals(healthyResult, table[healthy.id])
        }
        assertEquals(listOf(shared), states.first().films)
        assertEquals(listOf(newFilm, shared), states[1].films)
        assertNull(table[failed.id]?.error)
        assertEquals(true, table[failed.id]?.done)
    }

    @Test fun cancellationDuringRetryDoesNotPublishAFinalReplacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<SourceSearchUpdate>()
        val retry = launch {
            retryCatalogSource(previous, "岸线", true, { _, _, _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }, events::add)
        }
        entered.await()
        retry.cancelAndJoin()
        assertEquals(listOf(previous.copy(done = false, error = null)), events)
    }
}
