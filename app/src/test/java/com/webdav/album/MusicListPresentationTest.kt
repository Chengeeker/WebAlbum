package com.webdav.album

import org.junit.Assert.*
import org.junit.Test

class MusicListPresentationTest {
    @Test fun filenameOnlyIsSplitIntoRealTitleAndArtistInsteadOfFileFormat() {
        val info = fallbackMusicLabels("Just Kiddin、 Camden Cox - Stay The Night.mp3")
        assertEquals("Stay The Night", info.title)
        assertEquals("Just Kiddin、 Camden Cox", info.artist)
        assertFalse(info.artist.contains("WebDAV"))
        assertFalse(info.artist.contains("MP3"))
    }

    @Test fun taggedTitleArtistAndCoverTakePriorityOverFilenameGuess() {
        val track = MusicTrack(
            "https://example.test/music/a.mp3", "v", "random-download-813.mp3")
        val art = byteArrayOf(1, 2, 3)
        val final = musicListTrack(track,
            EmbeddedMusicTags(title = "傻女 (Album Version)",
                artist = "陈慧娴", album = "专辑名称", cover = art))
        assertEquals("傻女 (Album Version)", final.title)
        assertEquals("陈慧娴", final.artist)
        assertEquals("专辑名称", final.album)
        assertArrayEquals(art, final.artwork)
    }

    @Test fun missingTagsNeverMisrepresentAudioFormatAsAnArtist() {
        val track = MusicTrack("https://example.test/music/track.flac", "v", "track.flac")
        val final = musicListTrack(track, null)
        assertEquals("track", final.title)
        assertEquals("未知歌手", final.artist)
        assertEquals(MusicListLabels("track", "未知歌手"), fallbackMusicLabels("track.flac"))
    }

    @Test fun missingArtistTagFallsBackToFilenameWhenAvailable() {
        val track = MusicTrack("https://example.test/music/a.mp3", "v",
            "陈慧娴 - 傻女 (Album Version).mp3")
        val final = musicListTrack(track, EmbeddedMusicTags(title = "傻女 (Album Version)"))
        assertEquals("傻女 (Album Version)", final.title)
        assertEquals("陈慧娴", final.artist)
    }

    @Test fun filenamesWithoutExtensionsOrSeparatorsRemainReadable() {
        assertEquals(MusicListLabels("歌曲", "未知歌手"), fallbackMusicLabels("歌曲"))
        assertEquals(MusicListLabels("未知歌曲", "未知歌手"), fallbackMusicLabels(""))
    }
}
