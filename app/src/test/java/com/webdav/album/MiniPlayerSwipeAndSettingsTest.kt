package com.webdav.album

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerSwipeAndSettingsTest {
    @Test fun downSwipeDismissesWhenItDominatesHorizontalMotion() {
        assertTrue(isMiniPlayerDismissSwipe(4f, 58f, 56f))
        assertTrue(isMiniPlayerDismissSwipe(-8f, 120f, 56f))
    }

    @Test fun horizontalSkipOrUpSwipeNeverDismissesTheMiniBar() {
        assertFalse(isMiniPlayerDismissSwipe(140f, 60f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(-180f, 12f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(0f, -110f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(12f, 35f, 56f))
        assertFalse(isMiniPlayerDismissSwipe(60f, 60f, 56f))
    }

    @Test fun shortDownSwipeWorksNearBottomNavigationBar() {
        // The physical space below the bar is too short for the former 56dp.
        assertTrue(isMiniPlayerDismissSwipe(8f, 29f, 28f))
        assertTrue(isMiniPlayerDismissSwipe(5f, 17f, 28f,
            velocityY = 1100f, minimumFling = 650f))
        assertFalse(isMiniPlayerDismissSwipe(30f, 29f, 28f))
        assertFalse(isMiniPlayerDismissSwipe(5f, 12f, 28f,
            velocityY = 1100f, minimumFling = 650f))
        assertFalse(isMiniPlayerDismissSwipe(2f, -60f, 28f))
    }

    @Test fun smallScreenDismissAcceptsShortIntentionalDownSwipe() {
        assertTrue(isMiniPlayerDismissSwipe(3f, 15f, 14f))
        assertTrue(isMiniPlayerDismissSwipe(2f, 9f, 14f,
            velocityY = 900f, minimumFling = 550f))
        assertFalse(isMiniPlayerDismissSwipe(3f, 4f, 14f,
            velocityY = 900f, minimumFling = 550f))
        assertFalse(isMiniPlayerDismissSwipe(25f, 15f, 14f))
    }

    @Test fun dragGestureIsAnchoredOutsideMovingSurfaceAndNeverOscillatesAlpha() {
        val mini = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText().substringAfter("internal fun GreenMusicMiniPlayer(")
            .substringBefore("/** Glyph boxes")
        assertTrue(mini.contains("val dismissThreshold = 14.dp.toPx()"))
        assertTrue(mini.contains(".then(miniBarSwipe)"))
        assertTrue(mini.contains("translationY = dismissDrag"))
        assertTrue(mini.contains("dismissDrag = (dy.coerceAtLeast(0f)"))
        assertFalse(mini.contains("modifier = miniBarSwipe"))
        assertFalse(mini.contains("alpha = (1f - offsetY"))
        assertFalse(mini.contains("targetValue = if (isDismissing)"))
        // A successful swipe should not schedule a spring-back while the
        // parent AnimatedVisibility is already exiting.
        assertTrue(mini.contains("if (!completed && verticalClaimed"))
    }

    @Test fun swipedAlbumCoverRemainsUnfadedAfterNewTrackComposes() {
        val file = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        val mini = file.substringAfter("internal fun GreenMusicMiniPlayer(")
            .substringBefore("/** Glyph boxes")
        assertTrue(mini.contains("fadeArtwork = false"))
        assertTrue(file.contains("if (listArtworkReady || !fadeArtwork)"))
        assertTrue(file.contains("entry.first.contentEquals(bytes)"))
        assertTrue(file.contains("SharedMusicCoverCache.get(track.scopeId)"))
    }

    @Test fun diagonalStartDoesNotPermanentlyBlockDownSwipe() {
        assertEquals(0, miniPlayerSwipeIntent(17f, 16f, 14f))
        assertEquals(1, miniPlayerSwipeIntent(17f, 25f, 14f))
        assertEquals(-1, miniPlayerSwipeIntent(25f, 11f, 14f))
        assertEquals(0, miniPlayerSwipeIntent(4f, 7f, 14f))
        assertEquals(1, miniPlayerSwipeIntent(7f, 22f, 14f))
    }

    @Test fun swipeCommitWaitsForNewTrackInsteadOfFlashingOldSong() {
        val source = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicSwipeFrame.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicSwipeFrame.kt"),
        ).first { it.isFile }.readText()
        val committed = source.substringAfter("if (commit) {")
            .substringBefore("} else {")
        assertTrue(committed.contains("awaitingTrack = true"))
        assertFalse(committed.contains("progress.snapTo(0f)"))
        assertTrue(source.contains("if (key != previousKey) 0f"))
        val cover = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
        assertTrue(cover.contains("SharedMusicCoverCache.get(track.scopeId, artwork)"))
        assertTrue(cover.contains("remember(track.scopeId, artwork, listArtworkReady)"))
    }

    @Test fun hiddenMiniPlayerFreesScreenButActiveTrackCanStillExpand() {
        assertTrue(shouldShowMiniPlayer(true, true, true, false, false))
        assertFalse(shouldShowMiniPlayer(true, true, true, false, true))
        assertFalse(shouldShowMiniPlayer(true, true, true, true, false))
        assertFalse(shouldShowMiniPlayer(false, true, true, false, false))
        assertFalse(shouldShowMiniPlayer(true, false, true, false, false))
        assertFalse(shouldShowMiniPlayer(true, true, false, false, false))
        // The main-screen restore action clears dismissed and opens fullscreen.
        assertTrue(shouldShowMiniPlayer(true, true, true, false, false))
    }

    @Test fun wavySliderThumbIsHalfTheStraightHeight() {
        assertEquals(44.dp, musicWaveThumbHeight(false))
        assertEquals(22.dp, musicWaveThumbHeight(true))
        assertEquals(musicWaveThumbHeight(false).value / 2f, musicWaveThumbHeight(true).value)
    }
}
