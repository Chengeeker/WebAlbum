package com.webdav.album

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Looper
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.request.ErrorResult
import coil.ImageLoader
import coil.disk.DiskCache
import coil.decode.DataSource
import coil.request.Options
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@Implements(MediaMetadataRetriever::class)
class FakeVideoRetriever {
    companion object {
        @Volatile var forceUnscaled = false
        @Volatile var frameSize: Pair<String?, String?> = "640" to "480"
    }
    private var source: MediaDataSource? = null
    private var file: String? = null
    @Implementation fun setDataSource(value: MediaDataSource) { source = value }
    @Implementation fun setDataSource(value: String) { file = value }
    @Implementation fun getScaledFrameAtTime(time: Long, option: Int, width: Int, height: Int): Bitmap? = if (forceUnscaled) null else frame()
    @Implementation fun getFrameAtTime(time: Long, option: Int): Bitmap = frame()
    @Implementation fun extractMetadata(key: Int): String? = if (key == MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) frameSize.first else frameSize.second
    private fun frame(): Bitmap {
        if (source != null) {
            val data = ByteArray(4)
            check(requireNotNull(source).readAt(0, data, 0, data.size) == data.size)
        } else {
            val cached = java.io.File(requireNotNull(file))
            check(cached.length() <= 16L * 1024 * 1024 && cached.inputStream().use { it.readNBytes(10).toString(Charsets.UTF_8) } == "abcdefghij")
        }
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }
    @Implementation fun release() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class, shadows = [FakeVideoRetriever::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VideoThumbnailIntegrationTest {
    @Test fun unscaledFallbackRejectsUnknownOrExcessiveDimensions() {
        FakeVideoRetriever.forceUnscaled = true
        val retriever = MediaMetadataRetriever()
        try {
            for (size in listOf(null to "480", "12000" to "8000")) {
                FakeVideoRetriever.frameSize = size
                try { decodeFrame(retriever); fail("Unsafe video dimensions accepted") } catch (_: java.io.IOException) { }
            }
        } finally {
            retriever.release(); FakeVideoRetriever.forceUnscaled = false; FakeVideoRetriever.frameSize = "640" to "480"
        }
    }
    @Test fun fallsBackToUnscaledFrameWhenScaledDecoderReturnsNull() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setBody("a"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody("abcdefghij"))
            val media = DavItem(server.url("/dav/frame.mp4").toString(), "frame.mp4", false, "v1")
            var result: ImageResult? = null
            val loader = app.newImageLoader()
            FakeVideoRetriever.forceUnscaled = true
            try {
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Expected unscaled frame fallback, got $result", result is SuccessResult)
            } finally { FakeVideoRetriever.forceUnscaled = false; loader.shutdown() }
        }
    }
    @Test fun oldAccountThumbnailCannotBeFetchedUnderNewAccount() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val old = Connection(server.url("/dav/").toString(), "old", "secret")
            val media = DavItem(server.url("/dav/private.mp4").toString(), "private.mp4", false, "v1")
            val oldKey = Uri.parse(media.imageUrl(old))
            app.connection = old.copy(user = "new")
            val loader = app.newImageLoader()
            try {
                val factory = VideoThumbnailFetcher.Factory({ app.client }, app::connectionRevision, { app.connection })
                val fetcher = requireNotNull(factory.create(oldKey, Options(app, diskCachePolicy = CachePolicy.DISABLED), loader))
                try { fetcher.fetch(); fail("Old account cover must not be loaded") } catch (_: CancellationException) { }
                assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            } finally { loader.shutdown() }
        }
    }
    @Test fun generatedCoverIsReusedFromDiskWithoutAnotherVideoRequest() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setBody("a"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody("abcdefghij"))
            val media = DavItem(server.url("/dav/cached.mp4").toString(), "cached.mp4", false, "v1")
            val loader = ImageLoader.Builder(app).networkObserverEnabled(false)
                .components { add(VideoThumbnailFetcher.Factory({ app.client }, app::connectionRevision, { app.connection })) }
                .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("video-cover-test-${server.port}")).maxSizeBytes(1024 * 1024).build() }.build()
            fun load(): ImageResult {
                var result: ImageResult? = null
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                return result ?: error("Cover request timed out")
            }
            try {
                assertTrue(load() is SuccessResult)
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                val second = load()
                assertTrue("Expected disk cache, got $second", second is SuccessResult && second.dataSource == DataSource.DISK)
                assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            } finally { loader.shutdown() }
        }
    }
    @Test fun coilGridRequestUsesVideoFrameFetcherAndAuthentication() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setBody("a"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody("abcdefghij"))
            val media = DavItem(server.url("/dav/clip.mp4").toString(), "clip.mp4", false, "v1")
            var result: ImageResult? = null
            val loader = app.newImageLoader()
            try {
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Expected video frame, got $result", result is SuccessResult)
                assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization"))
                assertEquals("bytes=0-9", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
            } finally { loader.shutdown() }
        }
    }

    @Test fun coilGridFallsBackForSmallVideosWhenServerIgnoresRanges() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefghij")) }
            val media = DavItem(server.url("/dav/small.mp4").toString(), "small.mp4", false, "v1")
            var result: ImageResult? = null
            val loader = app.newImageLoader()
            try {
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Expected fallback video frame, got $result", result is SuccessResult)
                assertEquals("bytes=0-0", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Range"))
                assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization"))
                assertTrue(app.cacheDir.resolve("video-covers-temp").listFiles().orEmpty().isEmpty())
            } finally { loader.shutdown() }
        }
    }

    @Test fun fallbackRejectsIncompleteVideoAndDeletesTemporaryFile() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefghij"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("x").setHeader("Content-Length", "33554433"))
            val media = DavItem(server.url("/dav/large.mp4").toString(), "large.mp4", false, "v1")
            var result: ImageResult? = null
            val loader = app.newImageLoader()
            try {
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Expected a bounded fallback failure, got $result", result is ErrorResult)
                assertTrue(app.cacheDir.resolve("video-covers-temp").listFiles().orEmpty().isEmpty())
            } finally { loader.shutdown() }
        }
    }

    @Test fun fallbackTriesBoundedPrefixForLargeVideo() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefghij"))
            val prefix = ByteArray(4 * 1024 * 1024).also { "abcdefghij".toByteArray().copyInto(it) }
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(prefix)).setHeader("Content-Length", "33554433"))
            val media = DavItem(server.url("/dav/prefix.mp4").toString(), "prefix.mp4", false, "v1")
            var result: ImageResult? = null
            val loader = app.newImageLoader()
            try {
                loader.enqueue(ImageRequest.Builder(app).data(media.imageUrl(config)).memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED).listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Expected prefix frame, got $result", result is SuccessResult)
                assertTrue(app.cacheDir.resolve("video-covers-temp").listFiles().orEmpty().isEmpty())
            } finally { loader.shutdown() }
        }
    }
}
