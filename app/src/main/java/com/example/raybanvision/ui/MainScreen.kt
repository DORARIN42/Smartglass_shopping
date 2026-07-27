package com.example.raybanvision.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.session.SessionUiState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private val ScreenBlack = Color(0xFF0F0F0F)
private val FrameBlack = Color(0xFF111111)
private val OverlayBlack = Color(0xB20C0C0D)
private val Yellow = Color(0xFFFBEB37)
private val Lime = Color(0xFFA8E937)

@Composable
fun MainScreen(
    uiState: SessionUiState,
    previewFrame: Bitmap?,
    onCaptureClick: () -> Unit,
    onRetakeClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSendSampleResult: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The feature state and callbacks are deliberately unchanged; only the visual shell follows Figma.
    if (uiState.searchResult != null) {
        val capturedBitmap = uiState.pendingPhotoFile?.let { file ->
            remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
        }
        ResultScreen(uiState.searchResult!!, capturedBitmap ?: previewFrame, onRetakeClick, modifier)
    } else {
        CameraScreen(uiState, previewFrame, onCaptureClick, onRetakeClick, onSearchClick, modifier)
    }
}

@Composable
private fun CameraScreen(
    uiState: SessionUiState,
    previewFrame: Bitmap?,
    onCaptureClick: () -> Unit,
    onRetakeClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capturedBitmap = uiState.pendingPhotoFile?.let { file ->
        remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    val capturedPhotoKey = uiState.pendingPhotoFile?.absolutePath
    var analysisComplete by remember(capturedPhotoKey) { mutableStateOf(false) }
    LaunchedEffect(capturedPhotoKey) {
        if (capturedPhotoKey == null) {
            analysisComplete = false
        } else {
            // Node 71:6795 is the required intermediate state before node 72:6928.
            analysisComplete = false
            delay(5_000)
            analysisComplete = true
        }
    }
    val isAnalyzing = uiState.awaitingDecision && !analysisComplete
    val isResult = uiState.awaitingDecision && analysisComplete
    // Figma node 55:5534 starts gray. The button is enabled only after the on-device
    // detector finds an object whose centre is inside the guide circle.
    val isObjectDetected = rememberObjectInGuide(previewFrame)
    var captureCountdown by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(captureCountdown) {
        val count = captureCountdown ?: return@LaunchedEffect
        // 3 → 2 → 1 takes 1.5 seconds in total.
        delay(500)
        if (count > 1) captureCountdown = count - 1
        else {
            captureCountdown = null
            onCaptureClick()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(ScreenBlack).padding(horizontal = 32.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Figma CameraStatus: a 16px status point above the viewfinder.
            Box(
                modifier = Modifier.size(16.dp).clip(CircleShape)
                    .background(if (isObjectDetected) Lime else Color(0xFF757575)),
            )

            CameraFrame(
                previewFrame = previewFrame,
                capturedBitmap = capturedBitmap,
                showGuide = !isResult && !isAnalyzing && !uiState.isSearching,
                isAnalyzing = isAnalyzing || uiState.isSearching,
                isResult = isResult,
                onRetakeClick = onRetakeClick,
            )
        }

        InstructionChip(
            text = when {
                captureCountdown != null -> captureCountdown.toString()
                isAnalyzing || uiState.isSearching -> "사진 분석 중..."
                isResult -> "분석이 완료되었어요!"
                else -> "원 안에 물체를 두고 촬영해주세요."
            },
        )

        when {
            isAnalyzing || uiState.isSearching -> ActionButtons(enabled = false, onSearchClick = {}, onRetakeClick = {})
            isResult -> ActionButtons(enabled = true, onSearchClick = onSearchClick, onRetakeClick = onRetakeClick)
            else -> CaptureButton(
                enabled = uiState.canCapture && isObjectDetected && !uiState.isCapturing && captureCountdown == null,
                onClick = { captureCountdown = 3 },
                highlighted = captureCountdown != null,
            )
        }
    }
}

@Composable
private fun CameraFrame(
    previewFrame: Bitmap?,
    capturedBitmap: Bitmap?,
    showGuide: Boolean,
    isAnalyzing: Boolean,
    isResult: Boolean,
    onRetakeClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(518.4f / 720f)
            .clip(RoundedCornerShape(35.dp)).background(FrameBlack),
        contentAlignment = Alignment.Center,
    ) {
        val frame = capturedBitmap ?: previewFrame
        if (frame != null) {
            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("카메라 연결 대기 중...", color = Color.White.copy(alpha = .85f), fontSize = 16.sp)
        }
        if (showGuide) CameraGuideOverlay(Modifier.fillMaxSize())
        if (isAnalyzing) {
            Box(Modifier.fillMaxSize().background(OverlayBlack), contentAlignment = Alignment.Center) {
                // The supplied Loading animation is represented by its same bright accent while analysis runs.
                Canvas(Modifier.size(88.dp)) {
                    drawCircle(Yellow, style = Stroke(width = 7.dp.toPx()))
                    drawCircle(Lime, radius = size.minDimension * .29f)
                }
            }
        }
        if (isResult) ResultOverlay(onRetakeClick)
    }
}

@Composable
private fun ResultOverlay(onRetakeClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(OverlayBlack).padding(horizontal = 48.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("이 제품이 맞나요?", color = Color(0xFFD9D9D9), fontSize = 18.sp)
        Text("민티아 드라이하드", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        GlassButton("다시 찍기", Icons.Outlined.CameraAlt, onRetakeClick, transparent = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InstructionChip(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .10f)).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun CaptureButton(enabled: Boolean, onClick: () -> Unit, highlighted: Boolean = false) {
    Box(
        modifier = Modifier.size(88.dp).clip(CircleShape)
            .background(if (enabled || highlighted) Brush.linearGradient(listOf(Lime, Yellow)) else Brush.linearGradient(listOf(Color(0xFFB3B3B3), Color(0xFFB3B3B3))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.CameraAlt, null, tint = Color(0xFF1E1E1E), modifier = Modifier.size(32.dp)) }
}

@Composable
private fun ActionButtons(enabled: Boolean, onSearchClick: () -> Unit, onRetakeClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassButton("가격 비교", Icons.Outlined.MonetizationOn, onRetakeClick, enabled = enabled, modifier = Modifier.weight(1f))
        GlassButton("상품 정보", Icons.Outlined.Info, onSearchClick, enabled = enabled, primary = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GlassButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    transparent: Boolean = false,
) {
    val fill = when {
        // Figma 71:6795: while analysing, both bottom actions use the same disabled glass fill.
        !enabled -> Brush.linearGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .10f)))
        transparent -> Brush.linearGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .03f)))
        primary -> Brush.linearGradient(listOf(Lime, Yellow))
        else -> Brush.linearGradient(listOf(Color(0xFF2C2C2C), Color(0xFF2C2C2C)))
    }
    val color = when {
        !enabled -> Color.White.copy(alpha = .40f)
        primary -> Color(0xFF1E1E1E)
        else -> Color.White.copy(alpha = .90f)
    }
    Row(
        modifier = modifier.height(60.dp).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CameraGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color.White.copy(alpha = .8f), size.minDimension * .24f, Offset(size.width / 2, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/** Uses ML Kit's stream detector; visual activation has no effect on the existing capture/session pipeline. */
@Composable
private fun rememberObjectInGuide(previewFrame: Bitmap?): Boolean {
    val detector = remember {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .build(),
        )
    }
    var detectedInGuide by remember { mutableStateOf(false) }
    val detectionInFlight = remember { AtomicBoolean(false) }

    DisposableEffect(detector) {
        onDispose { detector.close() }
    }
    LaunchedEffect(previewFrame) {
        val frame = previewFrame ?: run {
            detectedInGuide = false
            return@LaunchedEffect
        }
        if (!detectionInFlight.compareAndSet(false, true)) return@LaunchedEffect
        detector.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { objects ->
                detectedInGuide = objects.any { isObjectCentreInGuide(it.boundingBox, frame.width, frame.height) }
                detectionInFlight.set(false)
            }
            .addOnFailureListener {
                detectedInGuide = false
                detectionInFlight.set(false)
            }
    }
    return detectedInGuide
}

private fun isObjectCentreInGuide(bounds: Rect, frameWidth: Int, frameHeight: Int): Boolean {
    val centerX = frameWidth / 2f
    val centerY = frameHeight / 2f
    // The Figma guide is 250px within a 518.4px-wide camera frame: radius = 24.1% of width.
    val guideRadius = frameWidth * 0.241f
    val objectX = bounds.exactCenterX()
    val objectY = bounds.exactCenterY()
    val dx = objectX - centerX
    val dy = objectY - centerY
    return dx * dx + dy * dy <= guideRadius * guideRadius
}

@Composable
private fun ResultScreen(
    result: AnalysisResult,
    backgroundFrame: Bitmap?,
    onRetakeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val buyables = result.candidates.filter { it.linkUrl != null }
    // Exact display copy from Figma node 82:8583. Existing candidate URLs are
    // still used when a user taps a corresponding "링크 저장" control.
    val figmaPrices = listOf(
        "9,900" to "무신사",
        "10,400" to "쿠팡",
        "10,900" to "이마트",
    )
    // The Figma frame is 600 × 1300. Scale every measurement as one surface so
    // the product page keeps its Figma proportions rather than overflowing on a phone.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color(0xFF0C0C0D))) {
        val scale = minOf(maxWidth / 600.dp, maxHeight / 1300.dp)
        // Node 82:8583 keeps the captured camera view visible at 30% behind the glass panels.
        backgroundFrame?.let { frame ->
            Image(
                frame.asImageBitmap(), null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = .30f,
            )
        }
        // Figma 82:8583: content begins at y=230 and occupies 800px in its
        // 600 × 1300 canvas. This leaves the same camera-image breathing room
        // above the cards and below the actions on every phone size.
        Column(
            modifier = Modifier.fillMaxWidth().height(800.dp * scale)
                .align(Alignment.TopCenter).offset(y = 230.dp * scale)
                .padding(horizontal = 32.dp * scale),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp * scale),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp * scale),
            ) {
                // assets/UI/Resources/Components/ProductName.json
                // ColorButtonTransparentBrandDefault + EffectStyleButtonSelected.
                ProductNameCard("민티아 드라이하드", scale)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                    figmaPrices.forEachIndexed { index, (price, vendor) ->
                        PriceInformationCard(
                            price = price,
                            vendor = vendor,
                            focused = false,
                            onSaveLink = {
                                buyables.getOrNull(index)?.linkUrl?.let { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            scale = scale,
                        )
                    }
                }
            }

            ResultCompletionChip(scale)

            // The Figma action row is retained as a visual row. The product-information
            // button represents the current screen, so it intentionally has no second action.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp * scale),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProductActionButton(
                    label = "다시 찍기",
                    icon = Icons.Outlined.CameraAlt,
                    onClick = onRetakeClick,
                    primary = false,
                    scale = scale,
                )
                ProductActionButton(
                    label = "상품 정보",
                    icon = Icons.Outlined.Info,
                    onClick = {},
                    primary = true,
                    modifier = Modifier.weight(1f),
                    scale = scale,
                )
            }
        }
    }
}

