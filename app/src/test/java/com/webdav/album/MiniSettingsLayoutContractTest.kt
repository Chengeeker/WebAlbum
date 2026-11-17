package com.webdav.album

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards layout conventions that are easy to accidentally regress while editing Composables. */
class MiniSettingsLayoutContractTest {
    private fun source(file: String): String {
        val path = "src/main/java/com/webdav/album/$file"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate Android source: $file")
    }

    private fun segment(body: String, start: String, end: String): String =
        body.substringAfter(start).substringBefore(end)

    @Test fun playbackSectionPutsAllSwitchesBeforeAllChoices() {
        val s = segment(source("MusicSettings.kt"),
            "LucentSection(\"播放与交互\")", "LucentSection(\"歌词界面\")")
        val kinds = Regex("LucentToggle\\(|LucentChoiceRow\\(")
            .findAll(s).map { if (it.value.startsWith("LucentToggle")) "switch" else "choice" }.toList()
        assertEquals(listOf("switch", "switch", "switch", "choice", "choice"), kinds)
    }

    @Test fun lyricSectionPutsAllSwitchesBeforeAllChoices() {
        val s = segment(source("MusicSettings.kt"),
            "LucentSection(\"歌词界面\")", "LucentSection(\"后台通知\")")
        val kinds = Regex("LucentToggle\\(|LucentChoiceRow\\(")
            .findAll(s).map { if (it.value.startsWith("LucentToggle")) "switch" else "choice" }.toList()
        assertEquals(listOf("switch", "switch", "switch", "choice", "choice", "choice"), kinds)
    }

    @Test fun miniPlayerDoesNotComposeAnyProgressRail() {
        val s = segment(source("GreenMusicPlayerUi.kt"),
            "internal fun GreenMusicMiniPlayer(", "private fun GreenMusicLyricPanel(")
        assertFalse(s.contains("LinearProgressIndicator("))
        assertFalse(s.contains("MusicWavyProgressTrack("))
        assertFalse(s.contains("playbackProgress"))
        assertTrue(s.contains(".height(miniPlayerCompactRowHeight)"))
    }

    @Test fun selectionActionBarFadesOnlyAndNeverSlides() {
        val s = segment(source("MainActivity.kt"),
            "Bottom action bar is stationary", "ReviewBottomBar(-1, floating = true")
        assertTrue(s.contains("fadeIn("))
        assertTrue(s.contains("fadeOut("))
        assertFalse(s.contains("slideInVertically("))
        assertFalse(s.contains("slideOutVertically("))
    }

    @Test fun miniRisesFirstAndLowersOnlyAfterActionBarDisappears() {
        assertTrue(MiniSelectionMotionTiming.MINI_RISE_MS >
            MiniSelectionMotionTiming.BAR_FADE_IN_DELAY_MS)
        assertTrue(MiniSelectionMotionTiming.BAR_FADE_IN_MS <= 120)
        assertTrue(MiniSelectionMotionTiming.BAR_FADE_OUT_MS <= 100)
        assertTrue(MiniSelectionMotionTiming.MINI_LOWER_DELAY_MS >=
            MiniSelectionMotionTiming.BAR_FADE_OUT_MS)
        assertEquals(0, MiniSelectionMotionTiming.BAR_FADE_IN_DELAY_MS -
            MiniSelectionMotionTiming.BAR_FADE_IN_DELAY_MS)
    }
}
