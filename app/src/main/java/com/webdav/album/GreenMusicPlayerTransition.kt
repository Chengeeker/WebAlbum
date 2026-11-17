package com.webdav.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
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
internal fun shouldShowMiniPlayer(
    available: Boolean, hasTrack: Boolean, miniEnabled: Boolean,
    expanded: Boolean, dismissed: Boolean,
): Boolean = available && hasTrack && miniEnabled && !expanded && !dismissed

/** One coordinated timeline: only the MINI moves; action bar only fades. */
internal object MiniSelectionMotionTiming {
    const val MINI_RISE_MS = 160
    const val BAR_FADE_IN_DELAY_MS = 120
    const val BAR_FADE_IN_MS = 105
    const val BAR_FADE_OUT_MS = 90
    const val MINI_LOWER_DELAY_MS = 100
    const val MINI_LOWER_MS = 160
}

/**
 * This is an ABSOLUTE mini destination, shared by mini, fullscreen return
 * and the interruption hit target. Do not derive it from AnimatedVisibility:
 * while fullscreen is open, the mini is hidden but its destination must still
 * be at the selected/raised position.
 */
internal val miniPlayerBottomClearance = 16.dp

/** Additional displacement while the selection bottom bar is present. */
internal fun miniSelectionLift(
    selecting: Boolean, miniAvailable: Boolean, navigationInset: Boolean,
): androidx.compose.ui.unit.Dp =
    if (!selecting || !miniAvailable) 0.dp
    else if (navigationInset) 48.dp else 56.dp

/** Bottom position in dp relative to the system navigation safe area. */
internal fun miniPlayerAnchorBottom(
    selecting: Boolean, miniAvailable: Boolean, navigationInset: Boolean,
): androidx.compose.ui.unit.Dp =
    miniPlayerBottomClearance + miniSelectionLift(selecting, miniAvailable, navigationInset)

internal fun canInterruptMusicCollapse(
    available: Boolean,
    hasTrack: Boolean,
    miniEnabled: Boolean,
    expanded: Boolean,
    fullscreenStillPresent: Boolean,
): Boolean = available && hasTrack && miniEnabled && !expanded && fullscreenStillPresent

/**
 * Pixel-perfect bounds of the morphing playback background. The starting
 * rectangle is the ACTUAL 24dp-radius mini Surface (not its padded wrapper):
 *
 * bottom = screenBottom - navBar - 16dp clearance - selectionLift - 6dp
 * top    = bottom - 58dp
 *
 * The top edge travels almost a full screen, while the bottom has only a
 * short distance to grow. Separate coordinated easing keeps them natural.
 */
internal data class CapsuleFillGeometry(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val radius: Float,
)

/** The glass strength follows the CORNER, not the fast-moving upper edge.
 * On collapse this returns gradually from 0 to 1; it never suddenly enables
 * after AnimatedVisibility disappears. The shape remains the sole outline.
 */
internal fun capsuleGlassStrength(cornerExpansion: Float): Float =
    (1f - cornerExpansion.coerceIn(0f, 1f))

internal fun capsuleFillGeometry(
    width: Float, height: Float, density: Float,
    navBottomPx: Float, liftPx: Float,
    head: Float, foot: Float, sides: Float, corners: Float = head,
): CapsuleFillGeometry {
    val safeWidth = width.coerceAtLeast(1f)
    val safeHeight = height.coerceAtLeast(1f)
    val miniBottom = (safeHeight - navBottomPx - liftPx - 22f * density)
        .coerceIn(0f, safeHeight)
    val miniTop = (miniBottom - miniPlayerCompactRowHeight.value * density)
        .coerceAtLeast(0f)
    val h = head.coerceIn(0f, 1f)
    val f = foot.coerceIn(0f, 1f)
    val t = sides.coerceIn(0f, 1f)
    val margin = (12f * density).coerceAtMost(safeWidth * .45f)
    return CapsuleFillGeometry(
        left = margin * (1f - t),
        top = miniTop * (1f - h),
        right = safeWidth - margin * (1f - t),
        bottom = miniBottom + (safeHeight - miniBottom) * f,
        radius = 24f * density * (1f - corners.coerceIn(0f, 1f)),
    )
}

/**
 * Exactly ONE visual boundary may be painted while mini -> fullscreen (or
 * fullscreen -> mini) is in progress.  The fullscreen RoundRect owns the
 * single capsule edge; the mini still participates in shared cover/title
 * motion but temporarily stops drawing its independent glass outline/shadow.
 *
 * Unlike expanded alone, this guards the entire exit animation as well,
 * including rapid tap-to-reopen while the fullscreen layer still exists.
 */
