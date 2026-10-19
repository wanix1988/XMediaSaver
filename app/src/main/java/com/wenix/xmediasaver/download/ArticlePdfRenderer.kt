package com.wenix.xmediasaver.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import com.wenix.xmediasaver.resolver.ArticleData
import com.wenix.xmediasaver.resolver.ArticleElement
import com.wenix.xmediasaver.resolver.ArticleEmbed
import com.wenix.xmediasaver.resolver.TweetResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream

/**
 * 文章 -> A4 PDF 渲染器。
 *
 * 排版约定：
 * - [Pager] 是唯一的 y 状态持有者；所有元素通过 pager.drawBitmap/drawLayout/requireSpace
 *   写入，杜绝「外层局部 y 与内部 currentY 失同步」导致的图文错位/越界。
 * - 图片统一按内容宽度缩放，封面与正文图都先 ensure 再画。
 * - 页脚画页码；每页结束由 [Pager.finish] 收尾。
 */
class ArticlePdfRenderer(private val client: OkHttpClient) {

    suspend fun render(article: ArticleData, out: OutputStream) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            val pager = Pager(doc)

            // ---------- 标题区 ----------
            pager.drawLayout(staticLayout(article.title, titlePaint))
            pager.gap(10f)
            val meta = buildString {
                append("@${article.authorHandle}")
                article.createdAt?.take(10)?.let { append("  ·  $it") }
                append("  ·  来自 X")
            }
            pager.drawLayout(staticLayout(meta, metaPaint))
            pager.gap(6f)
            pager.drawLine(dividerPaint)
            pager.gap(24f)

            // ---------- 封面 ----------
            article.coverUrl?.let { cover ->
                fetchBitmap(cover)?.let { bmp ->
                    pager.drawBitmap(fitBitmap(bmp, CONTENT_W))
                    article.coverAlt?.let { alt ->
                        pager.drawLayout(staticLayout(alt, captionPaint))
                    }
                    pager.gap(16f)
                }
            }

            // ---------- 正文 ----------
            for (element in article.elements) {
                when (element) {
                    is ArticleElement.Heading -> {
                        val paint = if (element.level <= 1) h1Paint else h2Paint
                        pager.drawLayout(staticLayout(element.text, paint))
                        pager.gap(12f)
                    }

                    is ArticleElement.Paragraph -> {
                        val ssb = spannable(element.spans)
                        val layout = StaticLayout.Builder
                            .obtain(ssb, 0, ssb.length, bodyPaint, CONTENT_W)
                            .setLineSpacing(5f, 1f)
                            .build()
                        pager.drawLayout(layout)
                        pager.gap(9f)
                    }

                    is ArticleElement.BlockQuote -> {
                        val layout = staticLayout(element.text, quotePaint, CONTENT_W - 24)
                        pager.drawLayout(layout, indent = 12f, indentRight = 12f) { cv, top, h ->
                            cv.drawRect(MARGIN.toFloat(), top, (MARGIN + 4).toFloat(), top + h, quoteBarPaint)
                        }
                        pager.gap(9f)
                    }

                    is ArticleElement.ListItem -> {
                        val ssb = spannable(element.spans)
                        val prefix = if (element.ordered) "${element.order}. " else "•  "
                        val full = SpannableStringBuilder(prefix).append(ssb)
                        val layout = StaticLayout.Builder
                            .obtain(full, 0, full.length, bodyPaint, CONTENT_W - 16)
                            .setLineSpacing(5f, 1f)
                            .build()
                        pager.drawLayout(layout, indent = 8f)
                        pager.gap(6f)
                    }

                    is ArticleElement.Divider -> {
                        pager.gap(8f)
                        pager.drawLine(dividerPaint, inset = 60f)
                        pager.gap(14f)
                    }

                    is ArticleElement.Embed -> when (val embed = element.embed) {
                        is ArticleEmbed.Image -> {
                            fetchBitmap(embed.url)?.let { bmp ->
                                pager.drawBitmap(fitBitmap(bmp, CONTENT_W))
                                embed.alt?.let { alt ->
                                    pager.drawLayout(staticLayout(alt, captionPaint))
                                }
                                pager.gap(12f)
                            }
                        }

                        is ArticleEmbed.Video -> {
                            val thumb = embed.thumbUrl?.let { fetchBitmap(it) }
                            if (thumb != null) {
                                pager.drawBitmap(fitBitmap(thumb, CONTENT_W)) { cv, top, h ->
                                    val cx = MARGIN + CONTENT_W / 2f
                                    val cy = top + h / 2f
                                    cv.drawCircle(cx, cy, 30f, videoBadgeBg)
                                    cv.drawPath(
                                        Path().apply {
                                            moveTo(cx - 9f, cy - 14f)
                                            lineTo(cx - 9f, cy + 14f)
                                            lineTo(cx + 15f, cy)
                                            close()
                                        },
                                        videoBadgeFg,
                                    )
                                }
                                pager.drawLayout(staticLayout("视频：${embed.url}", linkPaint))
                                pager.gap(12f)
                            }
                        }

                        is ArticleEmbed.Tweet -> {
                            val cardText = "[内嵌推文 https://x.com/i/status/${embed.tweetId}]"
                            val layout = staticLayout(cardText, quotePaint, CONTENT_W - 24)
                            pager.drawLayout(layout, indent = 12f, indentRight = 12f, padV = 10f,
                                background = tweetCardPaint) { cv, top, h ->
                                cv.drawRect(MARGIN.toFloat(), top, (MARGIN + 4).toFloat(), top + h, quoteBarPaint)
                            }
                            pager.gap(14f)
                        }

                        is ArticleEmbed.Code -> {
                            val layout = staticLayout(embed.content, codePaint, CONTENT_W - 24)
                            pager.drawLayout(
                                layout, indent = 12f, indentRight = 12f, padV = 10f,
                                background = codeBgPaint, roundCorner = 6f,
                            )
                            pager.gap(14f)
                        }
                    }
                }
            }

