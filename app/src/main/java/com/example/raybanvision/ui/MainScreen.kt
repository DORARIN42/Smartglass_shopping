package com.example.raybanvision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.raybanvision.R
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.session.SessionUiState
import com.example.raybanvision.ui.components.ShoplyButton
import com.example.raybanvision.ui.components.ShoplyButtonVariant
import com.example.raybanvision.ui.theme.Black1000
import com.example.raybanvision.ui.theme.Grey900
import com.example.raybanvision.ui.theme.Lime500
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.White300
import com.example.raybanvision.ui.theme.White500
import com.example.raybanvision.ui.theme.White700
import com.example.raybanvision.ui.theme.White1000
import com.example.raybanvision.ui.theme.Yellow400
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PhoneResultView {
    ProductInfo,
    PriceCompare,
    SavedLinks,
}

@Composable
fun MainScreen(
    uiState: SessionUiState,
    previewFrame: Bitmap?,
    onCaptureClick: () -> Unit,
    onRetakeClick: () -> Unit,
    onSearchClick: () -> Unit,
    onPriceComparisonClick: () -> Unit,
    onSendSampleResult: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var resultView by remember(uiState.searchResult) { mutableStateOf(PhoneResultView.ProductInfo) }

    // 검색 결과가 있으면 결과 화면 표시, 없으면 카메라 화면 표시
    if (uiState.awaitingProductConfirmation && uiState.searchResult != null) {
        ProductConfirmationScreen(
            result = uiState.searchResult,
            capturedPhotoFile = uiState.pendingPhotoFile,
            onAnalyzeClick = onSearchClick,
            onRetakeClick = onRetakeClick,
            modifier = modifier,
        )
    } else if (uiState.searchResult != null) {
        val result = uiState.searchResult!!
        val capturedBitmap = uiState.pendingPhotoFile?.let { file ->
            remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
        }
        when (resultView) {
            PhoneResultView.ProductInfo -> ProductInfoScreen(
                result = result,
                capturedBitmap = capturedBitmap,
                onRetake = onRetakeClick,
                onPriceCompare = {
                    onPriceComparisonClick()
                    resultView = PhoneResultView.PriceCompare
                },
                onBack = onRetakeClick,
                onSavedLinks = { resultView = PhoneResultView.SavedLinks },
            )

            PhoneResultView.PriceCompare -> PriceCompareScreen(
                result = result,
                capturedBitmap = capturedBitmap,
                onRetake = onRetakeClick,
                onProductInfo = { resultView = PhoneResultView.ProductInfo },
                onBack = { resultView = PhoneResultView.ProductInfo },
                onSavedLinks = { resultView = PhoneResultView.SavedLinks },
            )

            PhoneResultView.SavedLinks -> SavedLinksScreen(
                onBack = { resultView = PhoneResultView.PriceCompare },
                onRetake = onRetakeClick,
            )
        }
    } else {
        CameraScreen(
            uiState = uiState,
            previewFrame = previewFrame,
            onCaptureClick = onCaptureClick,
            onRetakeClick = onRetakeClick,
            onSearchClick = onSearchClick,
            onSendSampleResult = onSendSampleResult,
            onDisconnect = onDisconnect,
            modifier = modifier,
        )
    }
}

@Composable
private fun ProductConfirmationScreen(
    result: AnalysisResult,
    capturedPhotoFile: File?,
    onAnalyzeClick: () -> Unit,
    onRetakeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capturedBitmap = capturedPhotoFile?.let { file ->
        remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Black1000)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        capturedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.28f,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ShoplyDimens.Space800, vertical = ShoplyDimens.Space900),
            verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space600),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "상품 확인",
                style = ShoplyType.BodyStrong,
                color = White1000,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(ShoplyDimens.RadiusCameraFrame))
                    .background(Grey900),
                contentAlignment = Alignment.Center,
            ) {
                capturedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "촬영된 상품 사진",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ShoplyDimens.RadiusCard))
                    .background(White300)
                    .padding(ShoplyDimens.Space500),
                verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200),
            ) {
                Text("해당 상품이 맞습니까?", style = ShoplyType.Subheading, color = White1000)
                Text(
                    result.productName ?: result.originalProductName ?: result.headline,
                    style = ShoplyType.BodyBase,
                    color = White700,
                )
                listOfNotNull(result.brand ?: result.originalBrand, result.category)
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                    ?.let { Text(it, style = ShoplyType.BodySmall, color = White500) }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShoplyButton(
                    label = "다시 찍기",
                    onClick = onRetakeClick,
                    variant = ShoplyButtonVariant.TransparentWhite,
                    icon = painterResource(R.drawable.ic_camera),
                )
                ShoplyButton(
                    label = "상품 검색",
                    onClick = onAnalyzeClick,
                    modifier = Modifier.weight(1f),
                    variant = ShoplyButtonVariant.BrandSolid,
                    icon = painterResource(R.drawable.ic_dollar_sign),
                )
            }
        } 
    }
}

