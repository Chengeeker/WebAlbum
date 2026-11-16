package com.webdav.album

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Adapted from GreenMusic.ui.SwipeFrame: elastic horizontal song switching,
 * fling-threshold-aware finishing and cubic bezier settlement.
 */
@Composable
internal fun GreenMusicSwipeFrame(
    key: Any,
    hasNeighbor: (Int) -> Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentHasNeighbor by rememberUpdatedState(hasNeighbor)
    val currentOnChange by rememberUpdatedState(onChange)
    val progress = remember { Animatable(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var previousKey by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (previousKey != key) {
            previousKey = key
            settling?.cancel()
            dragging = false
            distance = 0f
            progress.snapTo(0f)
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val threshold = with(density) { 50.dp.toPx() }
        fun settle(commit: Boolean) {
            val step = if (distance < 0f) 1 else -1
            dragging = false
            settling?.cancel()
            settling = scope.launch {
                val from = distance
                progress.snapTo(from)
                progress.animateTo(
                    if (commit) -step * width else 0f,
                    tween(220, easing = CubicBezierEasing(.215f, .61f, .355f, 1f)),
                )
                if (commit) currentOnChange(step) else distance = 0f
                progress.snapTo(0f)
            }
        }
        val offset = if (dragging) distance else progress.value
        Box(Modifier.fillMaxSize().pointerInput(key, width) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var horizontal = false
                var vertical = false
                var completed = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!horizontal && !vertical && delta.getDistance() > 18.dp.toPx()) {
                        horizontal = abs(delta.x) > abs(delta.y)
                        vertical = !horizontal
                        if (horizontal) {
                            settling?.cancel()
                            dragging = true
                        }
                    }
                    if (horizontal) {
                        distance = if (currentHasNeighbor(if (delta.x < 0) 1 else -1))
                            delta.x.coerceIn(-width, width) else 0f
                        change.consume()
                    }
                    if (!change.pressed) {
                        if (horizontal) {
                            val speed = tracker.calculateVelocity().x
                            val allowed = currentHasNeighbor(if (distance < 0) 1 else -1)
                            settle(allowed && abs(distance) >= threshold &&
                                (abs(speed) < 200 || sign(speed) == sign(distance)))
                            completed = true
                        }
                        break
                    }
                    if (vertical) break
                } while (true)
                if (horizontal && !completed) settle(false)
            }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset }) { content(0) }
            val step = if (offset < 0f) 1 else -1
            if (offset != 0f && currentHasNeighbor(step)) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset + step * width }) {
                    content(step)
                }
            }
        }
    }
}
