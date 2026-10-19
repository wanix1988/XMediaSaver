package com.wenix.xmediasaver.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 轻量下载历史：JSON 文件持久化，内存缓存，最多保留 50 条 */
class HistoryStore(context: Context) {

    data class Entry(
        val timeMs: Long,
        val authorHandle: String,
        val text: String,
        val tweetId: String,
        val fileCount: Int,
        /** 本地文件 uri（content:// 或 file://），点击记录时打开第一个 */
        val uris: List<String> = emptyList(),
        val mime: String? = null,
    )

    private val file = File(context.filesDir, "history.json")
    private val cache = mutableListOf<Entry>()

    init {
        runCatching { load() }
    }

    fun entries(): List<Entry> = cache.toList()

    @Synchronized
    fun add(
        timeMs: Long,
        authorHandle: String,
        text: String,
        tweetId: String,
        fileCount: Int,
        uris: List<String> = emptyList(),
        mime: String? = null,
    ) {
        cache.add(0, Entry(timeMs, authorHandle, text, tweetId, fileCount, uris, mime))
        while (cache.size > MAX_ENTRIES) cache.removeAt(cache.size - 1)
        runCatching { save() }
    }

    private fun load() {
        if (!file.exists()) return
        val arr = JSONArray(file.readText())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            cache += Entry(
                timeMs = o.optLong("t"),
                authorHandle = o.optString("h"),
                text = o.optString("x"),
                tweetId = o.optString("id"),
                fileCount = o.optInt("n"),
                uris = o.optJSONArray("u")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optString(j).ifBlank { null } }
                } ?: emptyList(),
                mime = o.optString("m").ifBlank { null },
            )
        }
    }

    private fun save() {
        val arr = JSONArray()
        cache.forEach { e ->
            arr.put(
                JSONObject()
                    .put("t", e.timeMs)
                    .put("h", e.authorHandle)
                    .put("x", e.text)
                    .put("id", e.tweetId)
                    .put("n", e.fileCount)
                    .put("u", JSONArray(e.uris))
                    .put("m", e.mime ?: ""),
            )
        }
        file.writeText(arr.toString())
    }

    companion object {
        private const val MAX_ENTRIES = 50
    }
}
