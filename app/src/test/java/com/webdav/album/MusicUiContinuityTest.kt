package com.webdav.album

import org.junit.Assert.*
import org.junit.Test

class MusicUiContinuityTest {
    @Test fun albumAndLyricsShareOneAbsoluteCoordinateSystem() {
        assertEquals(MusicPagesOffset(0f, 1080f), musicPagesOffset(0f, 1080f))
        assertEquals(MusicPagesOffset(-450f, 630f), musicPagesOffset(-450f, 1080f))
        assertEquals(MusicPagesOffset(-1080f, 0f), musicPagesOffset(-1080f, 1080f))
    }

    @Test fun flippingTargetHalfwayNeverResetsOrBouncesEitherPage() {
        val intermediateX = -560f
        val before = musicPagesOffset(intermediateX, 1080f)
        // The current pixel location is completely independent of which
        // endpoint is requested, so retargeting does not cause a jump.
        val whenReversed = musicPagesOffset(intermediateX, 1080f)
        assertEquals(before, whenReversed)
        assertEquals(0f, musicPageTarget(false, 1080f))
        assertEquals(-1080f, musicPageTarget(true, 1080f))
    }

    @Test fun albumLyricsPageSeparationIsConstant() {
        for (x in -1080..0 step 27) {
            val pair = musicPagesOffset(x.toFloat(), 1080f)
            assertEquals(1080f, pair.lyrics - pair.album)
        }
    }

    @Test fun progressFractionIsClampedAndHandlesBrokenMetadata() {
        assertEquals(0f, musicSliderProgress(0f, 0f, 500f))
        assertEquals(.5f, musicSliderProgress(250f, 0f, 500f))
        assertEquals(1f, musicSliderProgress(900f, 0f, 500f))
        assertEquals(0f, musicSliderProgress(99f, 100f, 100f))
        assertEquals(0f, musicSliderProgress(Float.NaN, 0f, 100f))
    }

    @Test fun waveformAndStraightTrackAreDisjointAtEveryProgress() {
        for (i in 0..100) {
            val s = musicWaveSegments(i / 100f, 400f, 4f)
            assertTrue("no inactive straight line may go beneath the played wave",
                s.inactiveStart >= s.activeEnd)
            assertTrue(s.activeEnd >= 0f && s.activeEnd <= 400f)
            assertTrue(s.inactiveEnd <= 400f && s.inactiveStart <= s.inactiveEnd)
            if (i in 1..98) assertTrue(s.inactiveStart > s.activeEnd)
        }
    }

    @Test fun waveformEndpointsRenderOnlyOneStyle() {
        assertEquals(MusicWaveSegments(0f, 0f, 320f), musicWaveSegments(0f, 320f, 4f))
        assertEquals(MusicWaveSegments(160f, 164f, 320f), musicWaveSegments(.5f, 320f, 4f))
        assertEquals(MusicWaveSegments(320f, 320f, 320f), musicWaveSegments(1f, 320f, 4f))
        assertEquals(MusicWaveSegments(0f, 0f, 320f), musicWaveSegments(Float.NaN, 320f, 4f))
    }

    @Test fun waveOffsetsStayWithinAmplitude() {
        for (x in 0..320) {
            val delta = musicWaveOffset(x.toFloat(), 22f, 5f, .25f)
            assertTrue(delta in -5.001f..5.001f)
        }
    }

    @Test fun galleryGridIndexAccountsForFolderRowsAndSeparator() {
        assertEquals(0, galleryGridItemIndex(0, 80, 0))
        assertEquals(79, galleryGridItemIndex(0, 80, 79))
        assertEquals(4, galleryGridItemIndex(3, 80, 0))
        assertEquals(44, galleryGridItemIndex(3, 80, 40))
        assertNull(galleryGridItemIndex(3, 80, 80))
        assertNull(galleryGridItemIndex(3, 0, 0))
        assertNull(galleryGridItemIndex(-1, 10, 0))
    }
}
