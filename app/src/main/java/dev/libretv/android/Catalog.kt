package dev.libretv.android

import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class CmsSource(val name: String, val url: String, val id: String = url)
data class Film(val id: String, val title: String, val year: String, val category: String = "")
data class Episode(val name: String, val url: String, val explicitLabel: Boolean = true)
data class PlayLine @JvmOverloads constructor(val name: String, val episodes: List<Episode>, val explicitName: Boolean = true)

internal fun PlayLine.episodeLabels(): List<String> {
    val counts = episodes.groupingBy { it.name }.eachCount()
    val labels = episodes.mapIndexed { index, episode ->
        if (counts[episode.name] == 1) episode.name else "${episode.name}（第 ${index + 1} 项）"
    }
    if (labels.toSet().size == labels.size) return labels
    // An original title can already contain the suffix used to distinguish duplicates.
    return episodes.mapIndexed { index, episode -> "${index + 1} · ${episode.name}" }
}

private const val MAX_BYTES = 4L * 1024 * 1024
private class CatalogHttpError(val status: Int) : IOException("点播源返回 HTTP $status")
private class CatalogResponseError(val displayText: String) : IOException(displayText)

internal fun catalogErrorText(error: Exception): String = when (error) {
    is CatalogHttpError -> if (error.status == 429) {
        "请求过于频繁（HTTP 429），请稍后重试"
    } else {
        "点播源返回 HTTP ${error.status}，可稍后重试或换源"
    }
    is CatalogResponseError -> error.displayText
    is JSONException -> "点播源返回的数据格式无效，请检查片源或换源"
    is UnknownHostException -> "无法解析点播源域名，请检查网络和片源地址"
    is InterruptedIOException -> "请求超时，请稍后重试或换源"
    is IOException -> "网络连接失败，请检查网络后重试"
    is IllegalArgumentException -> "请求参数无效，请检查片源地址或输入内容"
    else -> "请求失败，请重试或换源"
}

// A search source can expire while OkHttp still holds its call in the global dispatcher queue.
internal class SearchDispatchState : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SearchDispatchState>
    @Volatile var started = false
    @Volatile var expiredInQueue = false
}

internal val catalogClient = OkHttpClient.Builder()
    .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
    .addInterceptor { chain ->
        chain.request().tag(SearchDispatchState::class.java)?.started = true
        chain.proceed(chain.request())
    }
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()

internal suspend fun checkCatalog(source: CmsSource) {
    val json = fetchCatalog(catalogUrl(source, mapOf("pg" to "1")))
    withContext(Dispatchers.Default) { resultList(JSONObject(json)) }
}

suspend fun searchCatalog(source: CmsSource, query: String): List<Film> {
    val keyword = query.trim()
    require(keyword.isNotEmpty() && keyword.length <= 100) { "搜索词应为 1 至 100 个字符" }
    return rankFilms(fetchSearchPage(source, keyword, 1).films, keyword)
}

internal suspend fun fetchSearchPage(source: CmsSource, keyword: String, page: Int): CatalogPage {
    val json = fetchCatalog(catalogUrl(source, mapOf("wd" to keyword, "pg" to page.toString())))
    return withContext(Dispatchers.Default) { parseCatalogPage(json) }
}

internal data class CatalogDetail(val film: Film?, val lines: List<PlayLine>) {
    fun confirms(expected: Film): Boolean = film != null && film.id == expected.id &&
        film.year.isNotBlank() && film.year == expected.year && film.title.isNotBlank() &&
        sameFilmTitle(film.title, expected.title)
}

internal suspend fun loadCatalogDetail(source: CmsSource, id: String): CatalogDetail {
    require(id.isNotBlank()) { "影片标识为空" }
    val url = catalogUrl(source, mapOf("ids" to id))
    val json = fetchCatalog(url)
    return withContext(Dispatchers.Default) { parseCatalogDetail(json, id) }
}

internal fun catalogUrl(source: CmsSource, parameters: Map<String, String>): HttpUrl {
    val base = source.url.trim().toHttpUrlOrNull()
    require(base != null && base.isHttps && base.username.isEmpty() && base.password.isEmpty()) {
        "请输入有效的 HTTPS 点播源地址"
    }
    return base.newBuilder().apply {
        fragment(null)
        for (key in listOf("ac", "wd", "pg", "ids")) removeAllQueryParameters(key)
        addQueryParameter("ac", "videolist")
        for ((key, value) in parameters) addQueryParameter(key, value)
    }.build()
}

