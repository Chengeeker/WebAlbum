package com.webdav.album

import android.os.Looper
import android.util.Base64
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.request.ErrorResult
import coil.network.HttpException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ImageLoadingTest {
    @Test fun webdavImagesUseAuthenticatedLoaderAndCache() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = connection
            val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/oAAAAAASUVORK5CYII=", Base64.DEFAULT)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val item = DavItem(server.url("/dav/photo.png").toString(), "photo.png", false, "version1")
            val loader = Coil.imageLoader(app)
            fun load(image: DavItem = item): ImageResult {
                var result: ImageResult? = null
                loader.enqueue(ImageRequest.Builder(app).data(image.imageUrl(connection))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < end) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                return result ?: error("Image request timed out")
            }
            val first = load()
            assertTrue("Image result: $first", first is SuccessResult)
            val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No image request")
            assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
            assertEquals("/dav/photo.png", request.path)
            assertTrue("Cached image failed", load() is SuccessResult)
            assertNull("Expected disk cache hit", server.takeRequest(300, TimeUnit.MILLISECONDS))
            val jpeg = requireNotNull(javaClass.getResourceAsStream("/landscape.jpg")).use { it.readBytes() }
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            assertTrue("JPEG image failed", load(DavItem(server.url("/dav/photo.jpg").toString(), "photo.jpg", false, "v1")) is SuccessResult)
            server.takeRequest(2, TimeUnit.SECONDS) ?: error("No JPEG request")
            server.enqueue(MockResponse().setResponseCode(401))
            val rejected = load(DavItem(server.url("/dav/private.png").toString(), "private.png", false, "v1"))
            assertTrue("Expected HTTP 401", rejected is ErrorResult && (rejected.throwable as? HttpException)?.response?.code == 401)
        }
    }

    @Test fun multipleImagesAreRequestedConcurrently() {
        MockWebServer().use { server ->
            val starts = CountDownLatch(6)
            val simultaneous = AtomicBoolean(false)
            val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/oAAAAAASUVORK5CYII=", Base64.DEFAULT)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    starts.countDown()
                    if (starts.await(5, TimeUnit.SECONDS)) simultaneous.set(true)
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                }
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.applyNetwork(NetworkSettings())
            val connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = connection
            val loader = app.newImageLoader()
            var completed = 0
            var failed = 0
            repeat(6) { index ->
                val item = DavItem(server.url("/dav/$index.png").toString(), "$index.png", false, "v1")
                loader.enqueue(ImageRequest.Builder(app).data(item.imageUrl(connection)).diskCachePolicy(CachePolicy.DISABLED)
                    .listener(onSuccess = { _, _ -> completed++ }, onError = { _, _ -> completed++; failed++ }).build())
            }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (completed < 6 && System.nanoTime() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(6, completed)
            assertEquals(0, failed)
            assertTrue("six image requests should overlap", simultaneous.get())
            loader.shutdown()
        }
    }

    @Test fun largeImagePreviewUsesRangesAndThenDiskCache() {
        MockWebServer().use { server ->
            val jpeg = requireNotNull(javaClass.getResourceAsStream("/landscape.jpg")).use { it.readBytes() }
            val bytes = jpeg.copyOf(4 * 1024 * 1024 + 19)
            val etag = "\"preview-v1\""
            val starts = CountDownLatch(3)
            val simultaneous = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
                    val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(bytes))
                    if (range == "bytes=0-0") return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, 0, 1))
                    assertEquals(etag, request.getHeader("If-Range"))
                    val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range))
                    val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
                    starts.countDown()
                    if (starts.await(5, TimeUnit.SECONDS)) simultaneous.set(true)
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = connection
            val loader = ImageLoader.Builder(app).okHttpClient(app.client).networkObserverEnabled(false)
                .components { add(ParallelImageFetcher.Factory(app.client, app.cacheDir.resolve("download-parts"))) }
                .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("preview-test-${System.nanoTime()}")).maxSizeBytes(32L * 1024 * 1024).build() }
                .build()
            val item = DavItem(server.url("/dav/photo.jpg").toString(), "photo.jpg", false, "$etag||${bytes.size}")
            fun load(): ImageResult {
                var result: ImageResult? = null
                loader.enqueue(ImageRequest.Builder(app).data(item.imageUrl(connection)).memoryCachePolicy(CachePolicy.DISABLED)
                    .listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (result == null && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                return result ?: error("Image request timed out")
            }
            val first = load()
            assertTrue("Preview result: $first", first is SuccessResult)
            assertTrue("Preview ranges should overlap", simultaneous.get())
            assertEquals(4, server.requestCount)
            assertTrue("Cached preview failed", load() is SuccessResult)
            assertEquals("Cached preview should not reconnect", 4, server.requestCount)
            loader.shutdown()
        }
    }

    @Test fun largePreviewPreservesAuthenticationError() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val connection = Connection(server.url("/dav/").toString(), "alice", "bad")
            app.connection = connection
            val loader = ImageLoader.Builder(app).okHttpClient(app.client).networkObserverEnabled(false)
                .components { add(ParallelImageFetcher.Factory(app.client, app.cacheDir.resolve("download-parts"))) }
                .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("error-test-${System.nanoTime()}")).maxSizeBytes(32L * 1024 * 1024).build() }
                .build()
            val item = DavItem(server.url("/dav/private.jpg").toString(), "private.jpg", false, "etag||5000000")
            var result: ImageResult? = null
            loader.enqueue(ImageRequest.Builder(app).data(item.imageUrl(connection))
                .listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (result == null && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            val error = result as? ErrorResult
            assertEquals(401, (error?.throwable as? HttpException)?.response?.code)
            loader.shutdown()
        }
    }

    @Test fun existingImageLoaderUsesUpdatedProxyForNewRequests() {
        MockWebServer().use { proxy ->
            proxy.start()
            val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/oAAAAAASUVORK5CYII=", Base64.DEFAULT)
            proxy.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.applyNetwork(NetworkSettings())
            val connection = Connection("http://example.invalid/dav/", "alice", "secret")
            app.connection = connection
            val loader = app.newImageLoader()
            try {
                app.applyNetwork(NetworkSettings(mode = "http", host = proxy.hostName, port = proxy.port))
                val item = DavItem("http://example.invalid/dav/photo.png", "photo.png", false, "v1")
                var result: ImageResult? = null
                loader.enqueue(ImageRequest.Builder(app).data(item.imageUrl(connection)).diskCachePolicy(CachePolicy.DISABLED)
                    .listener(onSuccess = { _, value -> result = value }, onError = { _, value -> result = value }).build())
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (result == null && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Updated proxy request: $result", result is SuccessResult)
                val request = proxy.takeRequest(2, TimeUnit.SECONDS) ?: error("Proxy received no image request")
                assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
            } finally {
                app.applyNetwork(NetworkSettings())
                loader.shutdown()
            }
        }
    }
}
