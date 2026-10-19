package com.wenix.xmediasaver.resolver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多源推文解析器（免登录）：
 * 1) fxtwitter 公共 API（v2 → v1 兜底）
 * 2) twitter 官方 syndication 嵌入接口
 * 任一源成功即返回，全部失败时报出各自原因。
 * 文章（Articles）仅 fxtwitter 支持。
 */
class TweetResolver(private val client: OkHttpClient) {

    /** 解析结果：普通推文 或 文章 */
    sealed interface Result {
        data class Tweet(val data: TweetData) : Result
        data class Article(val data: ArticleData) : Result
    }

    /** 先走 fxtwitter 识别文章；文章只在 fxtwitter 源可用 */
    suspend fun resolveAny(statusId: String): Result = withContext(Dispatchers.IO) {
        // 文章探测：fxtwitter v2
        runCatching {
            val json = getJson("https://api.fxtwitter.com/2/status/$statusId")
            val status = json?.optJSONObject("status")
            val article = status?.optJSONObject("article")
            if (article != null && article.optJSONObject("content")?.optJSONArray("blocks")?.length() ?: 0 > 0) {
                return@withContext Result.Article(ArticleParser.parse(status, article))
            }
        }
        Result.Tweet(resolve(statusId))
    }

    /** t.co 短链跟随重定向，拿到真实推文 URL */
    suspend fun expandShortLink(url: String): String = withContext(Dispatchers.IO) {
        if (!url.contains("://t.co/", ignoreCase = true)) return@withContext url
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
            client.newCall(request).execute().use { resp -> resp.request.url.toString() }
        }.getOrDefault(url)
    }

    suspend fun resolve(statusId: String): TweetData = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        for (fetch in listOf<suspend (String) -> TweetData>(::fromFxTwitter, ::fromSyndication)) {
            try {
                return@withContext fetch(statusId)
            } catch (e: Exception) {
                problems += (e.message ?: e.javaClass.simpleName)
            }
        }
        error("解析失败，请稍后重试或换一条链接（${problems.joinToString("；")}）")
    }

    // ---------- fxtwitter ----------

    private suspend fun fromFxTwitter(id: String): TweetData {
        val json = getJson("https://api.fxtwitter.com/2/status/$id")
            ?: getJson("https://api.fxtwitter.com/_/status/$id")
            ?: error("fxtwitter 无响应")
        val code = json.optInt("code", 200)
        if (code != 200) error("fxtwitter HTTP $code（推文可能已删除或受限）")
        val status = json.optJSONObject("status") ?: json.optJSONObject("tweet")
            ?: error("fxtwitter 响应格式异常")
        val author = status.optJSONObject("author")
        val mediaJson = status.optJSONObject("media")

        val media = mutableListOf<MediaItem>()
        mediaJson?.optJSONArray("photos")?.forEachObj { p ->
            media += MediaItem(
                kind = MediaKind.PHOTO,
                url = p.optString("url"),
                width = p.optInt("width", 0).takeIf { it > 0 },
                height = p.optInt("height", 0).takeIf { it > 0 },
            )
        }
        mediaJson?.optJSONArray("videos")?.forEachObj { v ->
            val url = bestMp4(v.optJSONArray("formats"), v.optString("url"))
            if (url.isNotBlank()) {
                media += MediaItem(
                    kind = if (v.optString("type") == "gif") MediaKind.GIF else MediaKind.VIDEO,
                    url = url,
                    thumbUrl = v.optString("thumbnail_url").ifBlank { null },
                    width = v.optInt("width", 0).takeIf { it > 0 },
                    height = v.optInt("height", 0).takeIf { it > 0 },
                    bitrate = v.optLong("bitrate", 0).takeIf { it > 0 },
                )
            }
        }
        if (media.isEmpty()) Log.d(TAG, "fx resolved id=$id media=0（纯文本推文）")
        else Log.d(TAG, "fx resolved id=$id media=${media.size}")
        val handle = author?.optString("screen_name")?.ifBlank { null } ?: "user"
        return TweetData(
            id = status.optString("id", id),
            authorName = author?.optString("name")?.ifBlank { null } ?: "unknown",
            authorHandle = handle,
            avatarUrl = author?.optString("avatar_url")?.ifBlank { null },
            text = status.optString("text"),
            createdAt = status.optString("created_at").ifBlank { null },
            media = media,
            url = status.optString("url").ifBlank { "https://x.com/$handle/status/$id" },
        )
    }

    private fun bestMp4(formats: JSONArray?, fallback: String): String {
        if (formats == null) return fallback
        var bestUrl = ""
        var bestRate = -1L
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val container = f.optString("container")
            if (container.isNotEmpty() && container != "mp4") continue
            val url = f.optString("url")
            if (url.isBlank()) continue
            val rate = f.optLong("bitrate", 0)
            if (rate > bestRate) {
                bestRate = rate
                bestUrl = url
            }
        }
        return bestUrl.ifBlank { fallback }
    }

    // ---------- 官方 syndication ----------

    private suspend fun fromSyndication(id: String): TweetData {
        val json = getJson("https://cdn.syndication.twimg.com/tweet-result?id=$id&token=$SYNDICATION_TOKEN&lang=zh")
            ?: error("syndication 无响应")
        val user = json.optJSONObject("user") ?: error("syndication 数据不完整")
        val details = json.optJSONArray("mediaDetails")

        val media = mutableListOf<MediaItem>()
        for (i in 0 until (details?.length() ?: 0)) {
            val d = details?.optJSONObject(i) ?: continue
            val size = d.optJSONObject("original_info")
            val width = size?.optInt("width", 0)?.takeIf { it > 0 }
            val height = size?.optInt("height", 0)?.takeIf { it > 0 }
            when (d.optString("type")) {
                "photo" -> {
                    val base = d.optString("media_url_https")
                    if (base.isNotBlank()) {
                        media += MediaItem(
                            kind = MediaKind.PHOTO,
                            url = if (base.contains('?')) base else "$base?name=orig",
                            width = width,
                            height = height,
                        )
                    }
                }
                "video", "animated_gif" -> {
                    val variants = d.optJSONObject("video_info")?.optJSONArray("variants")
                    val best = pickVariant(variants) ?: continue
                    media += MediaItem(
                        kind = if (d.optString("type") == "animated_gif") MediaKind.GIF else MediaKind.VIDEO,
                        url = best.second,
                        thumbUrl = d.optString("media_url_https").ifBlank { null },
                        width = width,
                        height = height,
                        bitrate = best.first.takeIf { it > 0 },
                    )
                }
            }
        }
        if (media.isEmpty()) Log.d(TAG, "synd resolved id=$id media=0（纯文本推文）")
        else Log.d(TAG, "synd resolved id=$id mediaDetails=${details!!.length()}")
        val handle = user.optString("screen_name").ifBlank { "user" }
        return TweetData(
            id = json.optString("id_str", id),
            authorName = user.optString("name").ifBlank { "unknown" },
            authorHandle = handle,
            avatarUrl = user.optString("profile_image_url_https").ifBlank { null },
            text = json.optString("text"),
            createdAt = json.optString("created_at").ifBlank { null },
            media = media,
            url = "https://x.com/$handle/status/${json.optString("id_str", id)}",
        )
    }

    private fun pickVariant(variants: JSONArray?): Pair<Long, String>? {
        if (variants == null) return null
        var best: Pair<Long, String>? = null
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            if (v.optString("content_type") != "video/mp4") continue
            val rate = v.optLong("bitrate", 0)
            if (best == null || rate > best.first) best = rate to v.optString("url")
        }
        return best
    }

    // ---------- HTTP ----------

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "[$url] HTTP ${resp.code}")
                return@use null
            }
            val text = resp.body?.string() ?: return@use null
            Log.d(TAG, "[$url] body ${text.length} chars: ${text.take(220)}")
            if (!text.trimStart().startsWith("{")) return@use null
            JSONObject(text)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "XMediaResolver"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        // syndication 接口只需任意 32 位 hex token（实测校验宽松）
        private const val SYNDICATION_TOKEN = "30616a9e10d67100f0eb2fe47d54ba79"
    }
}

private inline fun JSONArray.forEachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
