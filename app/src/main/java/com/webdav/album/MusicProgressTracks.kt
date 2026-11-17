package com.webdav.album

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** The expressive wavy thumb is half the height of the existing straight thumb. */
internal fun musicWaveThumbHeight(wavy: Boolean): Dp = if (wavy) 22.dp else 44.dp

internal fun musicSliderProgress(value: Float, minimum: Float, maximum: Float): Float {
    if (!value.isFinite() || !minimum.isFinite() || !maximum.isFinite() || maximum <= minimum) return 0f
    return ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
}

/** M3 expressive: played wave and unplayed straight line are DISJOINT segments. */
internal data class MusicWaveSegments(
    val activeEnd: Float,
    val inactiveStart: Float,
    val inactiveEnd: Float,
)
internal fun musicWaveSegments(progress: Float, width: Float, gap: Float): MusicWaveSegments {
    val safeWidth = width.coerceAtLeast(0f)
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val activeEnd = safeWidth * fraction
    return MusicWaveSegments(
        activeEnd = activeEnd,
        inactiveStart = if (activeEnd == 0f) 0f else
            (activeEnd + gap.coerceAtLeast(0f)).coerceAtMost(safeWidth),
        inactiveEnd = safeWidth,
    )
}

internal fun musicWaveOffset(x: Float, wavelength: Float, amplitude: Float, phase: Float): Float =
    (sin(2.0 * PI * (x / wavelength + phase)) * amplitude).toFloat()

/**
 * Expressive wavy *track*, not a separate seek control. The full player keeps
 * its M3 Slider for input, haptics, thumb and accessibility, swapping only track.
 */
@Composable
internal fun MusicWavyProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    stroke: Dp = 4.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val transition = rememberInfiniteTransition(label = "music-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "music-wave-phase",
    )
    Canvas(modifier) {
        val line = stroke.toPx().coerceAtLeast(1f)
        val y = size.height * .5f
        val segments = musicWaveSegments(progress, size.width, 4.dp.toPx())
        val end = segments.activeEnd
        // Never draw a grey straight track underneath the played sine wave.
        if (segments.inactiveEnd - segments.inactiveStart > line) {
            drawLine(
                inactive,
                Offset(segments.inactiveStart, y),
                Offset(segments.inactiveEnd, y),
                line,
                cap = StrokeCap.Round,
            )
        }
        if (end <= 0f) return@Canvas
        val waveLength = 22.dp.toPx().coerceAtLeast(1f)
        val amp = (size.height * .22f).coerceAtMost(5.dp.toPx())
        val path = Path().apply { moveTo(0f, y + musicWaveOffset(0f, waveLength, amp, if (active) phase else 0f)) }
        var x = 0f
        val offset = if (active) phase else 0f
        val step = 2.dp.toPx().coerceAtLeast(1f)
        while (x + step < end) {
            x += step
            path.lineTo(x, y + musicWaveOffset(x, waveLength, amp, offset))
        }
        path.lineTo(end, y + musicWaveOffset(end, waveLength, amp, offset))
        drawPath(path, primary, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
