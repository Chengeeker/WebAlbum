package com.webdav.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerTransitionTest {
    @Test fun capsuleFillIsTheSoleOutlineOwnerDuringOpeningAndClosing() {
        // Opening: the requested full player owns the visible outline immediately.
        assertTrue(capsuleFillOwnsBoundary(
            fullRequested = true, fullCurrent = false, fullIdle = true))
        // Fully open: the mini-player is not allowed to draw a second rim.
        assertTrue(capsuleFillOwnsBoundary(
            fullRequested = true, fullCurrent = true, fullIdle = true))
        // Closing: fullscreen is still drawing the shrinking outline.
        assertTrue(capsuleFillOwnsBoundary(
            fullRequested = false, fullCurrent = true, fullIdle = false))
        // The animated visibility state may have switched to false while
        // the exit is still running: its rim still owns the frame.
        assertTrue(capsuleFillOwnsBoundary(
            fullRequested = false, fullCurrent = false, fullIdle = false))
        // Only once the morph has ENDED may Review glass restore its own rim.
        assertFalse(capsuleFillOwnsBoundary(
            fullRequested = false, fullCurrent = false, fullIdle = true))
    }

    @Test fun miniKeepsSharedContentButDoesNotDrawSecondGlassRim() {
        val transition = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
        ).first { it.isFile }.readText()
        val mini = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerUi.kt"),
        ).first { it.isFile }.readText()
            .substringAfter("internal fun GreenMusicMiniPlayer(")
            .substringBefore("/** Glyph boxes")
        assertTrue(transition.contains("renderGlassBackground = !fullTarget"))
        assertTrue(transition.contains("rememberSharedContentState(key = \"music-cover\")"))
        assertTrue(transition.contains("rememberSharedContentState(key = \"music-title\")"))
        assertTrue(mini.contains("val glassDecoration = if (renderGlassBackground)"))
        assertTrue(mini.contains("Modifier.miniPlayerGlassBackground(glassTokens)"))
        assertTrue(mini.contains("}.then(glassDecoration)"))
    }

    @Test fun cornerKeepsRoundShapeAfterTopHasAlreadyExpanded() {
        // Top geometry is allowed to fill the screen first. Slower horizontal
        // expansion and corner unfold keep the leading rounded edge visible.
        val frame = capsuleFillGeometry(
            width = 400f, height = 800f, density = 1f,
            navBottomPx = 24f, liftPx = 0f,
            head = 1f, foot = .5f, sides = .25f, corners = .25f,
        )
        assertEquals(0f, frame.top, .01f)
        assertEquals(9f, frame.left, .01f)
        assertEquals(18f, frame.radius, .01f)
        assertTrue(frame.bottom < 800f)
    }

    @Test fun glassEmergesContinuouslyBeforeCapsuleFinishesShrinking() {
        assertEquals(0f, capsuleGlassStrength(1f), .0001f)
        assertEquals(.25f, capsuleGlassStrength(.75f), .0001f)
        assertEquals(.5f, capsuleGlassStrength(.5f), .0001f)
        assertEquals(1f, capsuleGlassStrength(0f), .0001f)
        assertEquals(1f, capsuleGlassStrength(-2f), .0001f)
        assertEquals(0f, capsuleGlassStrength(2f), .0001f)
    }

    @Test fun fullscreenCapsuleUsesOnlyCheapDrawPhaseWorkNotResizedGpuLens() {
        val source = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
        ).first { it.isFile }.readText()
        assertTrue(source.contains("label = \"player-capsule-horizontal-fill\""))
        assertTrue(source.contains("label = \"player-capsule-visible-corner-unfold\""))
        assertTrue(source.contains("corners = fillCorners"))
        assertTrue(source.contains(".drawWithContent {"))
        assertTrue(source.contains("clipPath(outline)"))
        assertTrue(source.contains("lerp("))
        assertTrue(source.contains("renderGlassBackground = !fullTarget"))
        assertFalse("Never rebuild a variable-size GPU lens on the UI thread",
            source.contains("reviewMorphingPlayerBackdrop("))
        assertFalse(source.contains("lensBounds"))
        assertFalse(source.contains(".size(lensWidth, lensHeight)"))
    }

    @Test fun aboutPageOnlyPresentsLucentUiInDesignAndTechnology() {
        val about = listOf(
            java.io.File("src/main/java/com/webdav/album/AboutWebAlbumPage.kt"),
            java.io.File("app/src/main/java/com/webdav/album/AboutWebAlbumPage.kt"),
        ).first { it.isFile }.readText()
        val section = about.substringAfter("LucentSection(\"界面与技术\")")
        assertTrue(section.contains("title = \"LucentUI\""))
        assertFalse(section.contains("GreenMusic 风格"))
        assertFalse(section.contains("LucentRow(\"音频播放\""))
    }

    @Test fun glassLensRemainsOnlyOnTheStableMiniPlayer() {
        val transition = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
        ).first { it.isFile }.readText()
        val mini = listOf(
            java.io.File("src/main/java/com/webdav/album/ReviewMiniPlayerBackdrop.kt"),
            java.io.File("app/src/main/java/com/webdav/album/ReviewMiniPlayerBackdrop.kt"),
        ).first { it.isFile }.readText()
        assertFalse(transition.contains("val movingBoundaryShape = GenericShape"))
        assertFalse(transition.contains("val lensShape = RoundedCornerShape(lensRadius)"))
        assertTrue(mini.contains("shape = { RoundedCornerShape(24.dp) }"))
        assertTrue(mini.contains("lens(24.dp.toPx(), 24.dp.toPx())"))
    }

    @Test fun capsuleFillStartsAtExactMiniPlayerPosition() {
        // A 400x800dp window, 24dp navigation inset, and a 58dp mini bar.
        val bounds = capsuleFillGeometry(
            width = 400f, height = 800f, density = 1f,
            navBottomPx = 24f, liftPx = 0f,
            head = 0f, foot = 0f, sides = 0f,
        )
        assertEquals(12f, bounds.left, .01f)
        assertEquals(388f, bounds.right, .01f)
        assertEquals(696f, bounds.top, .01f)
        assertEquals(754f, bounds.bottom, .01f)
        assertEquals(24f, bounds.radius, .01f)
    }

    @Test fun capsuleFillReachesFullScreenWithoutShapeJump() {
        val full = capsuleFillGeometry(400f, 800f, 1f, 24f, 0f, 1f, 1f, 1f)
        assertEquals(0f, full.left, .01f)
        assertEquals(0f, full.top, .01f)
        assertEquals(400f, full.right, .01f)
        assertEquals(800f, full.bottom, .01f)
        assertEquals(0f, full.radius, .01f)
    }

    @Test fun capsuleHeadHasLongerDistanceThanTheFootAndHonorsSelectionLift() {
        val start = capsuleFillGeometry(400f, 800f, 1f, 24f, 48f, 0f, 0f, 0f)
        val partial = capsuleFillGeometry(400f, 800f, 1f, 24f, 48f, .5f, .5f, .5f)
        assertEquals(648f, start.top, .01f)
        assertEquals(706f, start.bottom, .01f)
        assertTrue(start.top - partial.top > partial.bottom - start.bottom)
        assertEquals(6f, partial.left, .01f)
        assertEquals(12f, partial.radius, .01f)
        assertTrue(partial.top > 0f)
        assertTrue(partial.bottom < 800f)
    }

    @Test fun capsuleMorphUsesClipInsteadOfIndependentFullscreenSlide() {
        val src = listOf(
            java.io.File("src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
            java.io.File("app/src/main/java/com/webdav/album/GreenMusicPlayerTransition.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("player-capsule-fast-top-edge"))
        assertTrue(src.contains("player-capsule-short-bottom-edge"))
        assertTrue(src.contains("clipPath(outline)"))
        assertTrue(src.contains("capsuleFillGeometry("))
        val full = src.substringAfter("val enterFull =")
            .substringBefore("val reopen by")
        assertFalse(full.contains("slideInVertically("))
        assertFalse(full.contains("slideOutVertically("))
    }

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
