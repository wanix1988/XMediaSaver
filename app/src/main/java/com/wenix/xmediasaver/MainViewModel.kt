package com.wenix.xmediasaver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenix.xmediasaver.download.MediaDownloader
import com.wenix.xmediasaver.history.HistoryStore
import com.wenix.xmediasaver.resolver.ArticleData
import com.wenix.xmediasaver.resolver.LinkExtractor
import com.wenix.xmediasaver.resolver.TweetData
import com.wenix.xmediasaver.resolver.TweetResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val resolver = TweetResolver(client)
    private val downloader = MediaDownloader(app, client)
    private val history = HistoryStore(app)

    sealed interface UiState {
        data object Idle : UiState
        data class Resolving(val url: String) : UiState
        data class Downloading(val tweet: TweetData, val progress: MediaDownloader.Progress) : UiState
        data class RenderingPdf(val article: ArticleData) : UiState
        data class Done(val tweet: TweetData, val files: List<MediaDownloader.SavedFile>) : UiState
        data class ArticleDone(val article: ArticleData, val file: MediaDownloader.SavedFile) : UiState
        data class Failed(val message: String, val input: String?) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _historyEntries = MutableStateFlow(history.entries())
    val historyEntries: StateFlow<List<HistoryStore.Entry>> = _historyEntries.asStateFlow()

    /** 提交一段分享文本或链接，自动解析并下载全部媒体 */
    fun submit(text: String) {
        if (text.isBlank()) return
        val url = LinkExtractor.extractUrl(text)
        if (url == null) {
            _state.value = UiState.Failed("没有识别到 X / Twitter 链接，请检查输入内容", text)
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Resolving(url)
            try {
                val realUrl = resolver.expandShortLink(url)
                val statusId = LinkExtractor.statusIdFrom(realUrl)
                    ?: LinkExtractor.statusIdFrom(url)
                    ?: error("链接里没有推文 ID（可能不是推文分享链接）")
                when (val resolved = resolver.resolveAny(statusId)) {
                is TweetResolver.Result.Article -> {
                    val article = resolved.data
                    _state.value = UiState.RenderingPdf(article)
                    val file = downloader.saveArticlePdf(article)
                    history.add(
                        System.currentTimeMillis(),
                        article.authorHandle,
                        article.title,
                        article.id,
                        1,
                        uris = listOf(file.uriString),
                        mime = file.mimeType,
                    )
                    _historyEntries.value = history.entries()
                    _state.value = UiState.ArticleDone(article, file)
                }

                is TweetResolver.Result.Tweet -> {
                    val tweet = resolved.data
                    _state.value = UiState.Downloading(
                        tweet,
                        MediaDownloader.Progress(0, tweet.media.size, 0, null),
                    )
                    val files = downloader.downloadAll(tweet) { p ->
                        _state.value = UiState.Downloading(tweet, p)
                    }
                    history.add(
                        System.currentTimeMillis(),
                        tweet.authorHandle,
                        tweet.text,
                        tweet.id,
                        files.size,
                        uris = files.map { it.uriString },
                        mime = files.firstOrNull()?.mimeType,
                    )
                    _historyEntries.value = history.entries()
                    _state.value = UiState.Done(tweet, files)
                }
                }
            } catch (e: CancellationException) {
                _state.value = UiState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Failed(e.message ?: "未知错误", text)
            }
        }
    }

    fun retry() {
        val last = (_state.value as? UiState.Failed)?.input ?: return
        submit(last)
    }

    fun reset() {
        _state.value = UiState.Idle
    }
}
