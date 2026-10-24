# XMediaSaver

English | **[简体中文](README.zh-CN.md)**

An X / Twitter media saver for Android — paste a share link, and videos/photos land in your gallery while long-form articles are typeset into a PDF.

No login, no ads, no third-party SDKs. Requests only the INTERNET permission.

## Features

- **Tweet media downloads** — videos (incl. GIFs) and multi-image posts, written
  straight into the system gallery
  - Videos → `Movies/XMediaSaver/`
  - Photos → `Pictures/XMediaSaver/`
- **Tweet txt archive** — every tweet (including text-only ones) is exported to a
  txt file recording the author, posting time, tweet URL, media manifest, and full
  text, saved to `Documents/XMediaSaver/`
- **X Articles → PDF** — parses the Draft.js structure and preserves heading
  levels, bold/italic, links, block quotes, ordered/unordered lists, code blocks,
  inline images, and embedded tweets, with automatic A4 pagination into
  `Documents/XMediaSaver/`
- **Content-titled filenames** — `{title}_{author}_{tweetId}_{n}.{ext}`; the title
  comes from the tweet's first line or the article title (URLs and illegal
  characters stripped, capped at 50 chars)
- **Download history** — last 50 entries kept locally; tap any entry to open the
  saved file
- **Share-to-download** — share a link from the X app straight into XMediaSaver
- Duplicate detection — re-downloading an existing file reuses the local copy

## How It Works

### Login-free resolution (three-source fallback)

No official API calls and no login simulation. The resolver tries three
anonymously accessible data sources in order:

1. **fxtwitter v2** — `https://api.fxtwitter.com/2/status/{id}`
   (article structure `article.content.blocks` comes from here)
2. **fxtwitter v1** — `https://api.fxtwitter.com/_/status/{id}`
3. **Official syndication** —
   `https://cdn.syndication.twimg.com/tweet-result?id={id}&token=...`
   (token validation is lax — any 32-hex string works)

Video direct links come from `video.twimg.com`, photos from `pbs.twimg.com`;
both download anonymously with a browser UA and `Referer: https://x.com/`.

### Tech stack

| Item | Value |
|---|---|
| Language | Kotlin |
| Package | `com.wenix.xmediasaver` (personal projects use the `com.wenix` prefix) |
| UI | Jetpack Compose + Material 3 (single Activity) |
| Networking | OkHttp 4 |
| Storage | MediaStore (API 29+, permission-free); API 26-28 falls back to app-private dirs + MediaScanner |
| PDF | Native `PdfDocument` + `StaticLayout` pagination — no third-party renderers |
| Build | AGP 8.13.2 / Kotlin 2.0.21 / Gradle 8.13, JDK 17+ |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26 (Android 8.0+) |

### Module layout

```
app/src/main/java/com/wenix/xmediasaver/
├── MainActivity.kt          # Compose UI + history list + file opening
├── MainViewModel.kt         # UiState machine: resolve → download/render → done
├── resolver/
│   ├── TweetResolver.kt     # Three-source fallback resolver (tweets + articles)
│   ├── ArticleParser.kt     # Draft.js blocks → ArticleElement stream
│   ├── LinkExtractor.kt     # URL / tweet ID extraction from shared text
│   └── Models.kt            # TweetData / ArticleData / ArticleEmbed models
├── download/
│   ├── MediaDownloader.kt   # MediaStore persistence + progress + dedup + txt archive
│   └── ArticlePdfRenderer.kt# Article → PDF typesetting engine (A4 pagination)
└── history/
    └── HistoryStore.kt      # Download history as JSON (up to 50 entries)
```

## Building

```bash
# Requirements: JDK 17+, ANDROID_HOME pointing at an Android SDK
export ANDROID_HOME=$HOME/Library/Android/sdk

# Debug build
./gradlew assembleDebug

# Release build (signing key must be configured first — see below)
./gradlew assembleRelease
```

Output path: `app/build/outputs/apk/{debug,release}/`

## Release signing

Keystore and passwords are **never committed** (covered by `.gitignore`), and the
build script contains no plaintext secrets. Signing info is read from two sources
(highest priority first):

1. A local `keystore.properties` file (gitignored; format below)
2. Environment variables `XMS_STORE_FILE` / `XMS_STORE_PASSWORD` /
   `XMS_KEY_ALIAS` / `XMS_KEY_PASSWORD`

`keystore.properties` format:

```properties
storeFile=keystore/xmediasaver.jks
storePassword=<password>
keyAlias=xmediasaver
keyPassword=<password>
```

If neither source is present, the release artifact is left unsigned (the build
prints a warning); sign it manually afterwards. The keystore lives at
`keystore/xmediasaver.jks` on the dev machine — back it up yourself.

Regenerate a key on a new machine:

```bash
keytool -genkeypair -v -keystore keystore/xmediasaver.jks \
  -alias xmediasaver -keyalg RSA -keysize 2048 -validity 9125
```

Note: a different key cannot upgrade-install over an existing installation —
uninstall first.

## GitHub Releases

A push to `main` publishes a release when that `versionName` does not already
have one. The workflow builds a signed APK and attaches it as
`XMediaSaver-<version>-release.apk` on tag `v<version>`.

Before the first run, add these Actions secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of `keystore/xmediasaver.jks` |
| `XMS_STORE_PASSWORD` | Keystore password |
| `XMS_KEY_ALIAS` | Key alias |
| `XMS_KEY_PASSWORD` | Key password |

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\xmediasaver.jks"))
```

Bump `versionCode` and `versionName`, then push to `main`, to publish the next
release. If a run failed before the secrets existed, add them and re-run that
workflow from the Actions tab.

## Installing on a phone

```bash
adb install -r XMediaSaver-v1.3.0-release.apk
```

## Version history

| Version | Notes |
|---|---|
| 1.3.0 | Text-only tweet support; txt archive for every tweet; fix for short legacy tweet IDs |
| 1.2.0 | Filenames now start with the tweet/article title |
| 1.1.0 | R8-shrunk release; open files from history; top-bar overlap fix; article PDF layout rewrite |
| 1.0.0 | Initial release: tweet media download + article PDF + download history |
