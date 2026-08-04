package com.example.raybanvision.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Scroll-fade edge mask — mirrors scroll-fade.js / .scroll-fade-edges from the web prototype.
 *
 * - Bottom fade: visible when canScrollForward (more content below).
 * - Top fade:    visible only after user has scrolled past the first item (canScrollBackward).
 *                When at the top, the top edge reads as "this is the start" — no fade applied.
 * - Both edges animate in/out with a 200 ms tween.
 *
 * Uses CompositingStrategy.Offscreen + BlendMode.DstIn so the gradient acts as a true alpha
 * mask over the layer's own content (not the parent layer behind it).
 */
fun Modifier.scrollFadeEdges(state: LazyListState): Modifier = composed {
    val topAlpha by animateFloatAsState(
        targetValue = if (state.canScrollBackward) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "scroll_fade_top",
    )
    val bottomAlpha by animateFloatAsState(
        targetValue = if (state.canScrollForward) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "scroll_fade_bottom",
    )

    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // Single DstIn rect carries the full mask for both edges.
            // Color.Black (alpha=1) → preserve; Color.Transparent (alpha=0) → erase.
            // The alpha at each extreme animates so the fade grows smoothly in/out.
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 1f - topAlpha),
                        0.12f to Color.Black,
                        0.80f to Color.Black,
                        1.00f to Color.Black.copy(alpha = 1f - bottomAlpha),
                    )
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
