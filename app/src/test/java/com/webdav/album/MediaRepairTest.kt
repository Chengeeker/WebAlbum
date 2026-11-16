package com.webdav.album

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import android.net.Uri
import android.os.Looper
import android.view.LayoutInflater
import android.view.TextureView
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.mp4.MotionPhotoMetadata
import coil.Coil
import dev.chengeeker.lucent.review.GalleryMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaRepairTest {
    @Test fun playerUsesTextureViewSoGalleryChromeCanDrawAboveVideo() {
        val context = RuntimeEnvironment.getApplication() as AlbumApplication
        val player = LayoutInflater.from(context).inflate(dev.chengeeker.lucent.R.layout.lucent_video_texture, null, false)
        assertTrue(player.javaClass.getMethod("getVideoSurfaceView").invoke(player) is TextureView)
    }
    @Test fun hdrPlayerUsesSurfaceViewWhileOrdinaryPlaybackKeepsTextureView() {
        val context = RuntimeEnvironment.getApplication() as AlbumApplication
        val view = LayoutInflater.from(context).inflate(dev.chengeeker.lucent.R.layout.lucent_video_hdr_surface, null, false)
        assertTrue(view.javaClass.getMethod("getVideoSurfaceView").invoke(view) is SurfaceView)
    }

    private fun motionFixture(): ByteArray {
        val jpeg = requireNotNull(javaClass.getResourceAsStream("/landscape.jpg")).use { it.readBytes() }
        val video = requireNotNull(javaClass.getResourceAsStream("/motion-fixture.mp4")).use { it.readBytes() }
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${video.size}"/></rdf:RDF></x:xmpmeta>"""
        val xmp = ("http://ns.adobe.com/xap/1.0/\u0000" + xml).toByteArray()
        val segmentLength = xmp.size + 2
        return ByteArrayOutputStream().use { out ->
            out.write(jpeg, 0, 2); out.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), (segmentLength shr 8).toByte(), segmentLength.toByte()))
            out.write(xmp); out.write(jpeg, 2, jpeg.size - 2); out.write(video)
            out.toByteArray()
        }
    }
    @Test fun media3FindsMotionMetadataInSyntheticPhotoWithRealVideo() {
        val file = Files.createTempFile("live-photo", ".data").toFile()
        file.writeBytes(motionFixture())
        val retriever = MetadataRetriever.Builder(RuntimeEnvironment.getApplication(), MediaItem.fromUri(Uri.fromFile(file))).build()
        val future = retriever.retrieveTrackGroups()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!future.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            val groups = future.get(1, TimeUnit.SECONDS)
            val found = (0 until groups.length).flatMap { index ->
                val metadata = groups[index].getFormat(0).metadata
                if (metadata == null) emptyList() else (0 until metadata.length()).mapNotNull { metadata[it] as? MotionPhotoMetadata }
            }
            assertTrue("Expected real embedded video metadata", found.any { hasMotionVideo(file, it.videoStartPosition, it.videoSize) })
        } finally { future.cancel(true); retriever.close(); file.delete() }
    }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    @Test fun detectorRecognizesOnlyCachedMotionPhotos() {
        val app = RuntimeEnvironment.getApplication() as AlbumApplication
        val cache = requireNotNull(Coil.imageLoader(app).diskCache)
        val normalKey = "https://example.com/dav/normal.jpg#fixture-normal"
        val motionKey = "https://example.com/dav/live.jpg#fixture-live"
        fun cacheFile(key: String, bytes: ByteArray) {
            cache.remove(key)
            val editor = requireNotNull(cache.openEditor(key))
            editor.data.toFile().writeBytes(bytes); editor.metadata.toFile().writeBytes(byteArrayOf())
            requireNotNull(editor.commitAndOpenSnapshot()).close()
        }
        cacheFile(normalKey, requireNotNull(javaClass.getResourceAsStream("/landscape.jpg")).use { it.readBytes() })
        cacheFile(motionKey, motionFixture())
        val detector = MotionPhotoDetector(app)
        fun detect(key: String): String? {
            val done = AtomicBoolean(false); val result = AtomicReference<String?>(); val failure = AtomicReference<Throwable?>()
            val job = CoroutineScope(Dispatchers.Default).launch {
                try { result.set(detector.find(GalleryMedia(key))) } catch (error: Throwable) { failure.set(error) } finally { done.set(true) }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!done.get() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            if (!done.get()) { job.cancel(); fail("Detection timed out") }
            failure.get()?.let { throw AssertionError("Detection failed", it) }
            return result.get()
        }
        try {
            assertNull(detect(normalKey))
            assertEquals("https://example.com/dav/live.jpg", detect(motionKey))
        } finally { cache.remove(normalKey); cache.remove(motionKey) }
    }
    @Test fun motionMetadataMustPointToAnExistingVideoContainer() {
        val file = Files.createTempFile("motion", ".jpg").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0, 0, 0, 0, 0))
            assertTrue(hasMotionVideo(file, 4, 16))
            assertFalse(hasMotionVideo(file, 0, 16))
            assertFalse(hasMotionVideo(file, 4, 17))
            assertFalse(hasMotionVideo(file, Long.MAX_VALUE, 20))
            file.setWritable(true); file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertFalse(hasMotionVideo(file, 4, 16))
        } finally { file.delete() }
    }

    @Test fun videoCoverUsesAuthenticatedBoundedRangesAndStrongVersion() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setHeader("ETag", "\"v1\"").setBody("a"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody("abcdefghij"))
            val source = WebDavRangeSource(app.client, server.url("/dav/a.mp4").toString())
            val result = ByteArray(4)
            assertEquals(10L, source.size)
            assertEquals(4, source.readAt(3, result, 0, 4))
            assertEquals("defg", String(result))
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("Basic YWxpY2U6c2VjcmV0", first.getHeader("Authorization"))
            assertEquals("bytes=0-9", second.getHeader("Range"))
            assertEquals("\"v1\"", second.getHeader("If-Match"))
            assertEquals(3, source.readAt(7, result, 0, 3))
            assertEquals("hij", String(result, 0, 3))
            assertEquals(-1, source.readAt(10, result, 0, 4))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            source.close()
        }
    }

    @Test fun videoCoverRejectsServerThatIgnoresRange() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200).setBody("full video"))
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            try { WebDavRangeSource(app.client, server.url("/dav/a.mp4").toString()); fail("Range support required") }
            catch (_: IOException) { }
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun coverRangeStopsWhenConnectionChanges() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setBody("a"))
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "one", "secret")
            val revision = app.connectionRevision()
            val source = WebDavRangeSource(app.client, server.url("/dav/a.mp4").toString()) { app.connectionRevision() == revision }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            app.connection = Connection(server.url("/dav/").toString(), "two", "secret")
            try { source.readAt(0, ByteArray(4), 0, 4); fail("Old connection must be cancelled") } catch (_: CancellationException) { }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            source.close()
        }
    }
}
