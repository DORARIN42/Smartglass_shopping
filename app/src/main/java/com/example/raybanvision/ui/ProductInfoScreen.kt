package com.example.raybanvision.ui

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.raybanvision.R
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.ui.components.ProductNameCard
import com.example.raybanvision.ui.components.ShoplyButton
import com.example.raybanvision.ui.components.ShoplyButtonVariant
import com.example.raybanvision.ui.components.UpperBar
import com.example.raybanvision.ui.theme.Black1000
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White1000
import com.example.raybanvision.ui.theme.White500
import com.example.raybanvision.ui.theme.White700

// =============================================================================
// ProductInfoScreen — Figma node 71:6528
//
// Layout (tokens.css / ProductInfoScreen.json):
//   Column(SpaceBetween, statusBar+navBar padding, px=Space800, gap=Space900)
//     "상품 정보" title — Subheading · White1000 · centered
//     Column(weight=1) {
//       ProductNameCard  — brand gradient · inset glow · thick border
//       LazyColumn(weight=1) { ProductInformationCard × N }
//     }
//     ActionRow — TransparentWhite "다시 찍기" + BrandSolid "가격 비교"(weight=1)
//
// Background: captured photo at opacity=0.3f over Black1000
// =============================================================================

@Composable
fun ProductInfoScreen(
    result: AnalysisResult,
    capturedBitmap: Bitmap?,
    onRetake: () -> Unit,
    onPriceCompare: () -> Unit,
    onBack: () -> Unit = {},
    onSavedLinks: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().background(Black1000)) {

        // Captured photo background — opacity 0.3 (ProductInfoScreen.json §capturedPhotoBg)
        if (capturedBitmap != null) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.3f
                        setImageBitmap(capturedBitmap)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Figma: "Content" gap=40px→Space900 between UpperBar and content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space900),
        ) {
            // ── UpperBar — Figma: px=24px→Space600, py=10px→8dp ──────────────
            UpperBar(
                title = "상품 정보",
                onBack = onBack,
                onRightAction = onSavedLinks,
                modifier = Modifier.padding(
                    horizontal = ShoplyDimens.Space600,
                    vertical   = 8.dp,
                ),
            )

            // ── Content — Figma: px=32px→Space800, pb=40px→Space900, gap=40px→Space900
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = ShoplyDimens.Space800, end = ShoplyDimens.Space800, bottom = ShoplyDimens.Space900),
                verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space900),
            ) {
                // Information section — Figma: gap=16px→Space400 between ProductName and list
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
                ) {
                    ProductNameCard(result = result)

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space300),
                    ) {
                        item {
                            ProductInformationCard(
                                heading = "상품 요약",
                                body = result.productDescription ?: "",
                            )
                        }
                        if (result.specifications.isNotEmpty()) {
                            item {
                                ProductInformationCard(
                                    heading = "영양성분",
                                    body = result.specifications.joinToString("\n"),
                                )
                            }
                        }
                        if (result.positiveReviews.isNotEmpty() || result.negativeReviews.isNotEmpty()) {
                            item {
                                val reviewText = buildString {
                                    if (result.positiveReviews.isNotEmpty()) {
                                        append("[장점] ")
                                        append(result.positiveReviews.joinToString(", "))
                                    }
                                    if (result.negativeReviews.isNotEmpty()) {
                                        if (isNotEmpty()) append("\n")
                                        append("[단점] ")
                                        append(result.negativeReviews.joinToString(", "))
                                    }
                                }
                                ProductInformationCard(heading = "후기", body = reviewText)
                            }
                        }
                        result.message?.let { msg ->
                            item {
                                ProductInformationCard(heading = "주요 성분", body = msg)
                            }
                        }
                    }
                }

                // Action row — Figma: gap=16px→Space400
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShoplyButton(
                        label = "다시 찍기",
                        onClick = onRetake,
                        variant = ShoplyButtonVariant.TransparentWhite,
                        icon = painterResource(R.drawable.ic_camera),
                    )
                    ShoplyButton(
                        label = "가격 비교",
                        onClick = onPriceCompare,
                        modifier = Modifier.weight(1f),
                        variant = ShoplyButtonVariant.BrandSolid,
                        icon = painterResource(R.drawable.ic_dollar_sign),
                    )
                }
            }
        }
    }
}

// =============================================================================
// Sub-composables
// =============================================================================

/**
 * ProductInformation card (ProductInformation.json / Figma node 72:7417).
 * Background: --ColorButtonTransparentWhiteDefault (rgba 10%→3% vertical gradient)
 * Border: StrokeWhiteDefault 1dp
 * Heading: BodyStrong (SemiBold 19sp) · White700
 * Body: BodyBase (Regular 19sp) · White500 · maxLines=3
 */
private const val BODY_MAX_CHARS = 250

@Composable
private fun ProductInformationCard(heading: String, body: String) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusCard)
    val truncated = body.take(BODY_MAX_CHARS)
    val needsExpand = body.length > BODY_MAX_CHARS
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (needsExpand) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        // Background blur layer — BgBlurRadius token (16dp)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x08FFFFFF)))
                )
                .blur(ShoplyDimens.BgBlurRadius),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 300))
                .padding(
                    horizontal = ShoplyDimens.Space600,
                    vertical = ShoplyDimens.Space400,
                ),
            verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space300),
        ) {
            Text(
                text = heading,
                style = ShoplyType.BodyStrong,
                color = White700,
            )
            if (body.isNotEmpty()) {
                Text(
                    text = if (expanded || !needsExpand) body else "$truncated…",
                    style = ShoplyType.BodyBase,
                    color = White500,
                )
            }
        }
    }
}
