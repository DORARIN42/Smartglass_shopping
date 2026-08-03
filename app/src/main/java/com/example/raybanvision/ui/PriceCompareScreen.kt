package com.example.raybanvision.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.example.raybanvision.R
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.data.ProductCandidate
import com.example.raybanvision.data.SavedLink
import com.example.raybanvision.session.SavedLinksStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.example.raybanvision.ui.components.LinkSaveButton
import com.example.raybanvision.ui.components.scrollFadeEdges
import com.example.raybanvision.ui.components.ProductNameCard
import com.example.raybanvision.ui.components.ShoplyButton
import com.example.raybanvision.ui.components.ShoplyButtonVariant
import com.example.raybanvision.ui.components.UpperBar
import com.example.raybanvision.ui.theme.Black1000
import com.example.raybanvision.ui.theme.Grey900
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White200
import com.example.raybanvision.ui.theme.White700

// =============================================================================
// PriceCompareScreen — Figma node 82:8583
//
// Layout:
//   Box(Black1000 bg + 10% opacity captured photo)
//     Column(px=Space800, gap=Space900) {
//       Title "가격 비교" — BodyStrong · White1000 · centered
//       Column(weight=1) {
//         ProductNameCard  — brand gradient full-width card
//         LazyColumn { PriceInfoCard × candidates.size }
//       }
//       ActionRow — TransparentWhite "다시 찍기" + BrandSolid "상품 정보"(weight=1)
//     }
// =============================================================================

@Composable
fun PriceCompareScreen(
    result: AnalysisResult,
    capturedBitmap: Bitmap?,
    onRetake: () -> Unit,
    onProductInfo: () -> Unit,
    onBack: () -> Unit = {},
    onSavedLinks: () -> Unit = {},
) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Black1000)) {

        // Captured photo background — 10% opacity (Figma: opacity-10)
        if (capturedBitmap != null) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.1f
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
                title = "가격 비교",
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
                // Information section — Figma: gap=20px→Space500 between ProductName and list
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space500),
                ) {
                    ProductNameCard(result = result)

                    // Price card list — Figma: gap=8px→Space200
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .scrollFadeEdges(listState),
                        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200),
                    ) {
                        items(result.candidates, key = { it.linkUrl ?: it.store ?: it.price }) { candidate ->
                            PriceInfoCard(candidate = candidate, productName = result.headline)
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
                        label = "상품 정보",
                        onClick = onProductInfo,
                        modifier = Modifier.weight(1f),
                        variant = ShoplyButtonVariant.BrandSolid,
                        icon = painterResource(R.drawable.ic_info),
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
 * PriceInformation card — Figma nodes 82:8824/8825/8851.
 * bg=White200 · p=Space400 · radius=RadiusCard
 * Heading: ₩ + price (Heading · White700)
 * Store: vendor name (BodyStrong · White700)
 * Buttons: 링크 저장 (White300 bg) + 링크 바로가기 (White700 bg · Grey900 text)
 */
@Composable
private fun PriceInfoCard(candidate: ProductCandidate, productName: String) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusCard)
    val context = LocalContext.current
    // Strip Korean "원" suffix for display; show ₩ prefix separately
    val displayPrice = candidate.price.removeSuffix("원").trim()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
        // Background blur layer — BgBlurRadius token (16dp)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(White200)
                .blur(ShoplyDimens.BgBlurRadius),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ShoplyDimens.Space400),
            verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space600),
        ) {
            // Heading: price + store
            Column(verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200)) {
                // Price: ₩ and amount in separate Text with 4dp gap (priceGap)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "₩", style = ShoplyType.Heading, color = White700)
                    Text(text = displayPrice, style = ShoplyType.Heading, color = White700)
                }
                // Vendor / store name
                Text(
                    text = candidate.store ?: "",
                    style = ShoplyType.BodyStrong,
                    color = White700,
                )
            }

            // Action buttons row
            val isSaved = SavedLinksStore.links.any {
                it.linkUrl == candidate.linkUrl && candidate.linkUrl != null
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkSaveButton(
                    isSaved = isSaved,
                    onClick = {
                        if (isSaved) {
                            candidate.linkUrl?.let { SavedLinksStore.remove(it) }
                        } else {
                            val dateStr = LocalDate.now()
                                .format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + " 저장"
                            SavedLinksStore.save(
                                SavedLink(
                                    productName = productName,
                                    store       = candidate.store ?: "",
                                    price       = displayPrice,
                                    linkUrl     = candidate.linkUrl ?: "",
                                    savedAt     = dateStr,
                                )
                            )
                        }
                    },
                )
                LinkGoButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        candidate.linkUrl?.let { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )
            }
        }
    }
}


/**
 * "링크 바로가기" — Figma node 168:4317.
 * bg=White700 (85% white) · pill · py=Space300 px=Space400 · inset glow · Grey900 text.
 * flex=1 applied via modifier.
 */
@Composable
private fun LinkGoButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    Row(
        modifier = modifier
            .clip(shape)
            .background(White700)
            // EffectStyleButtonDefault: inset 0px 4px 24px rgba(255,255,255,0.3)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    )
                )
            }
            .clickable(onClick = onClick)
            .padding(
                horizontal = ShoplyDimens.Space400,
                vertical = ShoplyDimens.Space300,
            ),
        horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_link),
            contentDescription = null,
            tint = Grey900,
            modifier = Modifier.size(ShoplyDimens.IconSizeSm),
        )
        Text(
            text = "링크 바로가기",
            style = ShoplyType.SingleLineBodySmallStrong,
            color = Grey900,
        )
    }
}
