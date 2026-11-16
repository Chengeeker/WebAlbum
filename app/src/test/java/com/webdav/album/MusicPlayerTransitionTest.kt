package com.webdav.album

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerTransitionTest {
    @Test fun miniCanReopenImmediatelyWhileFullscreenStillExiting() {
        // The fullscreen hit-test layer is still mounted during its 300ms exit.
        // Our transparent topmost destination hit region must work immediately.
        assertTrue(canInterruptMusicCollapse(
            available = true, hasTrack = true, miniEnabled = true,
            expanded = false, fullscreenStillPresent = true,
        ))
    }

    @Test fun destinationInterceptorStopsWhenTransitionIsComplete() {
        // The mini-player's own clickable takes over after exit completes.
        assertFalse(canInterruptMusicCollapse(
            available = true, hasTrack = true, miniEnabled = true,
            expanded = false, fullscreenStillPresent = false,
        ))
    }

    @Test fun doNotStealTouchesFromFullscreenOrUnavailablePlayback() {
        assertFalse(canInterruptMusicCollapse(true, true, true, true, true))
        assertFalse(canInterruptMusicCollapse(false, true, true, false, true))
        assertFalse(canInterruptMusicCollapse(true, false, true, false, true))
        assertFalse(canInterruptMusicCollapse(true, true, false, false, true))
    }
}
