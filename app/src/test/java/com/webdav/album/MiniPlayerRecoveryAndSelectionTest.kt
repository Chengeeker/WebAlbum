package com.webdav.album

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerRecoveryAndSelectionTest {
    @Test fun defaultManualRecoveryNeverRestoresOnAudioEvents() {
        val mode = MiniPlayerRecovery.from(null)
        assertEquals(MiniPlayerRecovery.MANUAL, mode)
        assertFalse(shouldRestoreMini(mode, true, true, true))
        assertFalse(shouldRestoreMini(mode, true, false, true))
        assertFalse(shouldRestoreMini(mode, true, true, false))
    }

    @Test fun changeTrackPolicyRestoresOnlyOnTrackTransition() {
        assertTrue(shouldRestoreMini(MiniPlayerRecovery.TRACK, true, true, false))
        assertFalse(shouldRestoreMini(MiniPlayerRecovery.TRACK, true, false, true))
        assertFalse(shouldRestoreMini(MiniPlayerRecovery.TRACK, false, true, true))
    }

    @Test fun resumePolicyRestoresOnEitherNewTrackOrResumedPlayback() {
        assertTrue(shouldRestoreMini(MiniPlayerRecovery.RESUME, true, true, false))
        assertTrue(shouldRestoreMini(MiniPlayerRecovery.RESUME, true, false, true))
        assertFalse(shouldRestoreMini(MiniPlayerRecovery.RESUME, true, false, false))
        assertFalse(shouldRestoreMini(MiniPlayerRecovery.RESUME, false, true, true))
    }

    @Test fun unknownSavedModeFallsBackSafelyToManual() {
        assertEquals(MiniPlayerRecovery.MANUAL, MiniPlayerRecovery.from("old"))
        assertEquals(MiniPlayerRecovery.TRACK, MiniPlayerRecovery.from("track"))
        assertEquals(MiniPlayerRecovery.RESUME, MiniPlayerRecovery.from("resume"))
    }

    @Test fun floatingActionBarLiftsOnlyWhileMiniPlayerIsActuallyVisible() {
        assertEquals(0.dp, selectionBarClearance(false))
        assertEquals(86.dp, selectionBarClearance(true))
        assertEquals(58.dp, miniPlayerCompactRowHeight)
    }

    @Test fun fastShortDownFlingAlsoDismissesButHorizontalFlingDoesNot() {
        assertTrue(isMiniPlayerDismissSwipe(3f, 31f, 56f, velocityY = 1500f, minimumFling = 850f))
        assertFalse(isMiniPlayerDismissSwipe(3f, 10f, 56f, velocityY = 1500f, minimumFling = 850f))
        assertFalse(isMiniPlayerDismissSwipe(40f, 31f, 56f, velocityY = 2000f, minimumFling = 850f))
        assertFalse(isMiniPlayerDismissSwipe(3f, 31f, 56f, velocityY = 400f, minimumFling = 850f))
    }

    @Test fun gestureThresholdSeparatesDownSwipesFromSongSkipping() {
        assertTrue(isMiniPlayerDismissSwipe(8f, 68f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(85f, 68f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(8f, 40f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(0f, -68f, 56f))
    }
}
