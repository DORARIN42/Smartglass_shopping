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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.session.SessionUiState

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
    // 검색 결과가 있으면 결과 화면 표시, 없으면 카메라 화면 표시
    if (uiState.searchResult != null) {
        ResultScreen(
            result = uiState.searchResult!!,
            onRetakeClick = onRetakeClick,
            modifier = modifier,
        )
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
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RayBan Vision", style = MaterialTheme.typography.headlineMedium)

        // 세션/스트림/디스플레이 상태 카드
        StatusCard(uiState)

        // ── (임시) 실시간 카메라 프리뷰 / 촬영 결과 ────────────────────────
        // 촬영한 사진이 있으면 그 사진을, 없으면 라이브 프리뷰를 보여준다.
        CameraArea(uiState = uiState, previewFrame = previewFrame)

        // ── 촬영 / 재촬영·검색 ─────────────────────────────────────────────
        when {
            // 촬영 후 결정 단계: 재촬영 or 검색
            uiState.awaitingDecision -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onRetakeClick, modifier = Modifier.weight(1f)) {
                        Text("재촬영")
                    }
                    Button(onClick = onSearchClick, modifier = Modifier.weight(1f)) {
                        Text("검색")
                    }
                }
            }
            // 검색 진행 중: 재촬영으로만 빠져나갈 수 있음
            uiState.isSearching -> {
                OutlinedButton(onClick = onRetakeClick, modifier = Modifier.fillMaxWidth()) {
                    Text("검색 중... (재촬영하려면 탭)")
                }
            }
            // 라이브: 촬영
            else -> {
                Button(
                    onClick = onCaptureClick,
                    enabled = uiState.canCapture,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uiState.isCapturing) "촬영 중..." else "사진 촬영")
                }
            }
        }

        HorizontalDivider()

        // ── 디스플레이 테스트 ──────────────────────────────────────────────
        Text("디스플레이 테스트 (LLM 연동 전)", style = MaterialTheme.typography.titleSmall)
        Text(
            "샘플 텍스트·이미지·링크를 글라스에 전송해서 표시 결과를 확인합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onSendSampleResult,
            enabled = uiState.isDisplayReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("샘플 결과 글라스에 전송")
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("연결 해제")
        }
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
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 촬영 직후: 찍힌 사진을 표시
            capturedBitmap != null -> {
                Image(
                    bitmap = capturedBitmap.asImageBitmap(),
                    contentDescription = "촬영된 사진",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                // 검색 중 안내 텍스트 (이미지 위 중앙)
                if (uiState.isSearching) {
                    Text(
                        "AI 검색 중...",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    // 재촬영/검색 결정 안내 텍스트 (하단)
                    Text(
                        "물건 정보가 궁금하면 '검색'을 누르고,\n다시 촬영을 해야하면 '재촬영'을 눌러주세요",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xAA000000))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            // 라이브 프리뷰
            previewFrame != null -> {
                Image(
                    bitmap = previewFrame.asImageBitmap(),
                    contentDescription = "실시간 카메라 프리뷰",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                // 원형 가이드라인 + 십자가 오버레이
                CameraGuideOverlay(modifier = Modifier.fillMaxSize())
                // LIVE 배지 (좌상단)
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x88CC0000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                // 촬영 전 안내 텍스트 (원 위)
                Text(
                    "물건을 가운데에 두고 촬영버튼을 눌러주세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 50.dp)
                        .fillMaxWidth()
                        .background(Color(0xAA000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            else -> {
                Text(
                    "카메라 연결 대기 중...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }

    uiState.pendingPhotoFile?.let { file ->
        Text(
            "저장 경로: ${file.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onRetakeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        // 1번: 상품 설명 + 후기 (scrollable) - 중간에 배치
        result.message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
                textAlign = TextAlign.Left,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        }

        // 2번: 구매처 3칸 — 각 칸에 사이트명 + 가격, 누르면 해당 사이트가 열림
        val context = LocalContext.current
        val buyables = result.candidates.filter { it.linkUrl != null }
        if (buyables.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buyables.forEach { candidate ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E1E1E))
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(candidate.linkUrl)),
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        candidate.store?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFBBBBBB),
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            candidate.price,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // 재촬영 버튼
        OutlinedButton(
            onClick = onRetakeClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("다시 촬영")
        }
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
