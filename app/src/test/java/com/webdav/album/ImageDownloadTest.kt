package com.webdav.album

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(4 * 1024 * 1024 + 101) { (it * 31).toByte() }
    private val etag = "\"version-1\""

    @Test fun splitsLargeFileConcurrentlyAndCopiesExactBytes() = runBlocking {
        MockWebServer().use { server ->
            val allStarted = CountDownLatch(3)
            val simultaneous = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(bytes))
                    assertEquals("identity", request.getHeader("Accept-Encoding"))
                    if (range == "bytes=0-0") return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, 0, 1))
                    assertEquals(etag, request.getHeader("If-Range"))
                    val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range))
                    val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
                    allStarted.countDown()
                    if (allStarted.await(5, TimeUnit.SECONDS)) simultaneous.set(true)
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            server.start()
            val output = ByteArrayOutputStream()
            val dir = temp.root.resolve("parts")
            downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), dir, null) { output }
            assertTrue("all three ranges must be in flight together", simultaneous.get())
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(4, server.requestCount)
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }

    @Test fun fallsBackWhenServerIgnoresRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
            server.start()
            val output = ByteArrayOutputStream()
            downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), temp.root.resolve("parts"), null) { output }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(1, server.requestCount)
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun changedOrInvalidRangeFallsBackBeforeOpeningDestination() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, 0, 1))
                    null -> MockResponse().setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/${bytes.size}").setHeader("ETag", "\"changed\"").setBody("xx")
                }
            }
            server.start()
            var opens = 0
            val output = ByteArrayOutputStream()
            val dir = temp.root.resolve("parts")
            downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), dir, null) { opens++; output }
            assertEquals(1, opens)
            assertArrayEquals(bytes, output.toByteArray())
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }

    @Test fun smallKnownFileUsesSingleRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("small"))
            server.start()
            val output = ByteArrayOutputStream()
            downloadImage(OkHttpClient(), server.url("/small.jpg").toString(), temp.root.resolve("parts"), 5) { output }
            assertEquals("small", output.toString("UTF-8"))
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun weakEtagFallsBackToSingleRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${bytes.size}").setHeader("ETag", "W/$etag").setBody(Buffer().write(bytes, 0, 1)))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.start()
            val output = ByteArrayOutputStream()
            downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), temp.root.resolve("parts"), null) { output }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun ignoredIdentityEncodingFallsBackWithoutSavingCompressedData() = runBlocking {
        MockWebServer().use { server ->
            val compressed = ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
            }.toByteArray()
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(compressed))) }
            server.start()
            val output = ByteArrayOutputStream()
            downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), temp.root.resolve("parts"), null) { output }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }

    @Test fun cancellationStopsRequestsAndRemovesTemporaryParts() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Range") == "bytes=0-0") return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${bytes.size}").setHeader("ETag", etag).setBody(Buffer().write(bytes, 0, 1))
                    started.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            server.start()
            var opened = false
            val dir = temp.root.resolve("parts")
            val job = async(Dispatchers.IO) { downloadImage(OkHttpClient(), server.url("/photo.jpg").toString(), dir, null) { opened = true; ByteArrayOutputStream() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(opened)
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }
}
