package com.example.raybanvision.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.raybanvision.R
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.ui.theme.Grey900
import com.example.raybanvision.ui.theme.Grey100
import com.example.raybanvision.ui.theme.Grey800
import com.example.raybanvision.ui.theme.Lime500
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White300
import com.example.raybanvision.ui.theme.White500
import com.example.raybanvision.ui.theme.White700
import com.example.raybanvision.ui.theme.White1000
import com.example.raybanvision.ui.theme.Yellow400

enum class ShoplyButtonVariant {
    TransparentWhite,
    BrandSolid,
    NeutralSolid,
    SubtleSolid,
}

enum class UpperBarVariant {
    Default,
    SavedLink,
}

@Composable
fun UpperBar(
    title: String,
    onBack: () -> Unit,
    onRightAction: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UpperBarVariant = UpperBarVariant.Default,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = "Back",
            tint = White1000,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(ShoplyDimens.RadiusFull))
                .clickable(onClick = onBack)
                .padding(6.dp),
        )
        Text(text = title, style = ShoplyType.BodyStrong, color = White1000)
        Icon(
            painter = painterResource(
                if (variant == UpperBarVariant.SavedLink) R.drawable.ic_camera else R.drawable.ic_bookmark
            ),
            contentDescription = null,
            tint = White1000,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(ShoplyDimens.RadiusFull))
                .clickable(onClick = onRightAction)
                .padding(6.dp),
        )
    }
}

@Composable
fun ShoplyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ShoplyButtonVariant,
    icon: Painter? = null,
) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    val background = when (variant) {
        ShoplyButtonVariant.TransparentWhite -> Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x08FFFFFF)))
        ShoplyButtonVariant.BrandSolid -> Brush.linearGradient(
            colorStops = arrayOf(
                0.06f to Yellow400,
                0.69f to Color(0x1AFAF06C),
                1f to Lime500,
            ),
        )
        ShoplyButtonVariant.NeutralSolid -> Brush.verticalGradient(listOf(Grey800, Grey800))
        ShoplyButtonVariant.SubtleSolid -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val contentColor = when (variant) {
        ShoplyButtonVariant.TransparentWhite -> White700
        ShoplyButtonVariant.BrandSolid -> Grey900
        ShoplyButtonVariant.NeutralSolid -> Grey100
        ShoplyButtonVariant.SubtleSolid -> com.example.raybanvision.ui.theme.Grey700
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = ShoplyDimens.Space800, vertical = ShoplyDimens.Space600),
        horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                painter = it,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(ShoplyDimens.IconSizeSm),
            )
        }
        Text(text = label, style = ShoplyType.BodyStrong, color = contentColor)
    }
}

@Composable
fun ProductNameCard(result: AnalysisResult, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusCard)
    val productName = result.productName ?: result.originalProductName ?: result.headline
    val subtitle = listOfNotNull(result.brand ?: result.originalBrand, result.category)
        .joinToString(" · ")
        .ifBlank { result.rawStatus ?: "상품 정보" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.14f to Yellow400,
                        0.54f to Color(0x0DFAF06C),
                        0.58f to Color(0x0DCEFE7B),
                        1f to Lime500,
                    ),
                ),
            )
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)))
            }
            .padding(horizontal = ShoplyDimens.Space600, vertical = ShoplyDimens.Space400),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = productName, style = ShoplyType.ProductTitle, color = White1000)
                Text(text = subtitle, style = ShoplyType.ProductSubtitle, color = White700)
            }
        }
    }
}