@Composable
private fun CameraScreen(
    uiState: SessionUiState,
    previewFrame: Bitmap?,
    onCaptureClick: () -> Unit,
    onRetakeClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSendSampleResult: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Black1000)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = ShoplyDimens.Space800, vertical = ShoplyDimens.Space600),
        verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space500),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Shoply", style = ShoplyType.Subheading, color = White1000)
            CameraStatusDot(isOn = uiState.streamState?.name == "STREAMING")
        }

        CameraArea(uiState = uiState, previewFrame = previewFrame)

        when {
            uiState.awaitingDecision -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space400),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShoplyButton(
                        label = "다시 찍기",
                        onClick = onRetakeClick,
                        variant = ShoplyButtonVariant.TransparentWhite,
                        icon = painterResource(R.drawable.ic_camera),
                    )
                    ShoplyButton(
                        label = "상품 정보",
                        onClick = onSearchClick,
                        modifier = Modifier.weight(1f),
                        variant = ShoplyButtonVariant.BrandSolid,
                        icon = painterResource(R.drawable.ic_info),
                    )
                }
            }

            uiState.isSearching -> {
                ShoplyButton(
                    label = uiState.statusMessage ?: "상품 분석 중...",
                    onClick = onRetakeClick,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ShoplyButtonVariant.TransparentWhite,
                    icon = painterResource(R.drawable.ic_camera),
                )
            }

            else -> {
                CameraCaptureButton(
                    enabled = uiState.canCapture,
                    isCapturing = uiState.isCapturing,
                    onClick = onCaptureClick,
                )
            }
        }

        Text(
            uiState.statusMessage ?: "상품을 화면 중앙에 맞춰주세요",
            style = ShoplyType.BodySmall,
            color = White500,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

    }
}

@Composable
private fun CameraArea(uiState: SessionUiState, previewFrame: Bitmap?) {
    val capturedBitmap = uiState.pendingPhotoFile?.let { file ->
        remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(518.4f / 720f)
            .clip(RoundedCornerShape(ShoplyDimens.RadiusCameraFrame))
            .background(Black1000),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 촬영 직후: 찍힌 사진을 표시
            capturedBitmap != null -> {
                Image(
                    bitmap = capturedBitmap.asImageBitmap(),
                    contentDescription = "촬영된 사진",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // 검색 중 안내 텍스트 (이미지 위 중앙)
                if (uiState.isSearching) {
                    Text(
                        uiState.statusMessage ?: "상품 검색 중...",
                        style = ShoplyType.BodyStrong,
                        color = White1000,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(ShoplyDimens.RadiusFull))
                            .background(White300)
                            .padding(horizontal = ShoplyDimens.Space500, vertical = ShoplyDimens.Space300),
                    )
                } else {
                    // 재촬영/검색 결정 안내 텍스트 (하단)
                    Text(
                        "물건 정보가 궁금하면 상품 정보를 눌러주세요",
                        style = ShoplyType.BodySmall,
                        color = White700,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(horizontal = ShoplyDimens.Space400, vertical = ShoplyDimens.Space300),
                    )
                }
            }
            // 라이브 프리뷰
            previewFrame != null -> {
                Image(
                    bitmap = previewFrame.asImageBitmap(),
                    contentDescription = "실시간 카메라 프리뷰",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // 원형 가이드라인 + 십자가 오버레이
                CameraGuideOverlay(modifier = Modifier.fillMaxSize())
                Text(
                    "물건을 가운데에 두고 촬영버튼을 눌러주세요",
                    style = ShoplyType.BodySmall,
                    color = White700,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = ShoplyDimens.Space600)
                        .fillMaxWidth()
                        .padding(horizontal = ShoplyDimens.Space400, vertical = ShoplyDimens.Space300),
                )
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space300),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                        tint = White700,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        "카메라 연결 대기 중...",
                        style = ShoplyType.BodyBase,
                        color = White700,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraStatusDot(isOn: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ShoplyDimens.Space200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ShoplyDimens.CameraStatusPhone)
                .clip(RoundedCornerShape(ShoplyDimens.RadiusFull))
                .background(if (isOn) Color(0xFF7BFF72) else White300),
        )
        Text(
            text = if (isOn) "Glasses" else "Waiting",
            style = ShoplyType.BodySmall,
            color = White500,
        )
    }
}

@Composable
private fun CameraCaptureButton(
    enabled: Boolean,
    isCapturing: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(ShoplyDimens.RadiusFull))
            .background(if (enabled) White700 else White300)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = if (isCapturing) "촬영 중" else "사진 촬영",
            tint = if (enabled) Grey900 else White500,
            modifier = Modifier.size(ShoplyDimens.IconSizeMd),
        )
    }
}

