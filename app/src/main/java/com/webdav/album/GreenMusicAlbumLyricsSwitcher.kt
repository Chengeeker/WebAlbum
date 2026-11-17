package com.webdav.album

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One stable coordinate system: album at X, lyrics at X + screenWidth.
 * There is NO rendered-page state and NO offset reset on a completed transition.
 * A tap during animation simply retargets the SAME Animatable from its actual
 * in-flight position, so it cannot bounce to the old page for one frame.
 */
internal data class MusicPagesOffset(val album: Float, val lyrics: Float)
internal fun musicPagesOffset(absoluteX: Float, width: Float): MusicPagesOffset =
    MusicPagesOffset(absoluteX, absoluteX + width)

internal fun musicPageTarget(lyricsVisible: Boolean, width: Float): Float =
    if (lyricsVisible) -width else 0f

@Composable
internal fun GreenMusicAlbumLyricsSwitcher(
    trackKey: String,
    lyricsVisible: Boolean,
    expanded: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    album: @Composable () -> Unit,
    lyrics: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestChange by rememberUpdatedState(onChange)
    val activeLyrics by rememberUpdatedState(lyricsVisible)
    val motion = dev.chengeeker.lucent.LocalLucentStyle.current.motion
    val displacement = remember(trackKey) { Animatable(0f) }
    var fingerX by remember(trackKey) { mutableFloatStateOf(0f) }
    var dragging by remember(trackKey) { mutableStateOf(false) }
    val easing = remember { CubicBezierEasing(.215f, .61f, .355f, 1f) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)

        LaunchedEffect(trackKey, lyricsVisible, expanded, width, motion, dragging) {
            if (!expanded) {
                // The cover must be back at the album endpoint before the
                // fullscreen-to-mini shared-element flight takes possession.
                displacement.snapTo(0f)
            } else if (!dragging) {
                val destination = musicPageTarget(lyricsVisible, width)
                if (motion) displacement.animateTo(destination, tween(260, easing = easing))
                else displacement.snapTo(destination)
            }
        }

        val x = if (dragging) fingerX else displacement.value
        val positions = musicPagesOffset(x, width)

        Box(Modifier.fillMaxSize().pointerInput(trackKey, width) {
            detectHorizontalDragGestures(
                onDragStart = {
                    // Keep the frame visible under the finger; the keyed effect
                    // cancels the previous animation as soon as dragging starts.
                    fingerX = displacement.value
                    dragging = true
                },
                onHorizontalDrag = { change, amount ->
                    fingerX = (fingerX + amount).coerceIn(-width, 0f)
                    change.consume()
                },
                onDragEnd = {
                    val finalX = fingerX
                    val wantsLyrics = if (activeLyrics) {
                        finalX < -width * .82f
                    } else {
                        finalX <= -width * .18f
                    }
                    scope.launch {
                        // Absolute position remains identical before/after this
                        // handoff; only the destination of the animation changes.
                        displacement.snapTo(finalX)
                        if (activeLyrics != wantsLyrics) latestChange(wantsLyrics)
                        dragging = false
                    }
                },
                onDragCancel = {
                    val finalX = fingerX
                    scope.launch {
                        displacement.snapTo(finalX)
                        dragging = false // settle back to the current target
                    }
                },
            )
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = positions.album }) { album() }
            // The off-screen bilingual LazyColumn used to be composed on the
            // same first frame as opening fullscreen. Mount it on demand;
            // it is not drawn until the user reveals the lyric page.
            if (lyricsVisible || dragging || x < -1f) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = positions.lyrics }) { lyrics() }
            }
        }
    }
}
