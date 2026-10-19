package com.wenix.xmediasaver.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wenix.xmediasaver.resolver.ArticleData
import com.wenix.xmediasaver.resolver.MediaItem
import com.wenix.xmediasaver.resolver.MediaKind
import com.wenix.xmediasaver.resolver.TweetData
import com.wenix.xmediasaver.resolver.TweetResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 媒体下载器：视频存到 Movies/XMediaSaver，图片存到 Pictures/XMediaSaver。
 * API 29+ 走 MediaStore（免存储权限、相册直接可见）；
 * API 26-28 兜底写应用外部私有目录 + MediaScanner 收录。
 */
class MediaDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {

    data class SavedFile(
        val displayName: String,
        val uriString: String,
        val mimeType: String,
        val kind: MediaKind,
        val sizeBytes: Long,
        val alreadyExisted: Boolean,
    )

    data class Progress(
        val finishedFiles: Int,
        val totalFiles: Int,
        val bytesDone: Long,
        val bytesTotal: Long?, // null 表示总大小未知
    )

    suspend fun downloadAll(
        tweet: TweetData,
        onProgress: (Progress) -> Unit,
    ): List<SavedFile> = withContext(Dispatchers.IO) {
        val saved = mutableListOf<SavedFile>()
        val lengths = HashMap<Int, Long>()
        val total = tweet.media.size
        fun totalOrNull(): Long? = if (lengths.size == total) lengths.values.sum() else null

        tweet.media.forEachIndexed { index, item ->
            val base = saved.sumOf { it.sizeBytes }
            val file = downloadItem(tweet, index, item) { copied, length ->
                length?.let { lengths[index] = it }
                onProgress(Progress(saved.size, total, base + copied, totalOrNull()))
            }
            saved += file
            onProgress(Progress(saved.size, total, base + file.sizeBytes, totalOrNull()))
        }
        saved.toList()
    }

