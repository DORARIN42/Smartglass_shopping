package com.example.raybanvision.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.example.raybanvision.R
import com.example.raybanvision.ui.theme.Lime500
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White300
import com.example.raybanvision.ui.theme.White700
import com.example.raybanvision.ui.theme.Yellow400

// =============================================================================
// LinkSaveButton — Figma "Save Link" component (node 168:4260 / 168:4571)
//
// isSaved=false → White300 bg (rgba 255,255,255,0.2) · outline star
// isSaved=true  → soft brand-transparent gradient (Yellow+Lime 50% alpha) · filled star
// =============================================================================

@Composable
fun LinkSaveButton(
    isSaved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    // 0f = unsaved, 1f = saved — drives background and icon crossfade
    val progress by animateFloatAsState(
        targetValue = if (isSaved) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "link_save_progress",
    )

    Row(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                // Unsaved base — fades out as progress → 1
                drawRect(color = White300.copy(alpha = White300.alpha * (1f - progress)))

                if (progress > 0f) {
                    // Saved brand gradient — fades in as progress → 1
                    // Layer 1: white vertical fade
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.30f * progress), Color.Transparent)
                        )
                    )
                    // Layer 2: radial white highlight at (42%, 0%)
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f     to Color.White.copy(alpha = 0.29f * progress),
                                0.664f to Color.Transparent,
                            ),
                            center = Offset(size.width * 0.4242f, 0f),
                            radius = size.width,
                        )
                    )
                    // Layer 3: Lime 50%
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.1384f to Lime500.copy(alpha = 0.50f * progress),
                                0.5825f to Color(0xFFCEFE7B).copy(alpha = 0.05f * progress),
                            ),
                            start = Offset(size.width * 0.08f, 0f),
                            end   = Offset(size.width * 0.92f, size.height),
                        )
                    )
                    // Layer 4: Yellow 50%
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.1365f to Yellow400.copy(alpha = 0.50f * progress),
                                0.5424f to Color(0xFFFAF06C).copy(alpha = 0.05f * progress),
                            ),
                            start = Offset(size.width, size.height * 0.15f),
                            end   = Offset(0f, size.height * 0.85f),
                        )
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(
                horizontal = ShoplyDimens.Space400,
                vertical   = ShoplyDimens.Space300,
            ),
        horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (isSaved) R.drawable.ic_star_filled else R.drawable.ic_star
            ),
            contentDescription = null,
            tint = White700,
            modifier = Modifier.size(ShoplyDimens.IconSizeSm),
        )
        Text(
            text = "링크 저장",
            style = ShoplyType.SingleLineBodySmallStrong,
            color = White700,
        )
    }
}
