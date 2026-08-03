package com.example.raybanvision.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.raybanvision.R
import com.example.raybanvision.data.SavedLink
import com.example.raybanvision.session.SavedLinksStore
import com.example.raybanvision.ui.components.LinkSaveButton
import com.example.raybanvision.ui.components.UpperBar
import com.example.raybanvision.ui.components.UpperBarVariant
import com.example.raybanvision.ui.theme.Black1000
import com.example.raybanvision.ui.theme.Grey900
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White200
import com.example.raybanvision.ui.theme.White400
import com.example.raybanvision.ui.theme.White500
import com.example.raybanvision.ui.theme.White700
import com.example.raybanvision.ui.theme.White1000

// =============================================================================
// SavedLinksScreen — Figma node 167:3866
//
// Layout:
//   Column(Black1000, px=Space400, statusBar+navBar) {
//     Title "저장한 링크" — BodyStrong · White1000 · centered
//     LazyColumn(gap=Space400) { SavedLinkCard × links.size }
//   }
// =============================================================================

@Composable
fun SavedLinksScreen(
    onBack: () -> Unit = {},
    onRetake: () -> Unit = {},
) {
    val links = SavedLinksStore.links

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black1000)
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space900),
    ) {
        UpperBar(
            title = "저장한 링크",
            onBack = onBack,
            onRightAction = onRetake,
            variant = UpperBarVariant.SavedLink,
            modifier = Modifier.padding(
                horizontal = ShoplyDimens.Space600,
                vertical   = 8.dp,
            ),
        )

        if (links.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "저장한 링크가 없습니다",
                    style = ShoplyType.BodyBase,
                    color = White400,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ShoplyDimens.Space800),
                verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
            ) {
                items(links) { link ->
                    SavedLinkCard(link = link)
                }
            }
        }
    }
}

// =============================================================================
// Sub-composables
// =============================================================================

/**
 * SavedLink card — Figma node 167:3871.
 * bg=White200 · p=Space400 · radius=RadiusCard
 * Info: productName (Subheading/White700) + store+price row (BodyStrong/White500) + date (BodySmall/White400)
 * Buttons: "링크 저장" (brand-transparent pill) + "링크 바로가기" (White700 pill, weight=1)
 */
@Composable
private fun SavedLinkCard(link: SavedLink) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusCard)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(White200)
            .padding(ShoplyDimens.Space400),
        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space600),
    ) {
        // ── Info ──────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200)) {
            // Product name — Subheading (32px×0.8=26sp) · White700
            Text(
                text = link.productName,
                style = ShoplyType.Subheading,
                color = White700,
            )
            // Store + price row
            Row(
                horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = link.store,
                    style = ShoplyType.BodyStrong,
                    color = White500,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "₩", style = ShoplyType.BodyStrong, color = White500)
                    Text(text = link.price, style = ShoplyType.BodyStrong, color = White500)
                }
            }
            // Saved date — BodySmall · White400
            Text(
                text = link.savedAt,
                style = ShoplyType.BodySmall,
                color = White400,
            )
        }

        // ── Buttons ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinkSaveButton(isSaved = true, onClick = {})
            GoLinkButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (link.linkUrl.isNotEmpty()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.linkUrl)))
                    }
                },
            )
        }
    }
}


/**
 * "링크 바로가기" — Figma node 168:4565.
 * bg=White700 · pill · inset glow · Grey900 text · flex=1.
 */
@Composable
private fun GoLinkButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    Row(
        modifier = modifier
            .clip(shape)
            .background(White700)
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
