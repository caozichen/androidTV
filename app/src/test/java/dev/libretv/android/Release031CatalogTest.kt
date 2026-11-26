package dev.libretv.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release031CatalogTest {
    private fun source(n: Int) = CmsSource("馆藏$n", "https://archive$n.example.org/vod", "archive-$n")
    private val shared = Film("73", "山间来信", "2018", "纪录片")

    @Test fun jReportedPageCountAndFivePageBoundaryAreDistinct() = runBlocking {
        for (reported in listOf(5, 12)) {
            val requested = mutableListOf<Int>()
            val updates = mutableListOf<SourceSearchUpdate>()
            searchCatalogPages(listOf(source(7)), "来信", 2_000, { _, _, pg ->
                requested += pg
                parseCatalogPage("""{"pagecount":"$reported","list":[{"vod_id":73,"vod_name":"山间来信"}]}""")
            }, updates::add)
            assertEquals((1..5).toList(), requested)
            assertEquals(5, updates.last().pages)
            assertEquals(1, updates.last().films.size)
            assertTrue(updates.last().done)
            assertTrue(updates.all { it.truncated == (reported > 5) })
            assertTrue(updates.all { it.error == null })
        }
    }

    @Test fun mFourDispatchedCallsAndOneQueuedCallExpireDifferentlyThenRetrySucceeds() = runBlocking {
        val gate = CountDownLatch(1)
        val dispatched = ConcurrentHashMap.newKeySet<String>()
        val fixtures = (1..5).map(::source)
        val client = catalogClient.newBuilder().addInterceptor { chain ->
            dispatched += chain.request().url.host
            check(gate.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"pagecount":1,"list":[]}""".toResponseBody()).build()
        }.build()
        suspend fun fetch(s: CmsSource, keyword: String, pg: Int): CatalogPage {
            return parseCatalogPage(fetchCatalog(catalogUrl(s, mapOf("wd" to keyword, "pg" to pg.toString())), client))
        }
        val expired = mutableListOf<SourceSearchUpdate>()
        try {
            searchCatalogPages(fixtures, "雪原", 500, ::fetch, expired::add)
            assertEquals(setOf("archive1.example.org","archive2.example.org","archive3.example.org","archive4.example.org"), dispatched.toSet())
            assertTrue(expired.all { it.done && it.pages == 0 })
            assertTrue(expired.filter { it.source.id != fixtures.last().id }.all { it.error.orEmpty().contains("点播源搜索超时") })
            assertTrue(expired.single { it.source.id == fixtures.last().id }.error.orEmpty().contains("等待请求名额超时"))
        } finally {
            gate.countDown()
        }
        val recovered = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(fixtures, "雪原", 2_000, ::fetch, recovered::add)
        assertEquals(5, recovered.count { it.done && it.error == null && it.pages == 1 })
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun promotingAnExpiredPageBeforeCancellationDoesNotSendIt() = runBlocking {
        val first = catalogUrl(source(1), emptyMap())
        val second = catalogUrl(source(2), emptyMap())
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = ConcurrentHashMap.newKeySet<String>()
        val client = catalogClient.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1 })
            .addInterceptor { chain ->
                requests += chain.request().url.host
                if (chain.request().url == first) {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("ok".toResponseBody()).build()
            }.build()
        val clock = TestTimeSource()
        val state = SearchDispatchState(clock.markNow() + 10.seconds)
        val firstCall = async { fetchCatalog(first, client) }
        try {
            withTimeout(5_000) { entered.await() }
            val queued = async(state, start = CoroutineStart.UNDISPATCHED) {
                runCatching { fetchCatalog(second, client) }.exceptionOrNull()
            }
            assertEquals(1, client.dispatcher.queuedCallsCount())
            clock += 10.seconds
            release.countDown()
            assertEquals("ok", firstCall.await())
            assertTrue(queued.await() is CatalogQueueTimeout)
            assertTrue(state.waitingForSlot)
            assertEquals(setOf(first.host), requests.toSet())
        } finally {
            release.countDown()
        }
    }

    @Test fun gSameFilmIdAndTitleStayIndependentAcrossFastAndSlowSources() = runBlocking {
        val slow = source(21)
        val fast = source(22)
        val releaseSlow = CompletableDeferred<Unit>()
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(slow, fast), "山间来信", 2_000, { s, _, pg ->
            if (s.id == slow.id) releaseSlow.await()
            val extra = shared.copy(id = "79", title = "山间来信花絮")
            CatalogPage(if (pg == 1) listOf(shared) else listOf(shared, extra), 3)
        }) {
            updates += it
            if (it.source.id == fast.id && !it.done) releaseSlow.complete(Unit)
        }
        assertEquals(fast.id, updates.first().source.id)
        val last = updates.filter { it.done }
        assertEquals(2, last.size)
        assertTrue(last.all { it.films.map(Film::id) == listOf("73","79") })
        assertEquals(2, last.map { it.source.id to it.films.first().id }.distinct().size)
        assertFalse(last.any { it.truncated })
    }

    @Test fun gLateOldTermWithSameFilmIdCannotReplaceNewTerm() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val visible = mutableListOf<String>()
        val old = launch {
            searchCatalogPages(listOf(source(29)), "旧片名", 2_000, { _, query, _ ->
                oldStarted.complete(Unit)
                withContext(NonCancellable) { oldRelease.await() }
                CatalogPage(listOf(shared.copy(title = query)), 1)
            }) { visible += it.films.map(Film::title) }
        }
        oldStarted.await()
        old.cancel()
        try {
            searchCatalogPages(listOf(source(29)), "新片名", 2_000, { _, query, _ ->
                CatalogPage(listOf(shared.copy(title = query)), 1)
            }) { visible += it.films.map(Film::title) }
        } finally {
            oldRelease.complete(Unit)
            old.join()
        }
        assertEquals(listOf("新片名"), visible)
    }
}
