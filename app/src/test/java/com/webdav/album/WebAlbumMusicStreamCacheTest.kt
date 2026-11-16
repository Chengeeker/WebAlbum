package com.webdav.album

import kotlinx.coroutines.runBlocking
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
class WebAlbumMusicStreamCacheTest {
    @Test fun neighborIndicesAreBoundedAndDoNotRepeatCurrent() {
        assertEquals(emptyList<Int>(), WebAlbumMusicStreamCache.neighbors(0, 0))
        assertEquals(emptyList<Int>(), WebAlbumMusicStreamCache.neighbors(1, 0))
        assertEquals(listOf(1), WebAlbumMusicStreamCache.neighbors(3, 0))
        assertEquals(listOf(2, 0), WebAlbumMusicStreamCache.neighbors(3, 1))
        assertEquals(listOf(1), WebAlbumMusicStreamCache.neighbors(3, 2))
        assertEquals(emptyList<Int>(), WebAlbumMusicStreamCache.neighbors(3, -1))
    }

    @Test fun playbackCacheKeysAreIsolatedByAccountAndFileVersion() {
        val track = MusicTrack("https://example.test/dav/song.flac", "v1", "song.flac")
        val owner = Connection("https://example.test/dav/", "alice", "secret1")
        val another = owner.copy(user = "bob")
        assertNotEquals(WebAlbumMusicStreamCache.key(track, owner),
            WebAlbumMusicStreamCache.key(track, another))
        assertNotEquals(WebAlbumMusicStreamCache.key(track, owner),
            WebAlbumMusicStreamCache.key(track.copy(version = "v2"), owner))
    }

    @Test fun neighborWarmupFetchesBoundedRangesAndReusesDiskCache() = runBlocking {
        MockWebServer().use { server ->
            val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path !in listOf("/dav/zero.mp3", "/dav/two.mp3"))
                        return MockResponse().setResponseCode(404)
                    val expectedRange = "bytes=0-" + (WebAlbumMusicStreamCache.WARM_BYTES - 1)
                    if (request.getHeader("Range") != expectedRange)
                        return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range",
                            "bytes 0-" + (WebAlbumMusicStreamCache.WARM_BYTES - 1) +
                            "/" + data.size)
                        .setBody(Buffer().write(data, 0, WebAlbumMusicStreamCache.WARM_BYTES))
                }
            }
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val connection = Connection(server.url("/dav/").toString(), "alice", "pw")
            app.connection = connection
            val queue = listOf("zero", "one", "two").map { name ->
                MusicTrack(server.url("/dav/$name.mp3").toString(), "r1", "$name.mp3")
            }
            try {
                val cache = app.musicStreamCache
                cache.warmNeighbors(queue, 1, connection)
                assertEquals(2, server.requestCount)
                cache.warmNeighbors(queue, 1, connection)
                assertEquals("Repeated warm-up should reuse the exact same spans", 2, server.requestCount)
                assertEquals("/dav/two.mp3", server.takeRequest().path)
                assertEquals("/dav/zero.mp3", server.takeRequest().path)
            } finally { app.connection = null }
        }
    }
}