/**
 * 카메라 프리뷰 위에 표시되는 가이드 오버레이.
 * 원형 가이드라인: 물건이 들어갈 영역을 표시.
 * 십자가(+): 촬영 중심점 표시.
 */
@Composable
private fun CameraGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 원 반지름: 짧은 변의 38%
        val radius = minOf(size.width, size.height) * 0.38f
        // 십자가 팔 길이: 반지름의 15%
        val crossArmLength = radius * 0.15f
        val guideColor = Color.White.copy(alpha = 0.80f)
        val strokeWidth = 2.dp.toPx()

        // 원형 가이드라인
        drawCircle(
            color = guideColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = strokeWidth),
        )

        // 중앙 십자가 — 수평선
        drawLine(
            color = guideColor,
            start = Offset(cx - crossArmLength, cy),
            end = Offset(cx + crossArmLength, cy),
            strokeWidth = strokeWidth,
        )

        // 중앙 십자가 — 수직선
        drawLine(
            color = guideColor,
            start = Offset(cx, cy - crossArmLength),
            end = Offset(cx, cy + crossArmLength),
            strokeWidth = strokeWidth,
        )
    }
}

@Composable
private fun ResultScreen(
    result: AnalysisResult,
    isSearching: Boolean,
    showPriceComparison: Boolean,
    statusMessage: String?,
    onRetakeClick: () -> Unit,
    onPriceComparisonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 헤드라인 (상품명)
        Text(
            result.headline,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        result.topCandidate?.imageUrl?.let { imageUrl ->
            RemoteImage(
                url = imageUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }

        if (isSearching) {
            Text(
                statusMessage ?: "상품 검색 중...",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF4CAF50),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ResultSection("상품정보") {
                DetailRow("상품명", result.productName ?: result.headline)
                DetailRow("원본 상품명", result.originalProductName)
                DetailRow("브랜드", result.brand)
                DetailRow("원본 브랜드", result.originalBrand)
            }

            ResultTextSection("상품 설명", result.productDescription)
            ResultListSection("스펙", result.specifications)

            ResultListSection("\uAE0D\uC815\uD6C4\uAE30", result.positiveReviews)
            ResultListSection("\uBD80\uC815\uD6C4\uAE30", result.negativeReviews)

            if (showPriceComparison && result.candidates.isNotEmpty()) {
                ResultSection("가격 정보") {
                    result.candidates.forEachIndexed { index, candidate ->
                        PriceResultRow(
                            index = index + 1,
                            candidate = candidate,
                            onClick = candidate.linkUrl?.let { url ->
                                {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            ResultSection("추가 정보") {
                DetailRow("상태", result.rawStatus ?: result.status.name)
                DetailRow("전략", result.strategy)
                DetailRow("모델 번호", result.modelNumber)
                DetailRow("카테고리", result.category)
                DetailRow("신뢰도", result.confidence?.let { "%.2f".format(it) })
                DetailRow("평점", result.averageRating?.toString())
            }
        }

        if (!showPriceComparison && result.candidates.isNotEmpty()) {
            Button(
                onClick = onPriceComparisonClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("가격비교")
            }
        }

        OutlinedButton(
            onClick = onRetakeClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("다시 촬영")
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF151515))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9E9E9E))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEFEFEF))
    }
}

@Composable
private fun ResultTextSection(title: String, value: String?) {
    if (value.isNullOrBlank()) return
    ResultSection(title) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEFEFEF))
    }
}

@Composable
private fun ResultListSection(title: String, values: List<String>) {
    val filtered = values.filter { it.isNotBlank() }
    if (filtered.isEmpty()) return
    ResultSection(title) {
        filtered.forEach { value ->
            Text("• $value", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEFEFEF))
        }
    }
}

@Composable
private fun PriceResultRow(
    index: Int,
    candidate: com.example.raybanvision.data.ProductCandidate,
    onClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "$index. ${candidate.store ?: "판매처 없음"}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text(candidate.price, style = MaterialTheme.typography.titleMedium, color = Color(0xFF4CAF50))
        DetailRow("상품명", candidate.title)
        DetailRow("통화", candidate.currency)
        DetailRow("한국 마켓", candidate.isKoreanMarket?.let { if (it) "예" else "아니오" })
    }
}

@Composable
private fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { input -> BitmapFactory.decodeStream(input) }
            }.getOrNull()
        }
    }

    bitmapState.value?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.background(Color(0xFF151515)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun StatusCard(uiState: SessionUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("세션: ${uiState.sessionState?.name ?: "없음"}", style = MaterialTheme.typography.bodyMedium)
            Text("스트림: ${uiState.streamState?.name ?: "없음"}", style = MaterialTheme.typography.bodyMedium)
            Text("디스플레이: ${uiState.displayState?.name ?: "없음"}", style = MaterialTheme.typography.bodyMedium)
            uiState.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
