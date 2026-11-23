// Adapted from Review, Copyright 2026 Chengeeker, MIT.
package dev.chengeeker.lucent.review

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

/** All application haptics go through this opt-in, style-aware policy.
 * TAP: buttons and menu items; SELECTION: each newly crossed thumbnail;
 * LONG_PRESS: immediately when a long-press gesture is recognized, not on release.
 */
enum class ReviewHapticKind { TAP, SELECTION, LONG_PRESS }

object SilentComposeHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

val LocalReviewHapticsEnabled = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
fun reviewHaptic(kind: ReviewHapticKind = ReviewHapticKind.TAP): () -> Unit {
    val view = LocalView.current
    val enabled = LocalReviewHapticsEnabled.current
    return remember(view, enabled, kind) {
        {
            if (enabled()) {
                val constant = when (kind) {
                    ReviewHapticKind.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
                    ReviewHapticKind.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
                    ReviewHapticKind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
                }
                view.performHapticFeedback(constant)
            }
        }
    }
}

/** Compatible alias used by gallery thumbnails and other Lucent controls. */
@Composable
fun reviewPulse(): () -> Unit = reviewHaptic()

@Composable
fun hapticClick(action: () -> Unit): () -> Unit {
    val pulse = reviewPulse()
    return { pulse(); action() }
}

@Composable
fun <T> hapticChange(action: (T) -> Unit): (T) -> Unit {
    val pulse = reviewPulse()
    return { value -> pulse(); action(value) }
}

@Composable
fun Modifier.reviewClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = hapticClick(onClick))

@Composable
fun Modifier.reviewToggleable(value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit): Modifier =
    toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = hapticChange(change))
