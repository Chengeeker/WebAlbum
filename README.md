# WebAlbum

WebAlbum is an Android gallery for browsing photos and videos stored on a WebDAV server. It includes folder browsing, image and video playback, Live Photo support, configurable layouts, caching, and optional app locking.

The app uses the in-repository `lucent-ui` Compose module. The Android application ID is `com.webdav.album`; the current source version is 1.2.2 (version code 8).

## Build

Open this project in Android Studio with Android SDK 36 and JDK 17, or build from the project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

Release signing is configured through environment variables by the local release process. Do not commit keystores, passwords, `local.properties`, or generated APKs.

The release build targets `arm64-v8a` and Android 8.0 (API 26) or newer.
