package com.webdav.album

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chengeeker.lucent.LocalLucentStyle
import dev.chengeeker.lucent.review.frostedBackdrop

internal enum class MiniPlayerGlassMode { SOLID, FROSTED, CLEAR }
internal data class MiniPlayerGlassTokens(
    val mode: MiniPlayerGlassMode,
    val fill: Color,
    val primary: Color,
    val secondary: Color,
    val stroke: Color,
    val shadow: Dp,
)

/** Readability-first background: the worst-case black/white backdrop still
 * gives high text contrast even if the source capture or AGSL blur fails. */
internal fun miniPlayerGlassTokens(
    glass: Boolean, translucent: Boolean, dark: Boolean,
    solid: Color, solidText: Color, solidSecondary: Color,
): MiniPlayerGlassTokens {
    if (!glass) return MiniPlayerGlassTokens(
        MiniPlayerGlassMode.SOLID, solid, solidText, solidSecondary,
        Color.Transparent, 5.dp,
    )
    // Exact Review navigation body tint: opaque-ish classic vs translucent
    // frosted mode.  Optical refraction comes from Kyant lens, not the tint.
    val clear = translucent
    val primary = if (dark) Color(0xFFF8F9FE) else Color(0xFF141A25)
    val fill = when {
        dark -> Color.Black.copy(alpha = if (clear) .40f else .75f)
        clear -> Color(0xFFF5F5F5).copy(alpha = .42f)
        else -> Color.White.copy(alpha = .75f)
    }
    return MiniPlayerGlassTokens(
        if (clear) MiniPlayerGlassMode.CLEAR else MiniPlayerGlassMode.FROSTED,
        fill, primary, primary.copy(alpha = .90f),
        Color.Transparent,
        5.dp,
    )
}

@Composable
internal fun rememberMiniPlayerGlassTokens(): MiniPlayerGlassTokens {
    val colors = MaterialTheme.colorScheme
    val style = LocalLucentStyle.current
    val dark = colors.background.luminance() < .5f
    return miniPlayerGlassTokens(
        style.glass, style.translucent, dark,
        colors.surfaceContainerHigh, colors.onSurface, colors.onSurfaceVariant,
    )
}

/**
 * Draws the same outer glass material as Review's BackdropNavigation:
 * real GPU backdrop sampling + blur + a 24dp optical lens + highlight.
 * This deliberately does NOT use the earlier RedNote AGSL shader.
 */
@Composable
internal fun Modifier.miniPlayerGlassBackground(tokens: MiniPlayerGlassTokens): Modifier {
    val shape = RoundedCornerShape(24.dp)
    val dark = tokens.primary.luminance() > .5f
    if (tokens.mode == MiniPlayerGlassMode.SOLID) {
        return shadow(tokens.shadow, shape, clip = false)
            .clip(shape).background(tokens.fill)
    }
    // The Backdrop library owns the contour; never clip before sampling
    // or draw a competing external stroke.
    val base = shadow(tokens.shadow, shape, clip = false)
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        base.reviewMiniPlayerBackdrop(
            dark = dark,
            translucent = tokens.mode == MiniPlayerGlassMode.CLEAR,
        )
    } else {
        base.clip(shape).frostedBackdrop(
            color = tokens.fill, panel = true,
            blur = if (tokens.mode == MiniPlayerGlassMode.CLEAR) 6.dp else 2.dp,
            panelAlpha = tokens.fill.alpha, showBorder = false,
        )
    }
}