internal fun capsuleFillOwnsBoundary(
    fullRequested: Boolean,
    fullCurrent: Boolean,
    fullIdle: Boolean,
): Boolean = fullRequested || fullCurrent || !fullIdle

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
    miniDismissed: Boolean,
    selecting: Boolean,
    revealFromBottom: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onDismissMini: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by music.state.collectAsState()
    val hasTrack = state.current != null
    val motion = LocalLucentStyle.current.motion
    val miniTarget = shouldShowMiniPlayer(
        available, hasTrack, prefs.musicMiniPlayer, expanded, miniDismissed,
    )
    // Keep the mini in its raised slot WHILE exiting. Otherwise dismissal
    // lowers the mini through the bottom selection bar on its way out.
    val miniVisibility = remember { MutableTransitionState(miniTarget) }
    miniVisibility.targetState = miniTarget
    val fullTarget = available && hasTrack && expanded
    val fullVisibility = remember { MutableTransitionState(fullTarget) }
    fullVisibility.targetState = fullTarget
    val fillOwnsBoundary = capsuleFillOwnsBoundary(
        fullRequested = fullTarget,
        fullCurrent = fullVisibility.currentState,
        fullIdle = fullVisibility.isIdle,
    )

    val ease = CubicBezierEasing(.215f, .61f, .355f, 1f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() > 0.dp
    // Keep this position stable throughout expansion and return. Previously it
    // became zero while fullscreen was visible, so shared elements flew to the
    // selection action bar instead of the raised mini-player.
    val liftTarget = miniSelectionLift(
        selecting, hasTrack && prefs.musicMiniPlayer, inset,
    )
    val lift by animateDpAsState(
        targetValue = liftTarget,
        animationSpec = if (!motion) tween(0) else if (selecting)
            tween(MiniSelectionMotionTiming.MINI_RISE_MS, easing = ease)
        else tween(
            MiniSelectionMotionTiming.MINI_LOWER_MS,
            delayMillis = MiniSelectionMotionTiming.MINI_LOWER_DELAY_MS,
            easing = ease,
        ),
        label = "mini-lifts-for-bottom-selection-bar",
    )
    // On return from fullscreen, do not paint the compact pill too early
    // over a still-expanded capsule. It becomes opaque only during the
    // final ~160ms of the reverse morph.
    val returningToMini = !expanded &&
        (fullVisibility.currentState || !fullVisibility.isIdle)
    val enterMini = if (!motion) EnterTransition.None
        else if (revealFromBottom) slideInVertically(tween(300, easing = ease)) { height ->
            // Start BELOW the bottom slot even when selection lifts the card.
            height + with(density) { liftTarget.roundToPx() }
        } + fadeIn(tween(200, easing = ease))
        else if (returningToMini) fadeIn(
            tween(160, delayMillis = 445, easing = ease),
        )
        else fadeIn(tween(260, easing = ease))
    val exitMini = if (motion) fadeOut(tween(160)) +
        slideOutVertically(tween(220, easing = ease)) { if (miniDismissed) it else 0 }
        else ExitTransition.None
    // Keep the fullscreen composition alive for the entire capsule shape
    // transition. A 1% opacity range is intentional: it supplies a running
    // AnimatedVisibility transition without competing slide/fade movement.
    val enterFull = if (motion) fadeIn(
        initialAlpha = .99f, animationSpec = tween(660),
    ) else EnterTransition.None
    val exitFull = if (motion) fadeOut(
        targetAlpha = .99f, animationSpec = tween(660),
    ) else ExitTransition.None

    val fillHead by animateFloatAsState(
        targetValue = if (fullTarget) 1f else 0f,
        animationSpec = if (motion) tween(
            durationMillis = if (fullTarget) 410 else 415,
            easing = CubicBezierEasing(.22f, .66f, .31f, 1f),
        ) else tween(0),
        label = "player-capsule-fast-top-edge",
    )
    val fillFoot by animateFloatAsState(
        targetValue = if (fullTarget) 1f else 0f,
        animationSpec = if (motion) tween(
            durationMillis = if (fullTarget) 420 else 425,
            easing = CubicBezierEasing(.22f, .68f, .30f, 1f),
        ) else tween(0),
        label = "player-capsule-short-bottom-edge",
    )
    val fillSides by animateFloatAsState(
        targetValue = if (fullTarget) 1f else 0f,
        animationSpec = if (motion) tween(
            durationMillis = if (fullTarget) 600 else 620,
            easing = CubicBezierEasing(.40f, .0f, .60f, 1f),
        ) else tween(0),
        label = "player-capsule-horizontal-fill",
    )
    val fillCorners by animateFloatAsState(
        targetValue = if (fullTarget) 1f else 0f,
        animationSpec = if (motion) tween(
            durationMillis = if (fullTarget) 620 else 630,
            easing = CubicBezierEasing(.34f, .0f, .62f, 1f),
        ) else tween(0),
        label = "player-capsule-visible-corner-unfold",
    )
    val reopen by rememberUpdatedState(hapticClick(onExpand))

    val fillBackground = MaterialTheme.colorScheme.surface
    val miniTokens = rememberMiniPlayerGlassTokens()
    val glassIntensity = if (miniTokens.mode == MiniPlayerGlassMode.SOLID) 0f
        else capsuleGlassStrength(fillCorners)
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()
    SharedTransitionLayout(modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pixelDensity = density.density
            val navPx = with(density) { navigationBottom.toPx() }
            val liftPx = with(density) { lift.toPx() }
            // Lens in Kyant Backdrop only accepts rounded-rect/corner-based
            // Shapes, NOT GenericShape.  Measure the optical card as a REAL
            // rounded rectangle and move/resize it to the same dynamic bounds
            // as the parent clip.  This also prevents the effect from drawing
            // outside the expanding capsule.
            val lensBounds = capsuleFillGeometry(
                width = with(density) { maxWidth.toPx() },
                height = with(density) { maxHeight.toPx() },
                density = pixelDensity,
                navBottomPx = navPx, liftPx = liftPx,
                head = fillHead, foot = fillFoot, sides = fillSides,
                corners = fillCorners,
            )
            val lensWidth = with(density) {
                (lensBounds.right - lensBounds.left).coerceAtLeast(1f).toDp()
            }
            val lensHeight = with(density) {
                (lensBounds.bottom - lensBounds.top).coerceAtLeast(1f).toDp()
            }
            val lensRadius = with(density) { lensBounds.radius.toDp() }
            val lensShape = RoundedCornerShape(lensRadius)
            val opticalMaterial = if (
                Build.VERSION.SDK_INT >= 33 &&
                miniTokens.mode != MiniPlayerGlassMode.SOLID
            ) Modifier.reviewMorphingPlayerBackdrop(
                dark = miniTokens.primary.luminance() > .5f,
                translucent = miniTokens.mode == MiniPlayerGlassMode.CLEAR,
                strength = glassIntensity,
                shape = lensShape,
            ) else Modifier
            AnimatedVisibility(
                visibleState = fullVisibility,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val box = capsuleFillGeometry(
                        width = size.width, height = size.height,
                        density = pixelDensity,
                        navBottomPx = navPx, liftPx = liftPx,
                        head = fillHead, foot = fillFoot, sides = fillSides,
                        corners = fillCorners,
                    )
                    val capsule = Path().apply {
                        addRoundRect(
                            RoundRect(
                                Rect(box.left, box.top, box.right, box.bottom),
                                CornerRadius(box.radius, box.radius),
                            ),
                        )
                    }
                    // One continuous filled pill, expanding upward and
                    // slightly downward to cover the whole music screen.
                    // Clip BOTH the background and controls: no sliding page
                    // is visible outside the transforming glass boundary.
                    clipPath(capsule) {
                        // Crossfade only the material *inside* the sole moving
                        // boundary.  At the mini endpoint the Review shader is
                        // fully visible; at fullscreen the normal surface wins.
                        drawRect(fillBackground.copy(alpha = 1f - glassIntensity))
                        this@drawWithContent.drawContent()
                    }
                },
                enter = enterFull,
                exit = exitFull,
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (glassIntensity > .001f && miniTokens.mode != MiniPlayerGlassMode.SOLID) {
                        Box(
                            Modifier.offset {
                                IntOffset(
                                    lensBounds.left.roundToInt(),
                                    lensBounds.top.roundToInt(),
                                )
                            }
                                .size(lensWidth, lensHeight)
                                .then(opticalMaterial),
                        )
                    }
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
                } // fullscreen content and its sole lens-safe glass surface
            }

            AnimatedVisibility(
                visibleState = miniVisibility,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = miniPlayerBottomClearance)
                    .offset(y = -lift),
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
                    music, onExpand, onDismissMini, Modifier.fillMaxWidth(),
                    artworkModifier = coverModifier, titleModifier = titleModifier,
                    renderGlassBackground = !fillOwnsBoundary,
                )
            }

            // Android hit testing still targets the exiting fullscreen screen.
            // Put a transparent interceptor ABOVE it, exactly over the mini
            // destination. DOWN (rather than UP) reverses motion at once and
            // cannot be lost if the closing screen finishes while finger is held.
            if (canInterruptMusicCollapse(
                    available, hasTrack, prefs.musicMiniPlayer && !miniDismissed, expanded,
                    fullVisibility.currentState || !fullVisibility.isIdle
                )
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = miniPlayerBottomClearance)
                        .offset(y = -lift)
                        .fillMaxWidth()
                        .height(miniPlayerCompactRowHeight + 10.dp)
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
