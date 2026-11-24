package dev.libretv.android

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SourceCandidate(val name: String, val url: String, val detail: String? = null, val isAdult: Boolean = false)

data class ImportPreview(
    val additions: List<SourceCandidate>,
    val duplicates: List<String>,
    val invalid: List<String>,
    val skippedLive: List<String>,
    val overLimit: Boolean,
) {
    val liveCount get() = skippedLive.size
}

private const val FILE_LIMIT_BYTES = 1024 * 1024
internal const val SOURCE_LIMIT = 100

fun readSourceText(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val block = ByteArray(8192)
    while (true) {
        val remaining = FILE_LIMIT_BYTES - bytes.size()
        val count = input.read(block, 0, minOf(block.size, remaining + 1))
        if (count == -1) break
        require(count <= remaining) { "文件超过 1 MiB" }
        bytes.write(block, 0, count)
    }
    return try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("请使用 UTF-8 编码的 JSON 文件")
    }
}

private fun endpoint(raw: String): HttpUrl {
    val text = raw.trim()
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }
    val url = text.toHttpUrlOrNull()
    require(uri != null && !uri.rawAuthority.isNullOrEmpty() && uri.rawUserInfo == null &&
        url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
        "请输入有效的 HTTPS 地址，地址中不可含用户名或密码"
    }
    return url.newBuilder().apply {
        if (url.encodedQuery.isNullOrEmpty()) encodedQuery(null)
        fragment(null)
    }.build()
}

// LibreTV treats a final slash as equivalent for deduplication. The server still receives its original path.
fun sourceIdentity(url: String): String {
    val endpoint = endpoint(url)
    return endpoint.newBuilder().encodedPath(endpoint.encodedPath.trimEnd('/').ifEmpty { "/" }).build().toString()
}

fun manualSource(name: String, url: String): SourceCandidate {
    val normalized = endpoint(url)
    return SourceCandidate(name.trim().ifEmpty { normalized.host }, normalized.toString())
}

private fun JsonObject.optionalString(key: String): String? {
    val field = get(key) ?: return null
    if (field.isJsonNull) return null
    require(field.isJsonPrimitive && field.asJsonPrimitive.isString) { "$key 应为字符串" }
    return field.asString.trim().ifEmpty { null }
}

private fun parseRow(item: JsonObject): SourceCandidate {
    val url = item.optionalString("url") ?: throw IllegalArgumentException("缺少点播源地址")
    val source = manualSource(item.optionalString("name").orEmpty(), url)
    val detail = item.optionalString("detail")?.let {
        try {
            endpoint(it).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("详情页根地址须为有效的 HTTPS 地址")
        }
    }
    val adultFlag = item.get("isAdult")
    require(adultFlag == null || (adultFlag.isJsonPrimitive && adultFlag.asJsonPrimitive.isBoolean)) {
        "isAdult 应为布尔值 true 或 false"
    }
    return source.copy(detail = detail, isAdult = adultFlag?.asBoolean ?: false)
}

private fun readSourceJson(text: String): JsonElement {
    try {
        JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
            reader.strictness = Strictness.STRICT
            val root = JsonParser.parseReader(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON 结束后含有其他内容" }
            return root
        }
    } catch (_: JsonParseException) {
        throw IllegalArgumentException("JSON 格式错误")
    } catch (_: IOException) {
        throw IllegalArgumentException("JSON 格式错误")
    }
}

private fun JsonObject.arrayField(key: String): JsonArray {
    val value = get(key) ?: return JsonArray()
    require(value.isJsonArray) { "$key 应为数组" }
    return value.asJsonArray
}

fun parseSourceList(text: String, existing: Set<String>): ImportPreview {
    require(text.length <= FILE_LIMIT_BYTES && text.toByteArray(Charsets.UTF_8).size <= FILE_LIMIT_BYTES) { "文件超过 1 MiB" }
    val root = readSourceJson(text)
    val rows: JsonArray
    val liveRows: JsonArray
    when {
        root.isJsonArray -> {
            rows = root.asJsonArray
            liveRows = JsonArray()
        }
        root.isJsonObject -> {
            rows = root.asJsonObject.arrayField("sources")
            liveRows = root.asJsonObject.arrayField("liveSources")
        }
        else -> throw IllegalArgumentException("请选择 LibreTV 点播源列表或源数组")
    }
    require(rows.size() > 0 || liveRows.size() > 0) { "文件中没有点播源或直播源" }
    val known = existing.mapTo(mutableSetOf()) { runCatching { sourceIdentity(it) }.getOrDefault(it) }
    val incoming = mutableSetOf<String>()
    val additions = mutableListOf<SourceCandidate>()
    val duplicates = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    for ((index, rawRow) in rows.withIndex()) {
        val row = rawRow.takeIf { it.isJsonObject }?.asJsonObject
        val name = row?.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
        val entry = if (name.length in 1..80 && name.all { it.isLetterOrDigit() || it in " -_" }) {
            "第 ${index + 1} 项（$name）"
        } else {
            "第 ${index + 1} 项"
        }
        val candidate = try {
            parseRow(row ?: throw IllegalArgumentException("条目应为对象"))
        } catch (failure: IllegalArgumentException) {
            invalid.add("$entry · ${failure.message}")
            continue
        }
        val identity = sourceIdentity(candidate.url)
        when {
            identity in known -> duplicates.add("$entry · 与已有点播源重复")
            !incoming.add(identity) -> duplicates.add("$entry · 与文件内点播源重复")
            else -> additions.add(candidate)
        }
    }
    val skippedLive = liveRows.mapIndexed { index, row ->
        val name = row.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        name?.takeIf { it.isNotEmpty() } ?: "第 ${index + 1} 项"
    }
    return ImportPreview(additions, duplicates, invalid, skippedLive, known.size + additions.size > SOURCE_LIMIT)
}