internal suspend fun fetchCatalog(url: HttpUrl, client: OkHttpClient = catalogClient): String = suspendCancellableCoroutine { continuation ->
    val dispatch = continuation.context[SearchDispatchState]
    dispatch?.started = false
    val request = Request.Builder().url(url).header("Accept", "application/json")
        .tag(SearchDispatchState::class.java, dispatch).build()
    val call = client.newCall(request)
    continuation.invokeOnCancellation {
        if (dispatch != null) dispatch.expiredInQueue = !dispatch.started
        call.cancel()
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                continuation.resume(response.use(::readCatalogResponse))
            } catch (e: IOException) {
                continuation.resumeWithException(e)
            }
        }
    })
}

internal fun readCatalogResponse(response: Response): String {
    if (!response.isSuccessful) throw CatalogHttpError(response.code)
    val body = response.body ?: throw CatalogResponseError("点播源返回空响应，请重试或换源")
    if (body.contentLength() > MAX_BYTES || body.source().request(MAX_BYTES + 1)) {
        throw CatalogResponseError("点播源响应超过 4 MiB，请换源")
    }
    return body.string().removePrefix("\uFEFF")
}

private fun resultList(root: JSONObject): JSONArray {
    if (root.has("code") && root.optString("code") == "0") throw CatalogResponseError("点播源返回业务错误，请重试或换源")
    if (!root.has("list")) throw JSONException("点播源缺少 list 字段")
    if (root.isNull("list")) return JSONArray()
    return root.getJSONArray("list")
}

private fun JSONObject.field(name: String): String =
    if (isNull(name)) "" else optString(name).trim()

internal fun parseFilms(json: String): List<Film> {
    return parseCatalogPage(json).films
}

internal fun parseCatalogPage(json: String): CatalogPage {
    val root = JSONObject(json)
    val rows = resultList(root)
    val films = linkedMapOf<String, Film>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val id = row.field("vod_id")
        val title = row.field("vod_name")
        if (id.isNotEmpty() && title.isNotEmpty() && id !in films) {
            films[id] = Film(id, title, row.field("vod_year"), row.field("type_name"))
        }
    }
    val reported = root.field("pagecount").toLongOrNull() ?: 1L
    return CatalogPage(films.values.toList(), reported.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
}

internal fun parseLines(json: String, id: String): List<PlayLine> = parseCatalogDetail(json, id).lines

internal fun parseCatalogDetail(json: String, id: String): CatalogDetail {
    val rows = resultList(JSONObject(json))
    val film = (0 until rows.length()).asSequence().mapNotNull { rows.optJSONObject(it) }
        .firstOrNull { it.field("vod_id") == id || (rows.length() == 1 && it.field("vod_id").isEmpty()) }
        ?: return CatalogDetail(null, emptyList())
    val names = film.field("vod_play_from").split("$$$")
    val lines = film.field("vod_play_url").split("$$$").mapIndexedNotNull { index, group ->
        val episodes = group.split('#').mapIndexedNotNull { position, raw -> parseEpisode(position, raw) }
        if (episodes.isEmpty()) return@mapIndexedNotNull null
        val name = names.getOrNull(index)?.trim().orEmpty()
        PlayLine(name.ifEmpty { "线路 ${index + 1}" }, episodes, explicitName = name.isNotEmpty())
    }
    return CatalogDetail(Film(film.field("vod_id"), film.field("vod_name"), film.field("vod_year"), film.field("type_name")), lines)
}

private fun parseEpisode(index: Int, raw: String): Episode? {
    val entry = raw.trim()
    val delimiter = if (entry.startsWith("https://", ignoreCase = true)) -1 else entry.indexOf('$')
    val label = if (delimiter < 0) "" else entry.substring(0, delimiter).trim()
    val url = entry.substring(delimiter + 1).trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath.lowercase()
    val directMedia = path.endsWith(".m3u8") || path.endsWith(".mp4")
    if (path.endsWith(".html") || path.endsWith(".htm") || (path.startsWith("/share/") && !directMedia)) return null
    return Episode(label.ifEmpty { "第 ${index + 1} 集" }, url.toString(), explicitLabel = label.isNotEmpty())
}
