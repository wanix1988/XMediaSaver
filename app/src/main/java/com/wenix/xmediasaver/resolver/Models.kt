package com.wenix.xmediasaver.resolver

/** 媒体类型：图片 / 视频 / 动图（X 的 GIF 实际是短 MP4） */
enum class MediaKind { PHOTO, VIDEO, GIF }

data class MediaItem(
    val kind: MediaKind,
    val url: String,
    val thumbUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
)

data class TweetData(
    val id: String,
    val authorName: String,
    val authorHandle: String,
    val avatarUrl: String? = null,
    val text: String,
    val createdAt: String? = null,
    val media: List<MediaItem>,
)

// ---------------- X 文章（Articles） ----------------

/** 文章正文里的内嵌元素：图片 / 视频 / 内嵌推文 / 代码块 */
sealed interface ArticleEmbed {
    data class Image(val url: String, val alt: String?, val width: Int?, val height: Int?) : ArticleEmbed
    data class Video(val url: String, val thumbUrl: String?, val width: Int?, val height: Int?) : ArticleEmbed
    data class Tweet(val tweetId: String) : ArticleEmbed
    data class Code(val language: String?, val content: String) : ArticleEmbed
}

/** 渲染后的文章元素流：PDF 渲染器直接消费 */
sealed interface ArticleElement {
    data class Heading(val text: String, val level: Int) : ArticleElement
    data class Paragraph(val spans: List<Span>) : ArticleElement
    data class BlockQuote(val text: String) : ArticleElement
    data class ListItem(val spans: List<Span>, val ordered: Boolean, val order: Int) : ArticleElement
    data class Divider(val dummy: Unit = Unit) : ArticleElement
    data class Embed(val embed: ArticleEmbed) : ArticleElement
}

/** 段内富文本：加粗/斜体/删除线 + 链接 */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

data class ArticleData(
    val id: String,
    val url: String,
    val title: String,
    val previewText: String,
    val authorName: String,
    val authorHandle: String,
    val avatarUrl: String? = null,
    val createdAt: String? = null,
    val coverUrl: String? = null,
    val coverAlt: String? = null,
    val elements: List<ArticleElement>,
)
