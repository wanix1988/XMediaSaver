package com.wenix.xmediasaver.resolver

import org.json.JSONArray
import org.json.JSONObject

/**
 * X 文章解析：fxtwitter 返回的 Draft.js 风格 blocks/entityMap
 * -> 转换为线性 ArticleElement 流（参照 FxEmbed src/helpers/article.ts 的处理逻辑）。
 */
object ArticleParser {

    fun parse(statusJson: JSONObject, articleJson: JSONObject): ArticleData {
        val author = statusJson.optJSONObject("author")
        val content = articleJson.optJSONObject("content") ?: JSONObject()
        val blocks = content.optJSONArray("blocks") ?: JSONArray()
        val entityMap = HashMap<String, JSONObject>()
        content.optJSONArray("entityMap")?.forEachObj { e ->
            e.optString("key").ifBlank { null }?.let { entityMap[it] = e.optJSONObject("value") ?: JSONObject() }
        }
        val mediaEntities = HashMap<String, JSONObject>()
        articleJson.optJSONArray("media_entities")?.forEachObj { m ->
            m.optString("media_id").ifBlank { null }?.let { mediaEntities[it] = m }
        }

        val elements = mutableListOf<ArticleElement>()
        var listOrder = 0

        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val type = block.optString("type")
            val rawText = block.optString("text")

            if (type == "atomic") {
                val embed = resolveAtomic(block, entityMap, mediaEntities)
                if (embed != null) {
                    elements += ArticleElement.Embed(embed)
                    listOrder = 0
                }
                continue
            }

            val spans = buildSpans(block, rawText, entityMap)
            listOrder = when (type) {
                "unordered-list-item" -> { elements += ArticleElement.ListItem(spans, ordered = false, order = 0); 0 }
                "ordered-list-item" -> {
                    val next = listOrder + 1
                    elements += ArticleElement.ListItem(spans, ordered = true, order = next)
                    next
                }
                "header-one" -> { elements += ArticleElement.Heading(rawText, 1); 0 }
                "header-two" -> { elements += ArticleElement.Heading(rawText, 2); 0 }
                "header-three" -> { elements += ArticleElement.Heading(rawText, 3); 0 }
                "blockquote" -> { elements += ArticleElement.BlockQuote(rawText); 0 }
                "code-block" -> { elements += ArticleElement.Embed(ArticleEmbed.Code(language = null, content = rawText)); 0 }
                else -> {
                    if (rawText.isNotBlank() || spans.isNotEmpty()) {
                        elements += ArticleElement.Paragraph(spans)
                    }
                    0
                }
            }
        }

        val cover = articleJson.optJSONObject("cover_media")
        val coverInfo = cover?.optJSONObject("media_info")

