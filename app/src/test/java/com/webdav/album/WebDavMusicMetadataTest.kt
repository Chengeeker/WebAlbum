package com.webdav.album

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class WebDavMusicMetadataTest {
    @Test fun sidecarLyricsLoadWithoutRequestingWholeSong() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("[00:01.50] 第一行\n[00:03.20] 第二行"))
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val conn = Connection(server.url("/dav/").toString(), "alice", "pw")
            app.connection = conn
            try {
                val track = MusicTrack(server.url("/dav/music.flac").toString(), "ver1", "music.flac")
                val read = WebDavMusicMetadata(app).lyrics(track,conn)
                assertEquals(2,read.lines.size)
                assertEquals(1500L,read.lines.first().timeMs)
                assertEquals("/dav/music.lrc",server.takeRequest().path)
                assertEquals(1,server.requestCount)
            } finally { app.connection = null }
        }
    }

    @Test fun missingSidecarFallsBackToProvidedEmbeddedLyrics() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val conn = Connection(server.url("/dav/").toString(),"alice","pw")
            app.connection = conn
            try {
                val track = MusicTrack(server.url("/dav/track.mp3").toString(),"ver2","track.mp3")
                val tags=EmbeddedMusicTags(lyrics="[00:07.21] 文件内歌词")
                val result=WebDavMusicMetadata(app).lyrics(track,conn,tags)
                assertEquals(7210L,result.lines.single().timeMs)
                assertEquals(1,server.requestCount)
            } finally { app.connection = null }
        }
    }

    @Test fun twoMetadataConsumersWaitForOneSharedRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setBody("ID3\\u0003\\u0000\\u0000\\u0000\\u0000\\u0000\\u0000")
                .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val connection = Connection(server.url("/dav/").toString(), "alice", "pw")
            app.connection = connection
            try {
                val track = MusicTrack(server.url("/dav/shared.mp3").toString(), "v1", "shared.mp3")
                val playback = WebAlbumMusicPlayback(app)
                val results = listOf(
                    async { playback.warmTags(track, connection); playback.cachedTags(track, connection) },
                    async { playback.warmTags(track, connection); playback.cachedTags(track, connection) },
                ).awaitAll()
                assertEquals(1, server.requestCount)
                assertTrue("both rows must wait for the same completed metadata probe",
                    results.all { it != null })
            } finally {
                app.connection = null
            }
        }
    }

    @Test fun wrongOriginNeverFetchesAudioOrSidecar() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            val conn = Connection(server.url("/dav/").toString(),"alice","pw")
            app.connection = conn
            try {
                val track=MusicTrack("https://example.org/audio.flac","v","audio.flac")
                val reader=WebDavMusicMetadata(app)
                assertEquals(EmbeddedMusicTags(),reader.read(track,conn))
                assertEquals(MusicLyrics(),reader.lyrics(track,conn))
                assertEquals(0,server.requestCount)
            } finally { app.connection = null }
        }
    }

    @Test fun audioTagProbeUsesBoundedHttpRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range","bytes 0-10/12000000")
                .setBody(Buffer().writeUtf8("ID3").write(ByteArray(8))))
            server.start()
            val app=RuntimeEnvironment.getApplication() as AlbumApplication
            val conn=Connection(server.url("/dav/").toString(),"alice","pw")
            app.connection=conn
            try {
                val track=MusicTrack(server.url("/dav/track.mp3").toString(),"v","track.mp3")
                WebDavMusicMetadata(app).read(track,conn)
                assertEquals("bytes=0-1048575",server.takeRequest().getHeader("Range"))
            } finally { app.connection=null }
        }
    }
}