            pager.finish()
            doc.writeTo(out)
            out.flush()
        } finally {
            doc.close()
        }
    }

    // ---------- 分页器：唯一 y 状态 ----------

    private inner class Pager(private val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        var canvas: Canvas = startPage()
            private set
        private var y = MARGIN.toFloat()

        private fun startPage(): Canvas {
            val info = PdfDocument.PageInfo.Builder(A4_W, A4_H, ++pageNo).create()
            page = doc.startPage(info)
            y = MARGIN.toFloat()
            return page!!.canvas
        }

        private val pageBottom: Float get() = (A4_H - MARGIN - FOOTER_H).toFloat()

        private fun ensureSpace(needed: Float) {
            if (page == null || y + needed > pageBottom) {
                finishPage()
                canvas = startPage()
            }
        }

        /** 纯留白（不触发分页检查的小间距用 [gap]） */
        fun gap(amount: Float) {
            y += amount
        }

        fun drawLine(paint: Paint, inset: Float = 0f) {
            ensureSpace(paint.strokeWidth + 2f)
            canvas.drawLine(
                (MARGIN + inset).toFloat(), y, (A4_W - MARGIN - inset).toFloat(), y, paint,
            )
            y += paint.strokeWidth + 2f
        }

        /**
         * 画一个文本块。空间不足自动换页。
         * [decoration] 在背景绘制之后、文字绘制之前调用（画布原始坐标系）。
         */
        fun drawLayout(
            layout: StaticLayout,
            indent: Float = 0f,
            indentRight: Float = 0f,
            padV: Float = 0f,
            background: Paint? = null,
            roundCorner: Float = -1f,
            decoration: (Canvas, Float, Float) -> Unit = { _, _, _ -> },
        ) {
            ensureSpace(layout.height + padV * 2 + 2f)
            val top = y + padV
            val w = CONTENT_W - indent - indentRight
            if (background != null) {
                if (roundCorner >= 0f) {
                    canvas.drawRoundRect(
                        (MARGIN + indent - 6).toFloat(), y,
                        (MARGIN + indent + w + 6).toFloat(), y + layout.height + padV * 2,
                        roundCorner, roundCorner, background,
                    )
                } else {
                    canvas.drawRoundRect(
                        (MARGIN + indent - 6).toFloat(), y,
                        (MARGIN + indent + w + 6).toFloat(), y + layout.height + padV * 2,
                        8f, 8f, background,
                    )
                }
            }
            decoration(canvas, top, layout.height.toFloat())
            canvas.save()
            canvas.translate(MARGIN + indent, top)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + padV * 2
        }

        /** 画图片（按给定宽度），[overlay] 可叠加角标；自动换页。 */
        fun drawBitmap(bmp: Bitmap, overlay: (Canvas, Float, Float) -> Unit = { _, _, _ -> }) {
            ensureSpace(bmp.height + 6f)
            canvas.drawBitmap(bmp, MARGIN.toFloat(), y, null)
            overlay(canvas, y, bmp.height.toFloat())
            y += bmp.height
        }

        fun finishPage() {
            val p = page ?: return
            footerPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("$pageNo", A4_W / 2f, (A4_H - MARGIN + 14).toFloat(), footerPaint)
            doc.finishPage(p)
            page = null
        }

        fun finish() {
            finishPage()
        }
    }

    // ---------- 排版辅助 ----------

    private fun staticLayout(text: String, paint: TextPaint, width: Int = CONTENT_W): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1f)
            .build()

    private fun spannable(spans: List<com.wenix.xmediasaver.resolver.Span>): CharSequence {
        val ssb = SpannableStringBuilder()
        for (s in spans) {
            val start = ssb.length
            ssb.append(s.text)
            val end = ssb.length
            if (s.bold) ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
            if (s.italic) ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, 0)
            if (s.strikethrough) ssb.setSpan(StrikethroughSpan(), start, end, 0)
            if (s.link != null) ssb.setSpan(URLSpan(s.link), start, end, 0)
        }
        return ssb
    }

    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TweetResolver.BROWSER_UA)
            .header("Referer", "https://x.com/")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun fitBitmap(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val ratio = maxWidth.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * ratio).toInt(), true)
    }

    companion object {
        // A4 @72dpi
        private const val A4_W = 595
        private const val A4_H = 842
        private const val MARGIN = 46
        private const val CONTENT_W = A4_W - 2 * MARGIN
        private const val FOOTER_H = 30

        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val h1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 20, 28)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 20, 28)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 34, 44)
            textSize = 12f
        }
        private val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 86, 98)
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 118, 132)
            textSize = 10f
        }
        private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(130, 138, 152)
            textSize = 9f
        }
        private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 42, 52)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
        }
        private val linkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(29, 100, 220)
            textSize = 9f
            isUnderlineText = true
        }
        private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 155, 165)
            textSize = 9f
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 228, 234)
            strokeWidth = 1.2f
        }
        private val quoteBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 130, 220)
            style = Paint.Style.FILL
        }
        private val tweetCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(244, 246, 250)
            style = Paint.Style.FILL
        }
        private val codeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(240, 242, 246)
            style = Paint.Style.FILL
        }
        private val videoBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 0, 0, 0)
        }
        private val videoBadgeFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
    }
}
