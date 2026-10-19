package com.wenix.xmediasaver.resolver

import java.net.URI

/** 从任意分享文本中提取 X / Twitter 推文链接与推文 ID */
object LinkExtractor {

    private val URL_TOKEN = Regex("""https?://[^\s<>"'，。；）】]+""", RegexOption.IGNORE_CASE)

    // /<user>/status/<id>，兼容 /i/web/status/<id> 与 /statuses/<id>
    private val STATUS_ID = Regex("""/(?:i/web/)?status(?:es)?/(\d{5,25})""")

    private val HOSTS = setOf(
        "x.com", "www.x.com",
        "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com",
        "t.co",
        "fxtwitter.com", "www.fxtwitter.com",
        "fixupx.com", "www.fixupx.com",
        "vxtwitter.com", "www.vxtwitter.com",
    )

    /** 挑出文本里第一条 X/Twitter 相关链接（含 t.co 短链） */
    fun extractUrl(text: String): String? =
        URL_TOKEN.findAll(text)
            .map { it.value.trimEnd('.', ',', ')') }
            .filter { it.isNotBlank() }
            .firstOrNull { hostOf(it) != null }

    /** 从链接中提取推文 ID；短链需先展开 */
    fun statusIdFrom(url: String): String? =
        STATUS_ID.find(url)?.groupValues?.getOrNull(1)

    private fun hostOf(url: String): String? = runCatching {
        val host = URI(url).host?.lowercase() ?: return@runCatching null
        if (host in HOSTS || host.endsWith(".t.co")) host else null
    }.getOrNull()
}
