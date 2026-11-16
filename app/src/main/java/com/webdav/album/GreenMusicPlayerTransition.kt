package com.webdav.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chengeeker.lucent.LocalLucentStyle
import dev.chengeeker.lucent.review.hapticClick

/**
 * The fullscreen outgoing layer is still on top of the mini player throughout
 * its exit. Offer a topmost hit target over the mini-player's destination while
 * that layer exists; DOWN reverses the shared element transition immediately.
 */
internal fun canInterruptMusicCollapse(
    available: Boolean,
    hasTrack: Boolean,
    miniEnabled: Boolean,
    expanded: Boolean,
    fullscreenStillPresent: Boolean,
): Boolean = available && hasTrack && miniEnabled && !expanded && fullscreenStillPresent

/**
 * Keep the mini/full endpoints composed through both directions so
 * SharedTransitionLayout can retarget moving cover/title from their CURRENT
 * coordinates, without completing the outgoing flight or restarting at 0.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun GreenMusicPlayerTransition(
    music: WebAlbumMusicPlayback,
    prefs: AlbumPreferences,
    expanded: Boolean,
    available: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by music.state.collectAsState()
    val hasTrack = state.current != null
    val motion = LocalLucentStyle.current.motion
    val miniTarget = available && hasTrack && prefs.musicMiniPlayer && !expanded
    val fullTarget = available && hasTrack && expanded
    val fullVisibility = remember { MutableTransitionState(fullTarget) }
    fullVisibility.targetState = fullTarget

    val ease = CubicBezierEasing(.215f, .61f, .355f, 1f)
    val enterMini = if (motion) fadeIn(tween(260, easing = ease)) else EnterTransition.None
    val exitMini = if (motion) fadeOut(tween(180)) else ExitTransition.None
    val enterFull = if (motion) slideInVertically(tween(340, easing = ease)) { it / 3 } +
        fadeIn(tween(260)) else EnterTransition.None
    val exitFull = if (motion) slideOutVertically(tween(300, easing = ease)) { it / 3 } +
        fadeOut(tween(260)) else ExitTransition.None
    val reopen by rememberUpdatedState(hapticClick(onExpand))

    SharedTransitionLayout(modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = miniTarget,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                enter = enterMini,
                exit = exitMini,
            ) {
                val coverModifier = with(this@SharedTransitionLayout) {
                    Modifier.sharedElement(
                        rememberSharedContentState(key = "music-cover"),
                        animatedVisibilityScope = this@AnimatedVisibility,
                    )
                }
                val titleModifier = with(this@SharedTransitionLayout) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "music-title"),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    )
                }
                GreenMusicMiniPlayer(
                    music, prefs, onExpand, Modifier.fillMaxWidth(),
                    artworkModifier = coverModifier, titleModifier = titleModifier,
                )
            }

            AnimatedVisibility(
                visibleState = fullVisibility,
                modifier = Modifier.fillMaxSize(),
                enter = enterFull,
                exit = exitFull,
            ) {
                val coverModifier = with(this@SharedTransitionLayout) {
                    Modifier.sharedElement(
                        rememberSharedContentState(key = "music-cover"),
                        animatedVisibilityScope = this@AnimatedVisibility,
                    )
                }
                val titleModifier = with(this@SharedTransitionLayout) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "music-title"),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    )
                }
                GreenMusicPlayerScreen(
                    music, prefs, onCollapse, expanded = expanded,
                    artworkModifier = coverModifier, titleModifier = titleModifier,
                )
            }

            // Android hit testing still targets the exiting fullscreen screen.
            // Put a transparent interceptor ABOVE it, exactly over the mini
            // destination. DOWN (rather than UP) reverses motion at once and
            // cannot be lost if the closing screen finishes while finger is held.
            if (canInterruptMusicCollapse(
                    available, hasTrack, prefs.musicMiniPlayer, expanded,
                    fullVisibility.currentState || !fullVisibility.isIdle
                )
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .height(86.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                down.consume()
                                reopen()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "立即重新展开音乐播放器"
                            onClick { reopen(); true }
                        },
                )
            }
        }
    }
}