    private suspend fun downloadItem(
        tweet: TweetData,
        index: Int,
        item: MediaItem,
        onCopy: (bytesCopied: Long, contentLength: Long?) -> Unit,
    ): SavedFile {
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", TweetResolver.BROWSER_UA)
            .header("Referer", "https://x.com/")
            .build()
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("下载失败：空响应")
            val mime = normalizeMime(response.header("Content-Type"), item.url)
            val ext = extensionFor(mime, item.url)
            val displayName = buildDisplayName(tweet, index, ext)
            val isVideo = item.kind != MediaKind.PHOTO

            // 同名文件已存在：跳过重复下载，直接复用
            findExisting(item, displayName, mime)?.let { existing ->
                onCopy(existing.sizeBytes, existing.sizeBytes)
                return existing
            }

            val length = body.contentLength().takeIf { it > 0 }

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) RELATIVE_VIDEO_PATH else RELATIVE_IMAGE_PATH,
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values)
                    ?: error("无法创建媒体文件（存储空间不足？）")
                try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        copyWithProgress(body, out, length, onCopy)
                    } ?: error("无法打开输出流")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                SavedFile(displayName, uri.toString(), mime, item.kind, querySize(uri), false)
            } else {
                // API 26-28：应用外部私有目录 + MediaScanner 收录
                val dirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val parent = File(context.getExternalFilesDir(dirType), "XMediaSaver")
                parent.mkdirs()
                val target = File(parent, displayName)
                target.outputStream().use { out -> copyWithProgress(body, out, length, onCopy) }
                MediaScannerConnection.scanFile(
                    context, arrayOf(target.absolutePath), arrayOf(mime), null,
                )
                SavedFile(
                    displayName, Uri.fromFile(target).toString(), mime, item.kind, target.length(), false,
                )
            }
        } finally {
            response.close()
        }
    }

    private suspend fun copyWithProgress(
        body: ResponseBody,
        out: OutputStream,
        length: Long?,
        onCopy: (Long, Long?) -> Unit,
    ) {
        val input = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            onCopy(copied, length)
        }
        out.flush()
    }

    private fun findExisting(
        item: MediaItem,
        displayName: String,
        mime: String,
    ): SavedFile? {
        val isVideo = item.kind != MediaKind.PHOTO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    SavedFile(displayName, uri.toString(), mime, item.kind, querySize(uri), true)
                } else null
            }
        } else {
            val dirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val target = File(File(context.getExternalFilesDir(dirType), "XMediaSaver"), displayName)
            if (target.exists()) {
                SavedFile(displayName, Uri.fromFile(target).toString(), mime, item.kind, target.length(), true)
            } else null
        }
    }

    private fun querySize(uri: Uri): Long =
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            ?: 0L

    private fun buildDisplayName(tweet: TweetData, index: Int, ext: String): String {
        val handle = tweet.authorHandle.filter { it.isLetterOrDigit() || it == '_' }.take(24).ifEmpty { "user" }
        val title = titleFromText(tweet.text)
        return "${title}_${handle}_${tweet.id}_${index + 1}.$ext"
    }

    private fun normalizeMime(contentType: String?, url: String): String {
        val raw = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (!raw.isNullOrBlank() && raw != "application/octet-stream" && raw != "binary/octet-stream") {
            return raw
        }
        return guessMimeFromUrl(url) ?: if (url.substringBefore('?').endsWith(".mp4")) "video/mp4" else "application/octet-stream"
    }

    private fun guessMimeFromUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            else -> null
        }
    }

    private fun extensionFor(mime: String, url: String): String {
        val fromMime = when (mime) {
            "video/mp4" -> "mp4"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
        if (fromMime != null) return fromMime
        val path = url.substringBefore('?')
        val dot = path.lastIndexOf('.')
        return if (dot in (path.length - 5) until path.length) path.substring(dot + 1).lowercase() else "bin"
    }

    companion object {
        private const val RELATIVE_VIDEO_PATH = "Movies/XMediaSaver"
        private const val RELATIVE_IMAGE_PATH = "Pictures/XMediaSaver"
        private const val RELATIVE_DOC_PATH = "Documents/XMediaSaver"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_TITLE_LEN = 50
    }

    /** 从推文正文提取标题：首行非空文本，去掉 URL 与 t.co 尾链 */
    private fun titleFromText(text: String): String {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "X"
        return sanitizeFileName(line).take(MAX_TITLE_LEN).ifEmpty { "X" }
    }

    /** 文件名合法化：去路径/控制字符/全角空格，折叠空白 */
    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("https?://\\S+"), "") // 链接整体去掉
            .replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]"), " ") // 控制字符
            .replace('/', ' ').replace('\\', ' ')
            .replace(':', ' ').replace('*', ' ').replace('?', ' ')
            .replace('"', ' ').replace('<', ' ').replace('>', ' ')
            .replace('|', ' ').replace('　', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ') // 防止结尾点号导致 Windows 式扩展名混淆
        return cleaned
    }

    // ---------------- 文章 PDF ----------------

    /** 文章保存为 PDF：Documents/XMediaSaver/ 下，返回文件信息 */
    suspend fun saveArticlePdf(article: ArticleData): SavedFile = withContext(Dispatchers.IO) {
        val displayName = buildPdfName(article)

        findExistingPdf(displayName)?.let { existing ->
            return@withContext existing.copy(kind = MediaKind.PHOTO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DOC_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: error("无法创建 PDF 文件")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    ArticlePdfRenderer(client).render(article, out)
                } ?: error("无法打开输出流")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SavedFile(displayName, uri.toString(), "application/pdf", MediaKind.PHOTO, querySize(uri), false)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val parent = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "XMediaSaver")
            parent.mkdirs()
            val target = File(parent, displayName)
            target.outputStream().use { out -> ArticlePdfRenderer(client).render(article, out) }
            SavedFile(
                displayName, Uri.fromFile(target).toString(), "application/pdf", MediaKind.PHOTO,
                target.length(), false,
            )
        }
    }

    private fun buildPdfName(article: ArticleData): String {
        val handle = article.authorHandle.filter { it.isLetterOrDigit() || it == '_' }.take(24).ifEmpty { "user" }
        val title = sanitizeFileName(article.title).take(MAX_TITLE_LEN).ifEmpty { "X_Article" }
        return "${title}_${handle}_${article.id}.pdf"
    }

    private fun findExistingPdf(displayName: String): SavedFile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=?",
                arrayOf(displayName, "application/pdf"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    SavedFile(displayName, uri.toString(), "application/pdf", MediaKind.PHOTO, querySize(uri), true)
                } else null
            }
        } else {
            val parent = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "XMediaSaver")
            val target = File(parent, displayName)
            if (target.exists()) {
                SavedFile(displayName, Uri.fromFile(target).toString(), "application/pdf", MediaKind.PHOTO, target.length(), true)
            } else null
        }
    }
}