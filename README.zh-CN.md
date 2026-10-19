# XMediaSaver

**[English](README.md)** | 简体中文

X / Twitter 媒体下载器 —— 贴入分享链接，视频/图片存入相册，长文章自动排版保存为 PDF。

免登录、无广告、无第三方 SDK，仅申请网络权限。

## 功能

- **推文媒体下载**：视频（含 GIF）、多图，直接写入系统相册
  - 视频 → `Movies/XMediaSaver/`
  - 图片 → `Pictures/XMediaSaver/`
- **推文 txt 存档**：每条推文（含纯文本推文）导出 txt，记录作者、发布时间、
  推文链接、媒体清单与正文，存入 `Documents/XMediaSaver/`
- **X 文章（Articles）转 PDF**：解析 Draft.js 结构，保留标题层级、粗斜体、链接、
  引用块、有序/无序列表、代码块、文内图片与内嵌推文，A4 自动分页，存入
  `Documents/XMediaSaver/`
- **文件名以内容标题开头**：`{标题}_{作者}_{推文ID}_{序号}.{扩展名}`，标题取推文
  首行/文章标题（去 URL 与非法字符，限 50 字符）
- **下载历史**：最近 50 条本地记录，点击直接打开对应文件
- **分享直达**：从 X App 分享链接到本应用直接开始下载
- 同名文件自动去重，重复下载直接复用本地文件

## 技术方案

### 免登录解析（三源容错）

不调用官方 API、不模拟登录，依次尝试三个匿名可访问的数据源：

1. **fxtwitter v2** — `https://api.fxtwitter.com/2/status/{id}`
   （文章结构 `article.content.blocks` 由此返回）
2. **fxtwitter v1** — `https://api.fxtwitter.com/_/status/{id}`
3. **官方 syndication** — `https://cdn.syndication.twimg.com/tweet-result?id={id}&token=...`
   （token 校验宽松，任意 32 位 hex 可用）

视频直链来自 `video.twimg.com`、图片来自 `pbs.twimg.com`，均匿名可下载，
请求时带浏览器 UA 与 `Referer: https://x.com/`。

### 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin |
| 包名 | `com.wenix.xmediasaver`（个人工程统一以 `com.wenix` 为前缀） |
| UI | Jetpack Compose + Material 3（单 Activity） |
| 网络 | OkHttp 4 |
| 存储 | MediaStore（API 29+ 免权限）；API 26-28 兜底 app 私有目录 + MediaScanner |
| PDF | 原生 `PdfDocument` + `StaticLayout` 自动分页，无第三方渲染库 |
| 构建 | AGP 8.13.2 / Kotlin 2.0.21 / Gradle 8.13，JDK 17+ |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26（Android 8.0+） |

### 模块结构

```
app/src/main/java/com/wenix/xmediasaver/
├── MainActivity.kt          # Compose UI + 历史列表 + 文件打开
├── MainViewModel.kt         # UiState 状态机：解析 → 下载/渲染 → 完成
├── resolver/
│   ├── TweetResolver.kt     # 三源容错解析器（推文 + 文章）
│   ├── ArticleParser.kt     # Draft.js blocks → ArticleElement 流
│   ├── LinkExtractor.kt     # 分享文本中提取 URL / 推文 ID
│   └── Models.kt            # TweetData / ArticleData / ArticleEmbed 等模型
├── download/
│   ├── MediaDownloader.kt   # MediaStore 落盘 + 进度回调 + 去重 + txt 存档
│   └── ArticlePdfRenderer.kt# 文章 → PDF 排版渲染（A4 分页引擎）
└── history/
    └── HistoryStore.kt      # 下载历史 JSON 持久化（最多 50 条）
```

## 构建

```bash
# 环境要求：JDK 17+，ANDROID_HOME 指向 Android SDK
export ANDROID_HOME=$HOME/Library/Android/sdk

# Debug 版
./gradlew assembleDebug

# Release 版（需先配置签名密钥，见下节）
./gradlew assembleRelease
```

产物路径：`app/build/outputs/apk/{debug,release}/`

## Release 签名

密钥与密码**均不入库**（已加入 `.gitignore`）。构建脚本中无明文密码，签名信息
从两个来源读取（优先级从高到低）：

1. 本机 `keystore.properties`（已 gitignore，格式见下）
2. 环境变量 `XMS_STORE_FILE` / `XMS_STORE_PASSWORD` / `XMS_KEY_ALIAS` /
   `XMS_KEY_PASSWORD`

`keystore.properties` 格式：

```properties
storeFile=keystore/xmediasaver.jks
storePassword=<密码>
keyAlias=xmediasaver
keyPassword=<密码>
```

两者皆缺时 release 产物不签名（构建会输出告警），可另行手动签名。
密钥本机位于 `keystore/xmediasaver.jks`，需自行备份。

在新机器恢复构建时重新生成密钥：

```bash
keytool -genkeypair -v -keystore keystore/xmediasaver.jks \
  -alias xmediasaver -keyalg RSA -keysize 2048 -validity 9125
```

注意：更换密钥后无法覆盖安装旧版本，需先卸载。

## 安装到手机

```bash
adb install -r XMediaSaver-v1.3.0-release.apk
```

## 版本历史

| 版本 | 说明 |
|---|---|
| 1.3.0 | 纯文本推文支持；每条推文导出 txt 存档；修复早期短推文 ID 被拒问题 |
| 1.2.0 | 文件名以推文/文章标题开头 |
| 1.1.0 | R8 压缩 release；历史点击打开文件；标题栏遮挡修复；文章 PDF 排版重写 |
| 1.0.0 | 首版：推文媒体下载 + 文章 PDF + 下载历史 |
