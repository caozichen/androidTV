package dev.libretv.android

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

data class CmsSource(val name: String, val url: String)
data class Film(val id: String, val title: String, val year: String)
data class Episode(val name: String, val url: String)
data class PlayLine(val name: String, val episodes: List<Episode>)

private const val MAX_BYTES = 4L * 1024 * 1024
private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()

suspend fun searchCatalog(source: CmsSource, query: String): List<Film> {
    val keyword = query.trim()
    require(keyword.isNotEmpty() && keyword.length <= 100) { "搜索词应为 1 至 100 个字符" }
    val url = catalogUrl(source, mapOf("wd" to keyword, "pg" to "1"))
    val json = fetchCatalog(url)
    return withContext(Dispatchers.Default) { parseFilms(json) }
}

suspend fun loadLines(source: CmsSource, id: String): List<PlayLine> {
    require(id.isNotBlank()) { "影片标识为空" }
    val url = catalogUrl(source, mapOf("ids" to id))
    val json = fetchCatalog(url)
    return withContext(Dispatchers.Default) { parseLines(json, id) }
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

private suspend fun fetchCatalog(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
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
    if (!response.isSuccessful) throw IOException("点播源返回 HTTP ${response.code}")
    val body = response.body ?: throw IOException("点播源返回空响应")
    if (body.contentLength() > MAX_BYTES || body.source().request(MAX_BYTES + 1)) {
        throw IOException("点播源响应超过 4 MiB")
    }
    return body.string().removePrefix("\uFEFF")
}

private fun resultList(json: String): JSONArray {
    val root = JSONObject(json)
    if (root.has("code") && root.optString("code") == "0") throw IOException("点播源返回业务错误")
    if (!root.has("list")) throw JSONException("点播源缺少 list 字段")
    if (root.isNull("list")) return JSONArray()
    return root.getJSONArray("list")
}

private fun JSONObject.field(name: String): String =
    if (isNull(name)) "" else optString(name).trim()

internal fun parseFilms(json: String): List<Film> {
    val rows = resultList(json)
    val films = linkedMapOf<String, Film>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val id = row.field("vod_id")
        val title = row.field("vod_name")
        if (id.isNotEmpty() && title.isNotEmpty() && id !in films) {
            films[id] = Film(id, title, row.field("vod_year"))
        }
    }
    return films.values.toList()
}

internal fun parseLines(json: String, id: String): List<PlayLine> {
    val rows = resultList(json)
    val film = (0 until rows.length()).asSequence().mapNotNull { rows.optJSONObject(it) }
        .firstOrNull { it.field("vod_id") == id || (rows.length() == 1 && it.field("vod_id").isEmpty()) }
        ?: return emptyList()
    val names = film.field("vod_play_from").split("$$$")
    val lines = film.field("vod_play_url").split("$$$").mapIndexedNotNull { index, group ->
        val episodes = group.split('#').mapIndexedNotNull { position, raw -> parseEpisode(position, raw) }
        if (episodes.isEmpty()) return@mapIndexedNotNull null
        val name = names.getOrNull(index)?.trim().orEmpty().ifEmpty { "线路 ${index + 1}" }
        PlayLine(name, episodes)
    }
    return lines
}

private fun parseEpisode(index: Int, raw: String): Episode? {
    val entry = raw.trim()
    val delimiter = if (entry.startsWith("https://", ignoreCase = true)) -1 else entry.indexOf('$')
    val label = if (delimiter < 0) "" else entry.substring(0, delimiter).trim()
    val url = entry.substring(delimiter + 1).trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath.lowercase()
    // Known web-player routes return HTML rather than a media stream. Keep extensionless media APIs.
    if (path.endsWith(".html") || path.endsWith(".htm") || path.startsWith("/share/")) return null
    return Episode(label.ifEmpty { "第 ${index + 1} 集" }, url.toString())
}
