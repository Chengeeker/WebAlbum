package com.webdav.album

import kotlinx.coroutines.CancellationException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class VideoRangeCacheTest {
    @Test fun rereadingAnEarlierBlockDoesNotDownloadItAgain() {
        MockWebServer().use { server ->
            val bytes = ByteArray(410000) { (it % 251).toByte() }
            val etag = "\"video-cache-v1\""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
                    if (range == "bytes=0-0") return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/" + bytes.size)
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(bytes, 0, 1))
                    val match = Regex("""bytes=(\d+)-(\d+)""").matchEntire(range)
                        ?: return MockResponse().setResponseCode(400)
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toInt()
                    if (request.getHeader("If-Match") != etag || start > end || end >= bytes.size)
                        return MockResponse().setResponseCode(400)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.size)
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "password")
            val source = WebDavRangeSource(app.client, server.url("/dav/clip.mp4").toString())
            try {
                val target = ByteArray(32)
                assertEquals(32, source.readAt(5, target, 0, target.size))
                assertArrayEquals(bytes.copyOfRange(5, 37), target)
                assertEquals(32, source.readAt(140000, target, 0, target.size))
                assertArrayEquals(bytes.copyOfRange(140000, 140032), target)
                assertEquals(32, source.readAt(6, target, 0, target.size))
                assertArrayEquals(bytes.copyOfRange(6, 38), target)
                assertEquals(2, source.rangeRequestCount)
                assertEquals(3, server.requestCount)
                assertEquals(2L * 128 * 1024, source.rangeDownloadedBytes)
            } finally { source.close(); app.connection = null }
        }
    }

    @Test fun connectionRevisionCancelsCachedReadsToo() {
        MockWebServer().use { server ->
            val bytes = ByteArray(4096) { it.toByte() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Range") == "bytes=0-0")
                        return MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes 0-0/" + bytes.size)
                            .setBody(Buffer().write(bytes, 0, 1))
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-4095/" + bytes.size)
                        .setBody(Buffer().write(bytes))
                }
            }
            server.start()
            var valid = true
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val source = WebDavRangeSource(app.client, server.url("/clip.mp4").toString()) { valid }
            val destination = ByteArray(8)
            assertEquals(8, source.readAt(0, destination, 0, 8))
            valid = false
            try {
                source.readAt(0, destination, 0, 8)
                fail("Invalid connection must fail even on cached bytes")
            } catch (_: CancellationException) { }
        }
    }
}
