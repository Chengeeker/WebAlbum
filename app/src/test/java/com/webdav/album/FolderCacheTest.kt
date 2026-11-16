package com.webdav.album

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class FolderCacheTest {
    @Test fun galleryItemsPreserveOrderIdentityAndVideoPlaybackUrl() {
        val connection = Connection("https://example.com/dav/", "alice", "secret")
        val items = listOf(DavItem("https://example.com/dav/a.gif", "a.gif", false, "v1"),
            DavItem("https://example.com/dav/b.mp4", "b.mp4", false, "v2"))
        val media = GalleryItems(items, connection)
        assertEquals(2, media.size)
        assertTrue(media[0].gif)
        assertEquals("a.gif", media[0].description)
        assertEquals(items[1].url, media[1].video)
        assertTrue(media[1].url.contains("~${connection.videoScope}"))
        assertSame(media[1], media[1])
    }
    @Test fun cacheIsBoundedExpiresAndCanBeInvalidated() {
        var time = 1000L
        val cache = FolderCache { time }
        val value = listOf(DavItem("https://example.com/f/a.jpg", "a.jpg", false, "v1"))
        repeat(6) { cache.put("$it", value) }
        assertEquals(value, cache.get("0"))
        cache.put("6", value)
        assertNull(cache.get("1"))
        assertEquals(value, cache.get("0"))
        time += 30_000
        assertNull(cache.get("0"))
        cache.put("new", value); cache.clear()
        assertNull(cache.get("new"))
    }

    @Test fun prefetchIsBoundedAndNeverStoresResultsAfterConnectionChanges() = runBlocking {
        val cache = FolderCache { 1000L }
        val folders = (1..4).map { DavItem("https://example.com/dav/$it/", "$it", true, "") }
        val loaded = mutableListOf<String>()
        val content = listOf(DavItem("https://example.com/dav/1/a.jpg", "a.jpg", false, ""))
        cache.prefetch(folders, { true }) { loaded += it.url; content }
        assertEquals(folders.take(2).map { it.url }, loaded)
        assertEquals(content, cache.get(folders[0].url))
        assertNull(cache.get(folders[2].url))
        cache.clear(); loaded.clear()
        var current = true
        cache.prefetch(folders, { current }) { loaded += it.url; current = false; content }
        assertEquals(listOf(folders[0].url), loaded)
        assertNull(cache.get(folders[0].url))
    }

    @Test fun cancellingDirectoryRequestDoesNotFallBackToRoot() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val config = Connection(server.url("/dav/").toString(), "alice", "secret")
            app.connection = config
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { DavClient(app.client).listFolderAsync(config, listOf(server.url("/dav/photos/").toString()), true) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        }
    }
}
