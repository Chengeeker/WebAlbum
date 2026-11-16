package com.webdav.album

import dev.chengeeker.lucent.review.GalleryMedia
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class WebDavGalleryOriginalLoaderTest {
    @Test fun completeOriginalBytesAreCachedAndReusedWithoutExtraRequest() = runBlocking {
        MockWebServer().use { server ->
            val data = ByteArray(256 * 1024) { (it % 239).toByte() }
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody(Buffer().write(data)))
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            val original = WebDavGalleryOriginalLoader(app, app, config)
            val media = GalleryMedia(server.url("/dav/large.jpeg").toString() + "#v1")
            val progress = mutableListOf<Pair<Long, Long?>>()
            try {
                val first = original.obtain(media) { loaded, total -> progress += loaded to total }
                assertArrayEquals(data, first.readBytes())
                assertTrue(progress.isNotEmpty())
                assertEquals(data.size.toLong(), progress.last().first)
                assertEquals(data.size.toLong(), progress.last().second)
                assertEquals(1, server.requestCount)
                val second = original.obtain(media) { _, _ -> }
                assertEquals(first.canonicalPath, second.canonicalPath)
                assertEquals(1, server.requestCount)
            } finally { app.connection = null }
        }
    }

    @Test fun originalDownloadRejectsDifferentOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            val loader = WebDavGalleryOriginalLoader(app, app, config)
            try {
                loader.obtain(GalleryMedia("https://example.org/private/image.jpg#v1")) { _, _ -> }
                fail("Cross-origin originals must be rejected")
            } catch (_: IOException) {
                assertEquals(0, server.requestCount)
            } finally { app.connection = null }
        }
    }
}
