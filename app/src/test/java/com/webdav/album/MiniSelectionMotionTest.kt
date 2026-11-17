package com.webdav.album

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MiniSelectionMotionTest {
    @Test fun selectionActionBarStaysAtTheBottomWhileMiniRises() {
        // The new lift applies to the mini card, NOT the bottom action bar.
        assertEquals(48.dp, miniSelectionLift(
            selecting = true, miniAvailable = true, navigationInset = true))
        assertEquals(56.dp, miniSelectionLift(
            selecting = true, miniAvailable = true, navigationInset = false))
    }

    @Test fun normalMiniPlayerRemainsAtItsOriginalBottomPosition() {
        assertEquals(0.dp, miniSelectionLift(
            selecting = false, miniAvailable = true, navigationInset = true))
        assertEquals(0.dp, miniSelectionLift(
            selecting = false, miniAvailable = true, navigationInset = false))
    }

    @Test fun hidingMiniRemovesLiftRatherThanMovingTheActionBar() {
        assertEquals(0.dp, miniSelectionLift(
            selecting = true, miniAvailable = false, navigationInset = true))
        assertEquals(0.dp, miniSelectionLift(
            selecting = true, miniAvailable = false, navigationInset = false))
    }

    @Test fun stableDestinationIsAboveNavigationInBothStates() {
        assertEquals(16.dp, miniPlayerBottomClearance)
        assertEquals(16.dp, miniPlayerAnchorBottom(false, true, true))
        assertEquals(64.dp, miniPlayerAnchorBottom(true, true, true))
        assertEquals(72.dp, miniPlayerAnchorBottom(true, true, false))
        assertEquals(16.dp, miniPlayerAnchorBottom(true, false, true))
    }

    @Test fun fullscreenStateMustNotDetermineRaisedReturnAnchor() {
        // The anchor is based on selection and whether a music card is
        // AVAILABLE, not on AnimatedVisibility's current/target states.
        val selected = true
        val musicAvailable = true
        val duringMini = miniPlayerAnchorBottom(selected, musicAvailable, true)
        val duringFullscreen = miniPlayerAnchorBottom(selected, musicAvailable, true)
        val whileReturning = miniPlayerAnchorBottom(selected, musicAvailable, true)
        assertEquals(duringMini, duringFullscreen)
        assertEquals(duringFullscreen, whileReturning)
    }

    @Test fun existingMiniVisibilityConditionsStillApply() {
        assertTrue(shouldShowMiniPlayer(true,true,true,false,false))
        assertFalse(shouldShowMiniPlayer(true,true,true,true,false))
        assertFalse(shouldShowMiniPlayer(true,true,true,false,true))
        assertFalse(shouldShowMiniPlayer(true,true,false,false,false))
    }
}
