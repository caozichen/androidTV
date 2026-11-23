package dev.libretv.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMediaPathTest {
    @Test fun shareDirectoryAcceptsDirectMediaAndSkipsWebPlayerLinks() {
        val hls = "https://media.example.org/share/17/master.M3U8?sig=x"
        val mp4 = "https://media.example.org/share/17/intro.mp4"
        val webPlayer = "https://media.example.org/share/17?next=master.m3u8"
        val webPage = "https://media.example.org/share/watch.html?next=movie.mp4"
        val film = JSONObject().put("vod_id", 17)
            .put("vod_play_url", listOf(hls, mp4, webPlayer, webPage).joinToString("#"))
        val detail = JSONObject().put("list", JSONArray().put(film)).toString()
        assertEquals(listOf(hls, mp4), parseLines(detail, "17").single().episodes.map { it.url })
    }
}
