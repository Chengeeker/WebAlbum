# WebAlbum

**An Android WebDAV photo, video, and music browser/player.** Connect to your own WebDAV server to explore folders, preview photos, play videos and music, and manage remote media from your phone.

[简体中文](README.md) · [English](README.en.md) · [Latest release](https://github.com/Chengeeker/WebAlbum/releases/latest) · [LucentUI](https://github.com/Chengeeker/LucentUI)

## Overview

WebAlbum is a lightweight **WebDAV media browser and player**, not a hosted cloud storage service. You can browse and play photos, video, and music stored on your WebDAV server without syncing the entire library to your phone, and download selected files when needed.

The app uses **Material Design 3** and UI components from my [**LucentUI**](https://github.com/Chengeeker/LucentUI) project. The in-repository `lucent-ui` module contains the UI code used by WebAlbum. Some implementations also draw on [Review](https://github.com/Chengeeker/Review). See [NOTICE.md](NOTICE.md) for attribution and third-party licenses.

## Features

### WebDAV browsing and playback
- **Your own server:** Connect using a WebDAV URL, username, and password. Browse directories, save connection preferences, choose a default folder, and configure network timeouts and proxy settings.
- **Photo viewing:** Common formats including JPEG, PNG, WebP, GIF, BMP, HEIC/HEIF, and AVIF, subject to Android device support. Preview, zoom, and swipe through images.
- **Video playback:** Powered by AndroidX Media3 / ExoPlayer, with video thumbnails, play/pause, progress and seeking, landscape fullscreen, playback speed, brightness and volume gestures. Actual codec and container support depends on the device and server.
- **Motion photos:** Recognition/playback for compatible motion-photo media, depending on file format and device.
- **Progressive previews:** Show cached thumbnails first, then fade in the full-resolution image, prefetching only nearby pictures. Plain WebDAV may not provide server-generated thumbnails, so initial requests can still fetch source files.
- **HDR media:** Detect Ultra HDR images with gain maps on Android 14+, and HLG/PQ video via actual color metadata. HDR badges and a more suitable video output path are used on compatible devices; results depend on the display, platform, and decoder.
- **Original-resolution viewing:** A centered translucent capsule above the gallery filmstrip can cache or download the full source file with progress. Zooming past roughly 1.6× triggers region-based decoding of visible source pixels instead of stretching a small preview. The capsule follows the existing liquid-glass and haptics settings. Ultra HDR gainmap images conservatively retain the color-correct preview rather than using SDR region tiles.
- **On-demand image caching:** Load thumbnails and previews without downloading the full remote library.

### Music playback
- **WebDAV audio:** Recognizes MP3, FLAC, M4A, AAC, OGG, OPUS, WAV, and other common extensions. Codec support depends on the device and server; folder tracks form a playback queue.
- **GreenMusic-inspired player:** Mini-player, album cover, lyrics, queue, gradients, and gestures adapted from the author's [GreenMusic](https://github.com/Chengeeker/GreenMusic) project.
- **Interruptible transitions:** Tap the mini-player while fullscreen is still collapsing to reverse the shared cover/title animation from its current position. Nonessential fullscreen controls fade out rapidly.
- **Lyrics and tags:** Sidecar LRC files plus some MP3 ID3 USLT/TXXX and FLAC Vorbis Comment lyrics. Untimed lyrics display as text. M4A-specific embedded lyrics are not fully supported. Known embedded artwork and artist names can appear before playback.
- **Background playback:** Media3 MediaSessionService with system media notifications and controls; Android 13+ requests notification permission. Device restrictions may affect behavior.
- **Neighbor preloading:** Optionally caches about 768 KiB from the previous and next tracks to reduce WebDAV latency. Includes repeat, shuffle, speed, and lyric preferences.

### Gallery and file operations
- **Folders and grid layout:** Browse nested directories and see photos/videos in a unified media grid. Choose 1–5 media columns, with a separate folder-grid preference. Optional filename cards in 1–3 columns can trim common Screenshot, IMG, VID, and WeChat-export prefixes.
- **Quick navigation:** Pull to refresh and double-tap the top-bar title to scroll to the top.
- **Sorting:** Sort by capture time, modified time, or filename, in ascending or descending order. Most recently modified files appear first by default.
- **Range selection:** Long-press and drag to select multiple items; drag back toward the start to shrink the selection. Download, inspect properties, or delete selected files.
- **Local downloads:** Choose a destination through the Android system file picker or configure separate batch download folders for images, videos, and music.

> **About capture-time sorting:** WebDAV may not expose capture timestamps. WebAlbum currently prefers EXIF metadata available from cached images, falling back to the remote modified time when no capture timestamp is available (including for many videos). It does not guarantee a true chronological capture order for every file.

### Design, haptics, and privacy
- **LucentUI + Material 3:** Theme colors, font and layout settings, and optional liquid-glass visuals.
- **Interaction feedback:** Page transition effects, touch and range-selection haptics, with settings to disable animations or haptic feedback.
- **App protection:** Optional PIN/biometric locking and screenshot protection.
- **Settings backup:** Export/restore non-sensitive preferences. WebDAV credentials are not included in the settings export.

## What's new in 1.5.3 (since v1.5.0)

### Added
- **Separate image, video, and music download folders.** Select or clear each destination in Settings → Storage; batch downloads automatically use the matching media category.
- **Compatible migration of existing settings.** The previous shared image/video folder is retained for those categories, while music has its own destination. Access permissions remain valid when other categories still reference the same folder.

### Fixed
- **Off-center lyrics.** The active line is positioned using the actual line heights, bilingual text, and viewport size instead of a fixed offset that pushed it toward the bottom.
- **Abrupt lyric switching.** Line-size updates no longer cancel the in-progress scroll animation; neighboring lines scroll smoothly even with the optional stagger effect disabled.
- **Lag on first full-player expansion.** Reduces first-open initialization and repeated GPU effects during the capsule transition, while retaining its shared artwork/title animation.

### Improved
- **Lyric following.** Normal adjacent lines animate smoothly; large seeks jump directly to the destination. Global reduced-motion preferences are respected.
- **Storage Access Framework permissions.** Replacing one media folder does not revoke an authorization that other categories still need.

## What's new in 1.5.0

- **New app icon:** a unified watercolor brand mark for launcher aliases, About, and in-app icon preview. Displayed without cropping or distortion.
- **Compact settings:** less explanatory text, tighter section spacing, and music settings grouped with personalization.
- **Music player transitions:** capsule-fill expansion/collapse, progressive glass effects, and an inline scrollable queue connected to the mini player.
- **Playback UX:** smoother album artwork changes, improved swipe-to-dismiss recognition, lyrics and metadata improvements.
- **Stability:** fixed a Kyant lens shape compatibility crash on player expansion. The package identifier and original release signature are unchanged.

## Installation

Download a signed APK from [GitHub Releases](https://github.com/Chengeeker/WebAlbum/releases).

**[Download WebAlbum v1.5.3 (arm64-v8a)](https://github.com/Chengeeker/WebAlbum/releases/download/v1.5.3/WebAlbum_v1.5.3_arm64-v8a.apk)**

| Item | Requirement |
| --- | --- |
| Current version | **1.5.3** (version code 41) |
| Android | **8.0 / API 26 or later** |
| CPU architecture | **arm64-v8a** |
| Application ID | `com.webdav.album` |
| Media source | Your own WebDAV server |

There is no built-in public media library; you must provide a reachable WebDAV endpoint. **HTTPS is recommended**, as HTTP can expose credentials and media in transit.

## Getting started

1. Install the APK and open WebAlbum.
2. Enter your WebDAV server URL, username, and password.
3. Browse folders and tap a photo, video, or audio track to open or play it. Use the top-bar grid control and More menu for layout, sorting, and selection.
4. Long-press to select multiple files; use the bottom action bar to download, inspect properties, or delete them. Find other preferences under **More → Settings**.

**Warning:** Deleting a file removes it from your WebDAV server and may be irreversible. Back up important media first. Seeking in some videos requires proper HTTP Range support from your server; playback behavior also varies by device codec support.

## Building from source

WebAlbum uses **Kotlin + Jetpack Compose**, its bundled `lucent-ui` module, Media3/ExoPlayer, OkHttp, and Coil.

- Development setup: Android Studio, JDK 21, and an installed Android SDK with the project's required platform (the current `compileSdk` is API 37, minor API 2).
- Run JVM tests and build a debug APK (Windows PowerShell):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- Release signing uses the `WEBALBUM_KEYSTORE` and `WEBALBUM_KEY_PASSWORD` environment variables; refer to `app/build.gradle.kts`. **Do not commit signing keys, passwords, `local.properties`, or generated APK files.**

Repository layout:

```text
app/                  Android app, WebDAV/gallery/playback logic and tests
lucent-ui/            Bundled LucentUI Compose module
gradle/wrapper/       Gradle Wrapper
NOTICE.md             Third-party attribution and notices
README.md             Chinese documentation (default)
```

## Current status

Version 1.5.3 passes a signed Release build and 188 host-side unit tests (0 failures). Interruptible music transitions, background notifications, Bluetooth controls, and real WebDAV skip latency still require validation on physical devices. M4A-specific embedded lyrics support remains incomplete. Please share issues through [GitHub Issues](https://github.com/Chengeeker/WebAlbum/issues).

## License and credits

WebAlbum is released under the [MIT License](LICENSE). UI credits include [LucentUI](https://github.com/Chengeeker/LucentUI), [Review](https://github.com/Chengeeker/Review), and the author's [GreenMusic](https://github.com/Chengeeker/GreenMusic). Additional third-party licenses are documented in [NOTICE.md](NOTICE.md) and `lucent-ui/src/main/assets/licenses/`.
