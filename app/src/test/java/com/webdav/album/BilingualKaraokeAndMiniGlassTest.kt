package com.webdav.album

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.compositeOver
import dev.chengeeker.lucent.contrast
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BilingualKaraokeAndMiniGlassTest {
    @Test fun duplicateTimestampBilingualLrcIsOneHighlightableLine() {
        val result = parseMusicLyrics(
            "[00:10.00]我会在这里\n[00:10.00]I will be here\n" +
                "[00:13.25]等待着你\n[00:13.25]Waiting for you"
        )
        assertEquals(2, result.lines.size)
        assertEquals(listOf(10000L, 13250L), result.lines.map { it.timeMs })
        assertEquals("我会在这里", result.lines[0].text)
        assertEquals("I will be here", result.lines[0].translation)
        assertEquals("Waiting for you", currentMusicLyricLine(result, 13700)?.translation)
    }

    @Test fun separateOriginalAndTranslationTracksStayInSync() {
        val result = parseMusicLyrics(
            "[00:06.50]你好吗", "[00:06.50]How are you?"
        )
        assertEquals(1, result.lines.size)
        assertEquals("How are you?", result.lines.single().translation)
    }

    @Test fun id3EmbeddedBilingualContentParsesAfterTagReader() {
        val lyric = "[00:03.20]你好\n[00:03.20]Hello"
        // The parser is shared by sidecar LRC and embedded USLT/TXXX/FLAC lyric fields.
        val parsed = parseMusicLyrics(lyric)
        assertEquals(3200L, parsed.lines.single().timeMs)
        assertEquals("你好", parsed.lines.single().text)
        assertEquals("Hello", parsed.lines.single().translation)
    }

    @Test fun enhancedLrcWordTimingRetainsTranslation() {
        val lyrics = parseMusicLyrics(
            "[00:10.00]<00:10.00>你<00:10.30>好<00:10.60>！\n" +
                "[00:10.00]Hello!"
        )
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines.single()
        assertEquals("你好！", line.text)
        assertEquals("Hello!", line.translation)
        assertEquals(listOf("你", "好", "！"), line.words.map { it.text })
        assertEquals(listOf(10000L, 10300L, 10600L), line.words.map { it.timeMs })
        val words = musicKaraokeWords(lyrics.lines, 0, 15000, MusicKaraokeMode.TIMED)
        assertEquals(3, words.size)
        assertEquals(10300L, words.first().endMs)
    }

    @Test fun karaokeCompatibilityModesMatchGreenMusicRules() {
        val normal = parseMusicLyrics("[00:01.00]你好\n[00:03.00]世界")
        assertTrue(musicKaraokeWords(normal.lines, 0, 5000, MusicKaraokeMode.OFF).isEmpty())
        assertTrue(musicKaraokeWords(normal.lines, 0, 5000, MusicKaraokeMode.TIMED).isEmpty())
        assertTrue(musicKaraokeWords(normal.lines, 0, 5000, MusicKaraokeMode.SONG).isEmpty())
        val guessed = musicKaraokeWords(normal.lines, 0, 5000, MusicKaraokeMode.ALL)
        assertEquals("你好", guessed.joinToString("") { it.text })
        assertEquals(1000L, guessed.first().timeMs)
        assertEquals(3000L, guessed.last().endMs)
    }

    @Test fun realWordTimingTakesPrecedenceAndSongModeExtendsOtherLines() {
        val lyrics = parseMusicLyrics(
            "[00:01.00]<00:01.00>你<00:01.30>好\n[00:03.00]世界"
        )
        assertEquals(2, musicKaraokeWords(lyrics.lines, 0, 6000, MusicKaraokeMode.SONG).size)
        assertEquals(2, musicKaraokeWords(lyrics.lines, 1, 6000, MusicKaraokeMode.SONG).size)
        assertTrue(musicKaraokeWords(lyrics.lines, 1, 6000, MusicKaraokeMode.TIMED).isEmpty())
        assertEquals(MusicKaraokeMode.ALL, MusicKaraokeMode.from("unexpected-legacy-value"))
    }

    @Test fun plainLyricsAndRepeatedStampsRemainCompatible() {
        val plain = parseMusicLyrics("Hello world\n再见")
        assertTrue(plain.lines.isEmpty())
        assertTrue(plain.plain.contains("再见"))
        val repeated = parseMusicLyrics("[00:02.00][00:04.00]重复")
        assertEquals(listOf(2000L, 4000L), repeated.lines.map { it.timeMs })
    }

    @Test fun karaokeHighlightHandlesReversedGlyphBoundsWithoutCrashing() {
        // Reproduces the OPPO Android 17 crash from the 1.3.12 logcat:
        // Cannot coerce value to an empty range (maximum -0.49609375).
        assertEquals(0.49609375f, karaokeGlyphWidth(-0.49609375f), 0f)
        assertEquals(0.49609375f, karaokeGlyphFill(1f, -0.49609375f), 0f)
        assertEquals(0f, karaokeGlyphFill(-1f, -0.49609375f), 0f)
        assertEquals(0f, karaokeGlyphFill(Float.NaN, 2f), 0f)
        assertEquals(0f, karaokeGlyphFill(1f, Float.NaN), 0f)
    }

    @Test fun cumulativeLyricScrollCentersCurrentLineOnBothSides() {
        // This regression MUST measure the current lyric's actual viewport
        // coordinates, not merely assert that a formula is symmetric.
        val height = 350
        val rowHeights = listOf(68, 84, 102, 56, 110, 74, 61)
        for (index in rowHeights.indices) {
            val layout = lyricScrollGeometry(height, rowHeights, 14, index)
            val activeTop = layout.topPaddingPx + layout.startsPx[index] -
                layout.targetPx
            val physicalCenter = activeTop + rowHeights[index] / 2f
            assertEquals(
                "Lyric $index must be visibly centered, not at the bottom",
                height / 2f, physicalCenter, 1f,
            )
            assertTrue("Scroll targets must never be negative", layout.targetPx >= 0f)
        }
        assertEquals(0f, lyricScrollGeometry(350, rowHeights, 14, 0).targetPx, .01f)
        val last = lyricScrollGeometry(350, rowHeights, 14, rowHeights.lastIndex)
        assertTrue(last.targetPx > 0f)
        assertEquals(last.startsPx.last() + rowHeights.last().toFloat() / 2f +
            last.topPaddingPx - 175f, last.targetPx, 1f)
    }

    @Test fun variableBilingualHeightsAndSmallScreensDoNotShiftCurrentLyricDown() {
        for (viewport in listOf(245, 310, 410, 620)) {
            val heights = listOf(34, 135, 77, 115, 48, 91, 36)
            for (index in heights.indices) {
                val layout = lyricScrollGeometry(viewport, heights, 14, index)
                val center = layout.topPaddingPx + layout.startsPx[index] -
                    layout.targetPx + heights[index] / 2f
                assertEquals(viewport / 2f, center, 1.1f)
            }
        }
        val one = lyricScrollGeometry(200, listOf(60), 14, 0)
        assertEquals(0f, one.targetPx, .01f)
    }

    @Test fun lyricsUseMeasuredRowsAndPositiveAbsoluteScrollInsteadOfNegativeOffset() {
        val file = listOf(
            File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        val panel = file.substringAfter("private fun GreenMusicLyricPanel(")
            .substringBefore("private fun MusicPlayerButtons(")
        assertTrue(panel.contains("lyricScrollGeometry("))
        assertTrue(panel.contains("observedRowHeights[i] = size.height"))
        assertTrue(panel.contains("measurer.measure("))
        assertTrue(panel.contains("scrollState.animateScrollBy("))
        assertFalse(panel.contains("scrollOffset = -"))
        assertFalse(panel.contains("112.dp"))
        assertFalse(panel.contains("196.dp"))
        assertTrue(panel.contains("collectIsDraggedAsState"))
    }

    @Test fun adjacentLyricChangesKeepAnimatedScrollingRegardlessOfStaggerSetting() {
        assertEquals(300, lyricAutoScrollDurationMs(2, 3, true, true))
        assertEquals(260, lyricAutoScrollDurationMs(2, 3, true, false))
        assertEquals(260, lyricAutoScrollDurationMs(3, 2, true, false))
        assertEquals(300, lyricAutoScrollDurationMs(0, 4, true, true))
        assertEquals(0, lyricAutoScrollDurationMs(0, 5, true, true))
        assertEquals(0, lyricAutoScrollDurationMs(3, 3, true, true))
        assertEquals(0, lyricAutoScrollDurationMs(3, 4, false, true))
    }

    @Test fun lyricHeightRemeasureCannotCancelTheActiveScrollAnimation() {
        val file = listOf(
            File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        val panel = file.substringAfter("private fun GreenMusicLyricPanel(")
            .substringBefore("private fun MusicPlayerButtons(")
        assertFalse("Row-height remeasurement must not cancel scrolling",
            panel.contains("LaunchedEffect(lyric.lines, current, rowHeights"))
        assertFalse("Dragging must not accidentally re-key natural song scrolling",
            panel.contains("LaunchedEffect(lyric.lines, current, rowHeights, viewportPx, dragged)"))
        assertTrue(panel.contains("val latestGeometry by rememberUpdatedState(geometry)"))
        assertTrue(panel.contains("lyricAutoScrollDurationMs("))
        assertTrue(panel.contains("scrollState.animateScrollBy("))
        assertTrue(panel.contains("durationMillis = durationMs"))
        assertTrue(panel.contains("withFrameNanos"))
        assertTrue(panel.contains("top = with(density) { geometry.topPaddingPx.toDp() }"))
        assertTrue(panel.contains("bottom = with(density) { geometry.bottomPaddingPx.toDp() }"))
    }

    @Test fun coldFirstOpenDoesNotComposeOffscreenLyricsOrDecodePaletteOnMainThread() {
        val ui = listOf(
            File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        val switcher = listOf(
            File("src/main/java/com/webdav/album/GreenMusicAlbumLyricsSwitcher.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicAlbumLyricsSwitcher.kt"),
        ).first { it.isFile }.readText()
        val transition = listOf(
            File("src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
        ).first { it.isFile }.readText()
        assertTrue(switcher.contains("if (lyricsVisible || dragging || x < -1f)"))
        assertTrue(ui.contains("MusicArtworkPaletteCache.cached("))
        assertTrue(ui.contains("MusicArtworkPaletteCache.getOrPrepare("))
        assertTrue(transition.contains("MusicArtworkPaletteCache.getOrPrepare("))
    }

    @Test fun miniPlayerGlassMatchesReviewNavigationOpticalTints() {
        for (dark in listOf(false, true)) {
            for (translucent in listOf(false, true)) {
                val tokens = miniPlayerGlassTokens(true, translucent, dark,
                    Color.Gray, Color.White, Color.White)
                assertEquals(if (translucent) MiniPlayerGlassMode.CLEAR
                    else MiniPlayerGlassMode.FROSTED, tokens.mode)
                val expected = if (!translucent) .75f else if (dark) .4f else .42f
                assertEquals("Review glass body alpha", expected, tokens.fill.alpha, .002f)
                assertEquals(Color.Transparent, tokens.stroke)
            }
        }
        assertEquals(MiniPlayerGlassMode.SOLID, miniPlayerGlassTokens(
            false, false, true, Color.Gray, Color.White, Color.White).mode)
        val source = listOf(
            File("src/main/java/com/webdav/album/ReviewMiniPlayerBackdrop.kt"),
            File("app/src/main/java/com/webdav/album/ReviewMiniPlayerBackdrop.kt"),
        ).first { it.isFile }.readText()
        assertTrue("Uses Review's actual optical lens",
            source.contains("lens(24.dp.toPx(), 24.dp.toPx())"))
        assertTrue(source.contains("ReviewMiniSource"))
        assertTrue(source.contains("drawBackdrop("))
    }

    @Test fun miniQueueExpandsFromSameGlassSurfaceNotModalSheet() {
        val file = listOf(
            File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        val mini = file.substringAfter("internal fun GreenMusicMiniPlayer(")
            .substringBefore("/** Glyph boxes")
        assertTrue(mini.contains("InlineMiniMusicQueue("))
        assertTrue(mini.contains("expandVertically("))
        assertTrue(mini.contains("shrinkVertically("))
        assertTrue(mini.contains(".miniPlayerGlassBackground(glassTokens)"))
        assertTrue(mini.contains("BackHandler(enabled = showMiniQueue)"))
        assertFalse("Mini player must no longer open modal sheet",
            mini.contains("GreenMusicQueueSheet(music)"))
        assertTrue("Fullscreen queue is intentionally unchanged",
            file.contains("ModalBottomSheet("))
    }

    @Test fun inlineQueueStaysWithinHalfScreenIncludingMiniBar() {
        assertEquals(90.dp, inlineMiniQueueHeight(0, 800.dp))
        assertEquals(94.dp, inlineMiniQueueHeight(1, 800.dp))
        assertEquals(146.dp, inlineMiniQueueHeight(2, 800.dp))
        assertEquals(330.dp, inlineMiniQueueHeight(99, 800.dp))
        for (screen in listOf(360.dp, 480.dp, 720.dp, 900.dp)) {
            for (songs in listOf(0, 1, 3, 12, 1000)) {
                val panel = inlineMiniQueueHeight(songs, screen)
                assertTrue("Panel must keep fixed header visible", panel >= miniQueueHeaderHeight)
                assertTrue("Entire mini glass must be below 50% of screen",
                    panel + miniPlayerCompactRowHeight <= screen * .5f)
            }
        }
        val queue = listOf(
            File("src/main/java/com/webdav/album/InlineMiniMusicQueue.kt"),
            File("app/src/main/java/com/webdav/album/InlineMiniMusicQueue.kt"),
        ).first { it.isFile }.readText()
        assertTrue(queue.indexOf("Text(\"播放队列\"") < queue.indexOf("LazyColumn("))
        assertTrue(queue.contains("Modifier.fillMaxWidth().weight(1f)"))
        assertTrue(queue.contains("onClose"))
    }

    @Test fun aboutSourceLinkIsTheRealWebAlbumRepository() {
        assertEquals("https://github.com/Chengeeker/WebAlbum", WEBALBUM_SOURCE_URL)
    }
}
