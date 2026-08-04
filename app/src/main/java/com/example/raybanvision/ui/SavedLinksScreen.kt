package com.example.raybanvision.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.raybanvision.R
import com.example.raybanvision.data.SavedLink
import com.example.raybanvision.session.SavedLinksStore
import com.example.raybanvision.ui.components.UpperBar
import com.example.raybanvision.ui.components.UpperBarVariant
import com.example.raybanvision.ui.theme.Black1000
import com.example.raybanvision.ui.theme.Grey900
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White200
import com.example.raybanvision.ui.theme.White300
import com.example.raybanvision.ui.theme.White400
import com.example.raybanvision.ui.theme.White500
import com.example.raybanvision.ui.theme.White700
import com.example.raybanvision.ui.theme.White1000

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
            title = "\uC800\uC7A5\uD55C \uB9C1\uD06C",
            onBack = onBack,
            onRightAction = onRetake,
            variant = UpperBarVariant.SavedLink,
            modifier = Modifier.padding(horizontal = ShoplyDimens.Space600, vertical = 8.dp),
        )

        if (links.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ShoplyDimens.Space800),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\uC800\uC7A5\uD55C \uB9C1\uD06C\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4",
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
                itemsIndexed(links) { index, link ->
                    SavedLinkCard(link = link, focused = index == 0)
                }
            }
        }
    }
}

@Composable
private fun SavedLinkCard(link: SavedLink, focused: Boolean) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(ShoplyDimens.RadiusCard)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(White200)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) White700 else White300,
                shape = shape,
            )
            .drawBehind {
                if (focused) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.55f,
                        ),
                    )
                }
            }
            .padding(horizontal = ShoplyDimens.Space600, vertical = ShoplyDimens.Space400),
        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space600),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200)) {
            Text(
                text = link.productName,
                style = ShoplyType.Subheading,
                color = White700,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = link.store,
                    style = ShoplyType.BodyStrong,
                    color = White500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "\u20A9 ${link.price}",
                    style = ShoplyType.BodyStrong,
                    color = White500,
                    maxLines = 1,
                )
            }
            Text(text = link.savedAt, style = ShoplyType.BodySmall, color = White400)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space300),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenLinkButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (link.linkUrl.isNotEmpty()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.linkUrl)))
                    }
                },
            )
            DeleteLinkButton(
                onClick = { SavedLinksStore.remove(link.linkUrl) },
            )
        }
    }
}

@Composable
private fun OpenLinkButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFFFE45C), Color(0xFFA8E937)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ShoplyDimens.Space400, vertical = ShoplyDimens.Space300),
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
            text = "\uB9C1\uD06C \uBC14\uB85C\uAC00\uAE30",
            style = ShoplyType.SingleLineBodySmallStrong,
            color = Grey900,
        )
    }
}

@Composable
private fun DeleteLinkButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShoplyDimens.RadiusFull)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(White300)
            .clickable(onClick = onClick)
            .padding(horizontal = ShoplyDimens.Space400, vertical = ShoplyDimens.Space300),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\uC0AD\uC81C",
            style = ShoplyType.SingleLineBodySmallStrong,
            color = White700,
            maxLines = 1,
        )
    }
}