@Composable
private fun ProductNameCard(title: String, scale: Float) {
    val shape = RoundedCornerShape(24.dp * scale)
    Box(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .border(2.dp * scale, Color.White.copy(alpha = .50f), shape)
            .background(Brush.linearGradient(listOf(Yellow.copy(alpha = .50f), Yellow.copy(alpha = .05f))))
            .background(Brush.linearGradient(listOf(Lime.copy(alpha = .50f), Lime.copy(alpha = .05f))))
            .padding(24.dp * scale),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp * scale)) {
            Text(title, color = Color.White, fontSize = 32.sp * scale, lineHeight = 38.sp * scale, fontWeight = FontWeight.Medium)
            Text("오리온, 324g", color = Color.White.copy(alpha = .90f), fontSize = 24.sp * scale, lineHeight = 29.sp * scale)
        }
    }
}

@Composable
private fun PriceInformationCard(
    price: String,
    vendor: String,
    focused: Boolean,
    onSaveLink: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float,
) {
    val shape = RoundedCornerShape(24.dp * scale)
    val headingColor = if (focused) Color.White else Color.White.copy(alpha = .85f)
    Column(
        modifier = modifier.clip(shape)
            .border(2.dp * scale, if (focused) Color.White else Color.White.copy(alpha = .50f), shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .03f))))
            .padding(top = 16.dp * scale, start = 12.dp * scale, end = 12.dp * scale, bottom = 12.dp * scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp * scale),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp * scale)) {
            Text("₩ $price", color = headingColor, fontSize = 24.sp * scale, lineHeight = 34.sp * scale, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(vendor, color = headingColor, fontSize = 24.sp * scale, lineHeight = 34.sp * scale, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFFB3B3B3))
                .clickable(onClick = onSaveLink).padding(horizontal = 16.dp * scale, vertical = 12.dp * scale),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, null, tint = Color(0xFF1E1E1E), modifier = Modifier.size(24.dp * scale))
            Spacer(Modifier.width(8.dp * scale))
            Text("링크 저장", color = Color(0xFFD9D9D9), fontSize = 20.sp * scale, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ProductActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    scale: Float,
) {
    val shape = CircleShape
    val fill = if (primary) {
        Brush.linearGradient(listOf(Yellow, Yellow.copy(alpha = .10f), Lime.copy(alpha = .10f), Color(0xFFE6E6E6)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .03f)))
    }
    val textColor = if (primary) Color(0xFF1E1E1E) else Color.White
    Row(
        modifier = modifier.clip(shape)
            .border(if (primary) 0.dp else 2.dp * scale, if (primary) Color.Transparent else Color.White.copy(alpha = .50f), shape)
            .background(fill).clickable(onClick = onClick).padding(horizontal = 32.dp * scale, vertical = 24.dp * scale),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = textColor, modifier = Modifier.size(24.dp * scale))
        Spacer(Modifier.width(8.dp * scale))
        Text(label, color = textColor, fontSize = 24.sp * scale, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ResultCompletionChip(scale: Float) {
    Text(
        "가격 검색이 완료되었어요!",
        color = Color.White,
        fontSize = 20.sp * scale,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .10f))
            .padding(horizontal = 16.dp * scale, vertical = 12.dp * scale),
    )
}