        return ArticleData(
            id = articleJson.optString("id").ifBlank { statusJson.optString("id") },
            url = statusJson.optString("url"),
            title = articleJson.optString("title").ifBlank { "X 文章" },
            previewText = articleJson.optString("preview_text"),
            authorName = author?.optString("name")?.ifBlank { null } ?: "unknown",
            authorHandle = author?.optString("screen_name")?.ifBlank { null } ?: "user",
            avatarUrl = author?.optString("avatar_url")?.ifBlank { null },
            createdAt = articleJson.optString("created_at").ifBlank { null },
            coverUrl = coverInfo?.optString("original_img_url")?.ifBlank { null },
            coverAlt = coverInfo?.optString("ext_alt_text")?.ifBlank { null },
            elements = elements,
        )
    }

    /** atomic 块：占位文本指向 entityMap（MEDIA/TWEET/MARKDOWN） */
    private fun resolveAtomic(
        block: JSONObject,
        entityMap: Map<String, JSONObject>,
        mediaEntities: Map<String, JSONObject>,
    ): ArticleEmbed? {
        val ranges = block.optJSONArray("entityRanges") ?: return null
        for (i in 0 until ranges.length()) {
            val key = ranges.optJSONObject(i)?.optString("key") ?: continue
            val entity = entityMap[key] ?: continue
            when (entity.optString("type")) {
                "MEDIA" -> {
                    val mediaItem = (entity.optJSONObject("data") ?: continue)
                        .optJSONArray("mediaItems")?.optJSONObject(0) ?: continue
                    val mediaId = mediaItem.optString("mediaId")
                    val media = mediaEntities[mediaId] ?: continue
                    val info = media.optJSONObject("media_info") ?: continue
                    return when (info.optString("__typename")) {
                        "ApiImage" -> ArticleEmbed.Image(
                            url = info.optString("original_img_url"),
                            alt = info.optString("ext_alt_text").ifBlank { null },
                            width = info.optInt("original_img_width", 0).takeIf { it > 0 },
                            height = info.optInt("original_img_height", 0).takeIf { it > 0 },
                        )
                        else -> {
                            // 视频：variants 直接在 media_info 上（文章视频结构）
                            val variants = info.optJSONArray("variants")
                            val best = pickMp4(variants) ?: info.optString("media_url_https").ifBlank { null }
                            if (best == null) return null
                            val origInfo = info.optJSONObject("original_info")
                            ArticleEmbed.Video(
                                url = best,
                                thumbUrl = info.optString("media_url_https").ifBlank { null },
                                width = origInfo?.optInt("width", 0)?.takeIf { it > 0 },
                                height = origInfo?.optInt("height", 0)?.takeIf { it > 0 },
                            )
                        }
                    }
                }
                "TWEET" -> {
                    val tweetId = entity.optJSONObject("data")?.optString("tweetId") ?: continue
                    if (tweetId.isNotBlank()) return ArticleEmbed.Tweet(tweetId)
                }
                "MARKDOWN" -> {
                    val markdown = entity.optJSONObject("data")?.optString("markdown") ?: continue
                    val embed = parseMarkdown(markdown) ?: continue
                    return embed
                }
            }
        }
        return null
    }

    /** MARKDOWN entity 通常是 ```lang … ``` 代码块 */
    private fun parseMarkdown(markdown: String): ArticleEmbed? {
        if (markdown.startsWith("```")) {
            val lines = markdown.split('\n')
            val language = lines.firstOrNull()?.removePrefix("```")?.trim()?.ifBlank { null }
            val code = if (lines.size > 2) lines.subList(1, lines.size - 1).joinToString("\n") else ""
            return ArticleEmbed.Code(language, code)
        }
        return null
    }

    private fun pickMp4(variants: JSONArray?): String? {
        if (variants == null) return null
        var best: String? = null
        var bestRate = -1L
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            val url = v.optString("url")
            if (url.isBlank() || url.contains(".m3u8")) continue
            val rate = v.optLong("bit_rate", v.optLong("bitrate", 0))
            if (rate > bestRate) {
                bestRate = rate
                best = url
            }
        }
        return best
    }

    /** 段内富文本：inlineStyleRanges + entityRanges(LINK 实体) + data.mentions/urls/hashtags/cashtags */
    private fun buildSpans(
        block: JSONObject,
        rawText: String,
        entityMap: Map<String, JSONObject>,
    ): List<Span> {
        if (rawText.isEmpty()) return emptyList()
        val ranges = mutableListOf<Triple<Int, Int, String>>() // start, end, style
        block.optJSONArray("inlineStyleRanges")?.forEachObj { r ->
            val off = r.optInt("offset")
            val len = r.optInt("length")
            if (len > 0) ranges += Triple(off, off + len, r.optString("style"))
        }
        // 链接来源一：block.data 的 spans（mentions/urls/hashtags/cashtags）
        data class LinkInfo(val start: Int, val end: Int, val href: String)
        val links = mutableListOf<LinkInfo>()
        val data = block.optJSONObject("data")
        if (data != null) {
            fun collect(name: String, hrefOf: (String) -> String) {
                data.optJSONArray(name)?.forEachObj { e ->
                    val from = e.optInt("fromIndex")
                    val to = e.optInt("toIndex")
                    val text = e.optString("text")
                    if (to > from && text.isNotBlank()) links += LinkInfo(from, to, hrefOf(text))
                }
            }
            collect("urls") { it }
            collect("mentions") { "https://x.com/$it" }
            collect("hashtags") { "https://x.com/hashtag/$it" }
            collect("cashtags") { "https://x.com/search?q=%24$it" }
        }
        // 链接来源二：entityRanges 指向 entityMap 里的 LINK 实体
        block.optJSONArray("entityRanges")?.forEachObj { r ->
            val entity = entityMap[r.optString("key")] ?: return@forEachObj
            if (entity.optString("type") == "LINK") {
                val href = entity.optJSONObject("data")?.optString("url") ?: return@forEachObj
                val off = r.optInt("offset")
                val len = r.optInt("length")
                if (len > 0 && href.isNotBlank()) links += LinkInfo(off, off + len, href)
            }
        }

        // 事件法切分：样式与链接的起止都作为边界
        data class Boundary(val pos: Int, val isStart: Boolean, val style: String?, val link: LinkInfo?)
        val events = mutableListOf<Boundary>()
        for ((s, e, st) in ranges) {
            events += Boundary(s, true, st, null)
            events += Boundary(e, false, st, null)
        }
        for (l in links) {
            events += Boundary(l.start, true, null, l)
            events += Boundary(l.end, false, null, l)
        }
        // 同位置先关闭后打开
        events.sortWith(compareBy({ it.pos }, { if (it.isStart) 1 else 0 }))

        val spans = mutableListOf<Span>()
        val active = mutableSetOf<String>()
        var activeLink: LinkInfo? = null
        var cursor = 0
        fun emit(end: Int) {
            if (end > cursor) {
                spans += Span(
                    text = rawText.substring(cursor, end),
                    bold = "BOLD" in active || "Bold" in active,
                    italic = "ITALIC" in active || "Italic" in active,
                    strikethrough = "STRIKETHROUGH" in active || "Strikethrough" in active,
                    link = activeLink?.href,
                )
            }
        }
        for (ev in events) {
            if (ev.pos > rawText.length) break
            if (ev.pos > cursor) {
                emit(ev.pos)
                cursor = ev.pos
            }
            when {
                ev.style != null -> if (ev.isStart) active += ev.style else active -= ev.style
                ev.link != null -> activeLink = if (ev.isStart) ev.link else null
            }
        }
        if (cursor < rawText.length) {
            emit(rawText.length)
        }
        return spans.ifEmpty { listOf(Span(rawText)) }
    }
}

private inline fun JSONArray.forEachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
