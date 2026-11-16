package com.webdav.album

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MusicMediaAndLyricsTest {
    private fun be32(v:Int)=byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(),
        (v ushr 8).toByte(), v.toByte())
    private fun le32(v:Int)=byteArrayOf(v.toByte(), (v ushr 8).toByte(),
        (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun sync(v:Int)=byteArrayOf((v ushr 21 and 127).toByte(), (v ushr 14 and 127).toByte(),
        (v ushr 7 and 127).toByte(), (v and 127).toByte())
    private fun frame(tag:String,payload:ByteArray):ByteArray=
        tag.toByteArray(Charsets.US_ASCII)+be32(payload.size)+byteArrayOf(0,0)+payload
    private fun id3(vararg frames:ByteArray):ByteArray {
        val raw=frames.flatMap { it.toList() }.toByteArray()
        return "ID3".toByteArray()+byteArrayOf(3,0,0)+sync(raw.size)+raw
    }
    private fun utf8(value:String):ByteArray=byteArrayOf(3)+value.toByteArray(Charsets.UTF_8)
    private fun flac(tags:List<String>):ByteArray {
        val vendor="GreenMusic".toByteArray()
        val body=ByteArrayOutputStream().apply {
            write(le32(vendor.size));write(vendor);write(le32(tags.size))
            tags.forEach { val b=it.toByteArray();write(le32(b.size));write(b) }
        }.toByteArray()
        return "fLaC".toByteArray()+
            byteArrayOf((128 or 4).toByte(),(body.size ushr 16).toByte(),
                (body.size ushr 8).toByte(),body.size.toByte())+body
    }

    @Test fun supportsCommonAudioExtensionsWithoutTreatingFoldersAsTracks() {
        for (name in listOf("demo.MP3","album.FLAC","voice.m4a","ogg.ogg","opus.opus",
            "raw.wav","aa.aiff","aa.wma")) assertTrue(name, isAudioFile(name))
        assertFalse(isAudioFile("sample.mp4"))
        assertFalse(isAudioFile("music.mp3",directory=true))
        assertFalse(isAudioFile("photo.jpg"))
    }
    @Test fun id3UsltIsReadWithoutSeparateLrc() {
        val tags=parseEmbeddedMusicTags(id3(
            frame("TIT2",utf8("第一首歌")),
            frame("TPE1",utf8("演唱者")),
            frame("TALB",utf8("专辑 A")),
            frame("USLT",byteArrayOf(3)+"und".toByteArray()+byteArrayOf(0)+
                "[00:10.00]你好\n[00:13.10]世界".toByteArray())
        ))
        assertEquals("第一首歌",tags.title)
        assertEquals("演唱者",tags.artist)
        assertEquals("专辑 A",tags.album)
        assertEquals(listOf(10000L,13100L),parseMusicLyrics(tags.lyrics).lines.map { it.timeMs })
    }
    @Test fun id3RawLyricsStayReadableWithoutTimestamps() {
        val tags=parseEmbeddedMusicTags(id3(
            frame("USLT",byteArrayOf(3)+"und".toByteArray()+byteArrayOf(0)+
                "非同步歌词第一行\n第二行".toByteArray())
        ))
        val lines=parseMusicLyrics(tags.lyrics)
        assertTrue(lines.lines.isEmpty())
        assertTrue(lines.plain.contains("第二行"))
    }
    @Test fun flacVorbisCommentsReadEmbeddedLyricsAndTags() {
        val tags=parseEmbeddedMusicTags(flac(listOf(
            "TITLE=夏天","ARTIST=歌手","ALBUM=测试唱片",
            "LYRICS=[00:03.25]测试歌词"
        )))
        assertEquals("夏天",tags.title)
        assertEquals("歌手",tags.artist)
        assertEquals("测试唱片",tags.album)
        assertEquals(3250L,parseMusicLyrics(tags.lyrics).lines.single().timeMs)
    }
    @Test fun multilineLrcOffsetAndRepeatedTimestamps() {
        val lyrics=parseMusicLyrics("[offset:250]\n[00:05.00][00:10.50]重复歌词")
        assertEquals(listOf(5250L,10750L),lyrics.lines.map { it.timeMs })
    }
    @Test fun malformedMusicMetadataIsIgnoredSafely() {
        assertEquals(EmbeddedMusicTags(),parseEmbeddedMusicTags(byteArrayOf()))
        assertEquals(EmbeddedMusicTags(),parseEmbeddedMusicTags(byteArrayOf(73,68,51,3,0,0,127,127,127,127)))
        assertEquals(EmbeddedMusicTags(),parseEmbeddedMusicTags("fLaC".toByteArray()))
    }
    @Test fun id3EmbeddedAlbumArtworkIsAvailableBeforePlayback() {
        val picture = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        )
        val apic = byteArrayOf(3) + "image/jpeg".toByteArray() +
            byteArrayOf(0, 3, 0) + picture
        val tags = parseEmbeddedMusicTags(id3(
            frame("TIT2", utf8("测试歌曲")),
            frame("TPE1", utf8("测试歌手")),
            frame("APIC", apic),
        ))
        assertEquals("测试歌曲", tags.title)
        assertEquals("测试歌手", tags.artist)
        assertArrayEquals(picture, tags.cover)
    }

    @Test fun playbackQueueUsesDirectorySort() {
        val items=listOf(
            DavItem("https://example.com/2.flac","B.flac",false,"x||1"),
            DavItem("https://example.com/a.png","A.png",false,"x||1"),
            DavItem("https://example.com/1.mp3","A.mp3",false,"x||1")
        )
        assertEquals(listOf("A.mp3","B.flac"),
            folderAudioQueue(items,AlbumSort.NAME,false).map { it.filename })
    }
}
