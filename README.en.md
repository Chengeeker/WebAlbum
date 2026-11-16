# WebAlbum

**An Android WebDAV photo and video browser/player.** Connect to your own WebDAV server to explore folders, preview photos, play videos, and manage remote media from your phone.

[简体中文](README.md) · [English](README.en.md) · [Latest release](https://github.com/Chengeeker/WebAlbum/releases/latest) · [LucentUI](https://github.com/Chengeeker/LucentUI)

## Overview

WebAlbum is a lightweight **WebDAV media browser and player**, not a hosted cloud storage service. You can browse and play supported media stored on your WebDAV server without first syncing the whole library to your phone, and download selected files when needed.

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

### Gallery and file operations
- **Folders and grid layout:** Browse nested directories and see photos/videos in a unified media grid. Choose 1–5 media columns, with a separate folder-grid preference. Optional filename cards in 1–3 columns can trim common Screenshot, IMG, VID, and WeChat-export prefixes.
- **Quick navigation:** Pull to refresh and double-tap the top-bar title to scroll to the top.
- **Sorting:** Sort by capture time, modified time, or filename, in ascending or descending order. Most recently modified files appear first by default.
- **Range selection:** Long-press and drag to select multiple items; drag back toward the start to shrink the selection. Download, inspect properties, or delete selected files.
- **Local downloads:** Choose a save destination through the Android system file picker or configure a folder for batch downloads.

> **About capture-time sorting:** WebDAV may not expose capture timestamps. WebAlbum currently prefers EXIF metadata available from cached images, falling back to the remote modified time when no capture timestamp is available (including for many videos). It does not guarantee a true chronological capture order for every file.

### Design, haptics, and privacy
- **LucentUI + Material 3:** Theme colors, font and layout settings, and optional liquid-glass visuals.
- **Interaction feedback:** Page transition effects, touch and range-selection haptics, with settings to disable animations or haptic feedback.
- **App protection:** Optional PIN/biometric locking and screenshot protection.
- **Settings backup:** Export/restore non-sensitive preferences. WebDAV credentials are not included in the settings export.

## Installation

Download a signed APK from [GitHub Releases](https://github.com/Chengeeker/WebAlbum/releases).

**[Download WebAlbum v1.2.9 (arm64-v8a)](https://github.com/Chengeeker/WebAlbum/releases/download/v1.2.9/WebAlbum_v1.2.9_arm64-v8a.apk)**

| Item | Requirement |
| --- | --- |
| Current version | **1.2.9** (version code 15) |
| Android | **8.0 / API 26 or later** |
| CPU architecture | **arm64-v8a** |
| Application ID | `com.webdav.album` |
| Media source | Your own WebDAV server |

There is no built-in public media library; you must provide a reachable WebDAV endpoint. **HTTPS is recommended**, as HTTP can expose credentials and media in transit.

## Getting started

1. Install the APK and open WebAlbum.
2. Enter your WebDAV server URL, username, and password.
3. Browse folders and tap a photo/video to view or play it. Use the top-bar grid control and More menu for layout, sorting, and selection.
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

Version 1.2.9 passes a signed Release build and 84 host-side unit tests (0 failures). High-resolution region decoding, the liquid-glass capsule, and HDR behavior still need real-device validation on different Android devices. Please share reproducible issues through [GitHub Issues](https://github.com/Chengeeker/WebAlbum/issues).

## License and credits

WebAlbum is released under the [MIT License](LICENSE). UI credits include [LucentUI](https://github.com/Chengeeker/LucentUI) and [Review](https://github.com/Chengeeker/Review). Additional third-party licenses are documented in [NOTICE.md](NOTICE.md) and `lucent-ui/src/main/assets/licenses/`.
