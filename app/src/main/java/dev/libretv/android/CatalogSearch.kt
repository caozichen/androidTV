package dev.libretv.android

import java.text.Normalizer
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CatalogPage(val films: List<Film>, val totalPages: Int)
internal data class SourceSearchUpdate(
    val source: CmsSource,
    val films: List<Film>,
    val pages: Int,
    val done: Boolean,
    val error: String? = null,
    val truncated: Boolean = false,
)

private val punctuation = Regex("[\\p{Z}\\p{P}\\p{S}\\s]+")
private val adultCategoryNames = setOf("成人", "伦理", "倫理", "福利", "三级", "三級", "18+")
private val adultCategories = listOf("成人片", "成人电影", "成人动漫", "情色", "色情", "伦理片", "倫理片", "福利片", "里番", "无码", "無碼", "18禁", "三级片", "三級片")
private val englishAdultCategory = Regex("\\b(?:adult|xxx|porn)\\b", RegexOption.IGNORE_CASE)

internal fun normalizeCatalogTitle(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(punctuation, "")

internal fun isAdultCategory(category: String): Boolean =
    category.trim() in adultCategoryNames || adultCategories.any { it in category } || englishAdultCategory.containsMatchIn(category)

internal fun rankFilms(films: List<Film>, keyword: String, filterAdult: Boolean = true): List<Film> {
    val exact = normalizeCatalogTitle(keyword)
    return films.filterNot { filterAdult && isAdultCategory(it.category) }
        .map { it to normalizeCatalogTitle(it.title) }
        .sortedWith(compareByDescending<Pair<Film, String>> { it.second == exact }.thenBy { it.second }.thenByDescending { it.first.year })
        .map { it.first }
}

internal suspend fun runCatalogSearch(
    sources: List<CmsSource>,
    keyword: String,
    filterAdult: Boolean = true,
    onUpdate: (SourceSearchUpdate) -> Unit,
) = searchCatalogPages(sources, keyword, 10_000, filterAdult, ::fetchSearchPage, onUpdate)

internal suspend fun searchCatalogPages(
    sources: List<CmsSource>,
    keyword: String,
    deadlineMs: Long,
    fetch: suspend (CmsSource, String, Int) -> CatalogPage,
    onUpdate: (SourceSearchUpdate) -> Unit,
) = searchCatalogPages(sources, keyword, deadlineMs, true, fetch, onUpdate)

internal suspend fun searchCatalogPages(
    sources: List<CmsSource>,
    keyword: String,
    deadlineMs: Long,
    filterAdult: Boolean,
    fetch: suspend (CmsSource, String, Int) -> CatalogPage,
    onUpdate: (SourceSearchUpdate) -> Unit,
) {
    val query = keyword.trim()
    require(query.isNotEmpty() && query.length <= 100) { "搜索词应为 1 至 100 个字符" }
    channelFlow {
        val submitted = CompletableDeferred<Unit>()
        val deadline = TimeSource.Monotonic.markNow() + deadlineMs.milliseconds
        for (source in sources.distinctBy { it.id }) launch(start = CoroutineStart.UNDISPATCHED) {
            val films = linkedMapOf<String, Film>()
            var ranked = emptyList<Film>()
            var completedPages = 0
            var total = 1
            var page = 1
            var error: String? = null
            var truncated = false
            val dispatch = SearchDispatchState(deadline)
            val completed = withTimeoutOrNull(-deadline.elapsedNow()) {
                while (page <= total) {
                    val result = try {
                        withContext(dispatch) { fetch(source, query, page) }
                    } catch (_: CatalogQueueTimeout) {
                        return@withTimeoutOrNull false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (error == null) error = "第 ${page} 页 · ${catalogErrorText(e)}"
                        page++
                        continue
                    }
                    if (page == 1) {
                        truncated = result.totalPages > 5
                        total = result.totalPages.coerceIn(1, 5)
                    }
                    ranked = withContext(Dispatchers.Default) {
                        for (film in result.films) {
                            if (!filterAdult || !isAdultCategory(film.category)) films.putIfAbsent(film.id, film)
                        }
                        rankFilms(films.values.toList(), query, filterAdult = false)
                    }
                    completedPages++
                    if (page < total) send(SourceSearchUpdate(source, ranked, completedPages, done = false, error, truncated))
                    if (page == 1 && total > 1) submitted.await()
                    page++
                }
                true
            }
            if (completed != true) error = if (dispatch.waitingForSlot) {
                "等待请求名额超时，请重试"
            } else {
                "点播源搜索超时，请稍后重试或换源"
            }
            send(SourceSearchUpdate(source, ranked, completedPages, done = true, error, truncated))
        }
        // Each source has entered its homepage fetch before pagination can enqueue more HTTP calls.
        submitted.complete(Unit)
    }.collect { update ->
        currentCoroutineContext().ensureActive()
        onUpdate(update)
    }
}
