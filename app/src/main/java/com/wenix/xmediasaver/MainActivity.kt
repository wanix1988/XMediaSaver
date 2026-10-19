package com.wenix.xmediasaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenix.xmediasaver.history.HistoryStore
import com.wenix.xmediasaver.resolver.LinkExtractor
import com.wenix.xmediasaver.resolver.MediaKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(viewModel) { handleShareIntent(intent) }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val text = intent?.let(::sharedTextOf) ?: return
        viewModel.submit(text)
    }
}

private fun sharedTextOf(intent: Intent): String? =
    if (intent.action == Intent.ACTION_SEND &&
        intent.type == "text/plain" &&
        !intent.hasExtra(Intent.EXTRA_STREAM)
    ) {
        @Suppress("DEPRECATION")
        intent.getStringExtra(Intent.EXTRA_TEXT)
    } else null

// ---------------------------------------------------------------- UI

private val BgColor = Color(0xFF0D0F13)
private val CardColor = Color(0xFF15171C)
private val AccentColor = Color(0xFF4F9CF9)
private val SubTextColor = Color(0xFF8B93A1)
private val BodyTextColor = Color(0xFFB8BFCC)
private val OkColor = Color(0xFF7BD88F)
private val WarnColor = Color(0xFFF0A44B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(viewModel: MainViewModel, onRefreshIntent: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.historyEntries.collectAsStateWithLifecycle()

    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = AccentColor,
            surface = CardColor,
            background = BgColor,
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("X 媒体下载", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CardColor,
                        titleContentColor = Color.White,
                    ),
                    actions = {
                        IconButton(onClick = onRefreshIntent) {
                            Icon(Icons.Filled.Refresh, "重新读取分享", tint = SubTextColor)
                        }
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回首页", tint = SubTextColor)
                        }
                    },
                )
            },
            containerColor = BgColor,
        ) { padding ->
            val contentModifier = Modifier.padding(padding)
            when (val s = state) {
                is MainViewModel.UiState.Idle -> HomeScreen(
                    viewModel = viewModel,
                    history = history,
                    modifier = contentModifier,
                )

                is MainViewModel.UiState.Resolving -> CenterInfo(
                    title = "正在解析链接…",
                    detail = s.url,
                    showSpinner = true,
                    modifier = contentModifier,
                )

                is MainViewModel.UiState.Downloading -> DownloadingScreen(s, contentModifier)
                is MainViewModel.UiState.RenderingPdf -> CenterInfo(
                    title = "正在生成 PDF…",
                    detail = s.article.title,
                    showSpinner = true,
                    modifier = contentModifier,
                )

                is MainViewModel.UiState.Done -> DoneScreen(s, viewModel, contentModifier)
                is MainViewModel.UiState.ArticleDone -> ArticleDoneScreen(s, viewModel, contentModifier)
                is MainViewModel.UiState.Failed -> FailedScreen(s, viewModel, contentModifier)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    history: List<HistoryStore.Entry>,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val clipboardHasLink = clipboard.getText()?.text?.let(LinkExtractor::extractUrl) != null
    val context = androidx.compose.ui.platform.LocalContext.current

    fun openEntry(entry: HistoryStore.Entry) {
        val uri = entry.uris.firstOrNull() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(uri)
                entry.mime?.let { setDataAndType(android.net.Uri.parse(uri), it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "打开文件"))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("粘贴 X / Twitter 分享链接", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "视频/图片存入相册，X 文章自动排版保存为 PDF",
            fontSize = 13.sp, color = SubTextColor,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://x.com/…/status/… 或 t.co 短链", color = Color(0xFF5A6172)) },
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.submit(input) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = input.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("解析并下载", fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = { clipboard.getText()?.text?.let { input = it } },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("粘贴")
            }
        }

        if (clipboardHasLink) {
            Spacer(Modifier.height(10.dp))
            Text("剪贴板检测到 X 链接，点「粘贴」即可", fontSize = 12.sp, color = AccentColor)
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("最近下载", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history, key = { "${it.timeMs}-${it.tweetId}" }) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable(
                            enabled = entry.uris.isNotEmpty(),
                        ) { openEntry(entry) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "@${entry.authorHandle}",
                                    fontSize = 14.sp, color = AccentColor, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(entry.timeMs)),
                                    fontSize = 12.sp, color = SubTextColor,
                                )
                                Spacer(Modifier.weight(1f))
                                Text("${entry.fileCount} 个文件", fontSize = 12.sp, color = SubTextColor)
                            }
                            if (entry.text.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    entry.text,
                                    fontSize = 13.sp, color = BodyTextColor,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DownloadingScreen(s: MainViewModel.UiState.Downloading, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = AccentColor, strokeWidth = 4.dp)
        Spacer(Modifier.height(24.dp))
        Text("正在下载 @${s.tweet.authorHandle} 的 ${s.tweet.media.size} 个媒体", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text("第 ${s.progress.finishedFiles + 1}/${s.progress.totalFiles} 个文件", fontSize = 13.sp, color = SubTextColor)
        Spacer(Modifier.height(20.dp))
        val percent = s.progress.let { p ->
            if (p.bytesTotal != null && p.bytesTotal > 0) p.bytesDone.toFloat() / p.bytesTotal else null
        }
        LinearProgressIndicator(
            progress = { percent ?: 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = AccentColor,
            trackColor = Color(0xFF1E222A),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            formatBytes(s.progress.bytesDone) + (s.progress.bytesTotal?.let { " / ${formatBytes(it)}" } ?: ""),
            fontSize = 12.sp, color = SubTextColor,
        )
        if (s.tweet.text.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                s.tweet.text,
                fontSize = 13.sp, color = BodyTextColor,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DoneScreen(s: MainViewModel.UiState.Done, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(OkColor.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = OkColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("已保存 ${s.files.size} 个文件", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "@${s.tweet.authorHandle} · 已存入系统相册（Movies/XMediaSaver）",
                    fontSize = 13.sp, color = SubTextColor,
                )
            }
        }

        if (s.tweet.text.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                s.tweet.text,
                fontSize = 14.sp, color = BodyTextColor,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(20.dp))
        s.files.forEach { file ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(vertical = 5.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (file.kind == MediaKind.PHOTO) "🖼 图片" else "🎬 视频", fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatBytes(file.sizeBytes), fontSize = 12.sp, color = SubTextColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        file.displayName,
                        fontSize = 12.sp, color = SubTextColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (file.alreadyExisted) {
                        Spacer(Modifier.height(4.dp))
                        Text("之前已下载过，本次跳过", fontSize = 11.sp, color = WarnColor)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { viewModel.reset() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("继续下载其他链接", fontSize = 16.sp)
        }
    }
}

@Composable
private fun ArticleDoneScreen(s: MainViewModel.UiState.ArticleDone, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(OkColor.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("📄", fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("文章已保存为 PDF", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Documents/XMediaSaver · ${formatBytes(s.file.sizeBytes)}",
                    fontSize = 13.sp, color = SubTextColor,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            s.article.title,
            fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "@${s.article.authorHandle}" + (s.article.createdAt?.take(10)?.let { " · $it" } ?: ""),
            fontSize = 13.sp, color = AccentColor,
        )
        if (s.article.previewText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                s.article.previewText,
                fontSize = 13.sp, color = BodyTextColor,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }

        if (s.file.alreadyExisted) {
            Spacer(Modifier.height(12.dp))
            Text("之前已保存过，本次跳过", fontSize = 12.sp, color = WarnColor)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { viewModel.reset() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("继续下载其他链接", fontSize = 16.sp)
        }
    }
}

@Composable
private fun FailedScreen(s: MainViewModel.UiState.Failed, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⚠️", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        Text("下载失败", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(s.message, fontSize = 14.sp, color = SubTextColor, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { viewModel.retry() },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("重试") }
            Button(
                onClick = { viewModel.reset() },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("返回") }
        }
    }
}

@Composable
private fun CenterInfo(title: String, detail: String?, showSpinner: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(color = AccentColor, strokeWidth = 4.dp)
            Spacer(Modifier.height(24.dp))
        }
        Text(title, color = Color.White, fontSize = 16.sp)
        detail?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 12.sp, color = SubTextColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
