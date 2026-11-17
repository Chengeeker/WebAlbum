package com.webdav.album

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop as KyantBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import dev.chengeeker.lucent.review.LocalBackdrop

/**
 * Exact GPU backdrop capture strategy from Review's BackdropNavigation.kt.
 * Sample only the WebAlbum page layer UNDER the mini player; never itself.
 */
private class ReviewMiniSource(
    val layer: GraphicsLayer,
    val origin: () -> Offset,
    val background: Color,
) : KyantBackdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val p = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: return
        drawRect(background)
        translate(origin().x - p.x, origin().y - p.y) {
            drawLayer(layer)
        }
    }
}

/**
 * Matches Review 3.18.3 navigation glass, minus the selectable pill.
 * The classic material uses .75 tint; the clear/frosted one uses .40/.42.
 * Both share the 24dp lens, highlight, and GPU backdrop source.
 */
@RequiresApi(33)
@Composable
internal fun Modifier.reviewMiniPlayerBackdrop(
    dark: Boolean,
    translucent: Boolean,
): Modifier {
    val behind = LocalBackdrop.current
    if (Build.VERSION.SDK_INT < 33 || behind == null) {
        return background(if (dark) Color(0xFF20252C) else Color(0xFFF1F4F8))
    }
    val scene = MaterialTheme.colorScheme.background
    val source = remember(behind, scene) {
        ReviewMiniSource(behind.layer, { behind.origin }, scene)
    }
    val tint = (if (dark) Color.Black else if (translucent) Color(0xFFF5F5F5) else Color.White)
        .copy(alpha = if (!translucent) .75f else if (dark) .4f else .42f)

    return drawBackdrop(
        backdrop = source,
        shape = { RoundedCornerShape(24.dp) },
        effects = {
            blur((if (translucent && !dark) 6f else 2f).dp.toPx())
            lens(24.dp.toPx(), 24.dp.toPx())
        },
        highlight = { Highlight.Default.copy(alpha = if (dark) .35f else .65f) },
        shadow = null,
        onDrawSurface = { drawRect(tint) },
    )
}

/**
 * Review/Kyant optical glass on the SAME moving RoundRect used by the full
 * player clip. Blur, lens, tint and highlight strength all approach 1
 * continuously while shrinking, instead of swapping a flat surface to a
 * different glass panel on the final frame.
 *
 * The shape is a supported CornerBasedShape on a Box placed inside the
 * full-screen clip at exactly the morph's bounds. This is required because
 * Kyant's lens throws on arbitrary GenericShape outlines.
 * The mini never draws a second glass rim.
 */
@Composable
internal fun Modifier.reviewMorphingPlayerBackdrop(
    dark: Boolean,
    translucent: Boolean,
    strength: Float,
    // Compile-time restriction prevents regressing to the GenericShape
    // that caused the 1.3.20 real-device crash.
    shape: CornerBasedShape,
): Modifier {
    val captured = LocalBackdrop.current
    if (Build.VERSION.SDK_INT < 33 || captured == null) return this
    val k = strength.coerceIn(0f, 1f)
    if (k < .001f) return this
    val scene = MaterialTheme.colorScheme.background
    val source = remember(captured, scene) {
        ReviewMiniSource(captured.layer, { captured.origin }, scene)
    }
    val tint = (if (dark) Color.Black
        else if (translucent) Color(0xFFF5F5F5)
        else Color.White).copy(
        alpha = (if (!translucent) .75f else if (dark) .4f else .42f) * k,
    )
    return drawBackdrop(
        backdrop = source,
        shape = { shape },
        effects = {
            val blurRadius = if (translucent && !dark) 6f else 2f
            blur((blurRadius * k).dp.toPx())
            // Keep the optical lens positive even at the tiny first
            // visible glass frame, and identical to Review when k == 1.
            val optics = (24f * k).coerceAtLeast(.05f).dp.toPx()
            lens(optics, optics)
        },
        highlight = {
            Highlight.Default.copy(alpha = (if (dark) .35f else .65f) * k)
        },
        shadow = null,
        onDrawSurface = { drawRect(tint) },
    )
}
