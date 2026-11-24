package dev.libretv.android

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private val a = CmsSource("A", "https://a.example.org/catalog", "source-a")
    private val b = CmsSource("B", "https://b.example.org/catalog", "source-b")
    private val one = Film("1", "第一部", "2020")
    private val two = Film("2", "第二部", "2021")

    @Test fun fastSourceIsUsableWhileAnotherSourceIsStillWaitingForItsDeadline() = runBlocking {
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(a, b), "片", 500, { source, _, _ ->
            if (source.id == a.id) awaitCancellation()
            CatalogPage(listOf(two), 1)
        }, updates::add)
        assertEquals(b.id, updates.first().source.id)
        assertEquals(listOf(two), updates.first().films)
        assertTrue(updates.first().done)
        assertEquals(a.id, updates.last().source.id)
        assertTrue(updates.last().error.orEmpty().contains("超时"))
    }

    @Test fun filterOffIncludesTheCategoryOnBothIntermediateAndFinalPages() = runBlocking {
        val restricted = Film("1", "短片", "2020", "伦理片")
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(a), "短片", 2_000, filterAdult = false, fetch = { _, _, page ->
            CatalogPage(if (page == 1) listOf(restricted) else listOf(two), 2)
        }, onUpdate = updates::add)
        assertEquals(listOf(restricted), updates.first().films)
        assertFalse(updates.first().done)
        assertTrue(updates.last().done)
        assertEquals(setOf(restricted, two), updates.last().films.toSet())
    }

    @Test fun delayedOldKeywordCannotOverwriteNewerResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val visible = mutableListOf<String>()
        val old = launch {
            searchCatalogPages(listOf(a), "旧词", 2_000, { _, term, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
                CatalogPage(listOf(Film("same-id", term, "")), 1)
            }) { visible += it.films.map(Film::title) }
        }
        entered.await()
        old.cancel()
        try {
            searchCatalogPages(listOf(a), "新词", 2_000, { _, term, _ ->
                CatalogPage(listOf(Film("same-id", term, "")), 1)
            }) { visible += it.films.map(Film::title) }
        } finally {
            releaseOld.complete(Unit)
            old.join()
        }
        assertEquals(listOf("新词"), visible)
    }

    @Test fun allHomepagesStartBeforePagingAndEachSourceIsCappedAtFive() = runBlocking {
        val starts = mutableListOf<Pair<String, Int>>()
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(a,b), "片", 2_000, { source, _, page ->
            starts.add(source.id to page)
            CatalogPage(listOf(one), 20)
        }, updates::add)
        assertEquals(setOf(a.id to 1, b.id to 1), starts.take(2).toSet())
        assertEquals(10, starts.size)
        val terminal = updates.filter { it.done }
        assertEquals(2, terminal.size)
        assertTrue(terminal.all { it.pages == 5 && it.films == listOf(one) })
        assertTrue(updates.any { !it.done && it.pages == 1 })
    }

    @Test fun badPageKeepsPreviousResultsAndLaterPagesAndOtherSourceCanSucceed() = runBlocking {
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(a,b), "短片", 2_000, { source, _, page ->
            if (source.id == b.id) return@searchCatalogPages CatalogPage(listOf(one), 1)
            when (page) {
                1 -> CatalogPage(listOf(one), 3)
                2 -> throw IOException("synthetic failure")
                else -> CatalogPage(listOf(one, two), 3)
            }
        }, updates::add)
        val first = updates.single { it.source.id == a.id && it.done }
        assertEquals(2, first.pages)
        assertEquals(setOf("1","2"), first.films.map { it.id }.toSet())
        assertTrue(first.error.orEmpty().contains("第 2 页"))
        assertEquals(null, updates.single { it.source.id == b.id && it.done }.error)
    }

    @Test fun deadlineCancelsTheCurrentPageAndRetainsCompletedPage() = runBlocking {
        val updates = mutableListOf<SourceSearchUpdate>()
        var interrupted = false
        searchCatalogPages(listOf(a), "短片", 250, { _, _, page ->
            if (page == 1) CatalogPage(listOf(one), 2) else try {
                awaitCancellation()
            } finally { interrupted = true }
        }, updates::add)
        assertTrue(interrupted)
        val last = updates.last()
        assertTrue(last.done && last.error.orEmpty().contains("超时"))
        assertEquals(1, last.pages)
        assertEquals(listOf(one), last.films)
    }

    @Test fun cancellingOldSearchSuppressesItsTerminalUpdate() = runBlocking {
        val firstPage = CompletableDeferred<Unit>()
        val updates = mutableListOf<SourceSearchUpdate>()
        val job = launch {
            searchCatalogPages(listOf(a), "旧词", 2_000, { _, _, page ->
                if (page == 1) CatalogPage(listOf(one), 2) else awaitCancellation()
            }) {
                updates += it
                firstPage.complete(Unit)
            }
        }
        firstPage.await()
        job.cancelAndJoin()
        assertFalse(updates.any { it.done })
        searchCatalogPages(listOf(a), "新词", 2_000, { _, _, _ -> CatalogPage(listOf(two), 1) }, updates::add)
        assertEquals(listOf(two), updates.single { it.done }.films)
    }

    @Test fun categoryFilteringAndNormalizedExactRankingApplyToIntermediateAndFinalResults() = runBlocking {
        val partial = Film("1", "A海岸番外", "", "剧情")
        val exact = Film("2", "Ａ · 海 岸！", "", "纪录片")
        val hidden = Film("3", "A海岸", "", "伦理片")
        val updates = mutableListOf<SourceSearchUpdate>()
        searchCatalogPages(listOf(a), "a海岸", 2_000, { _, _, _ -> CatalogPage(listOf(partial, hidden, exact), 2) }, updates::add)
        assertTrue(updates.all { it.films == listOf(exact, partial) })
        val page = parseCatalogPage("""{"pagecount":"100","list":[{"vod_id":17,"vod_name":"短片","type_name":"纪录片"}]}""")
        assertEquals(100, page.totalPages)
        assertEquals("纪录片", page.films.single().category)
        assertFalse(isAdultCategory("家庭伦理剧"))
        assertFalse(isAdultCategory("成人教育"))
    }

    @Test fun catalogTransportLimitsMixedSearchDetailsAndChecksToFourPhysicalCalls() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstFour = CountDownLatch(4)
        val release = CountDownLatch(1)
        val client = catalogClient.newBuilder().addInterceptor { chain ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            firstFour.countDown()
            try {
                check(release.await(3, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"list":[]}""".toResponseBody()).build()
            } finally { active.decrementAndGet() }
        }.build()
        val calls = (1..9).map { i ->
            async {
                val params = when (i % 3) {
                    0 -> mapOf("wd" to "短片", "pg" to "1")
                    1 -> mapOf("ids" to i.toString())
                    else -> mapOf("pg" to "1")
                }
                fetchCatalog(catalogUrl(a, params), client)
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { firstFour.await(2, TimeUnit.SECONDS) })
            delay(20)
            assertEquals(4, peak.get())
            assertEquals(5, client.dispatcher.queuedCallsCount())
        } finally { release.countDown() }
        calls.awaitAll()
        assertEquals(4, peak.get())
    }
}
