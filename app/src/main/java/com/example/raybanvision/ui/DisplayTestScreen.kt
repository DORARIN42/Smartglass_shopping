package com.example.raybanvision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.data.DISPLAY_SAMPLES
import com.example.raybanvision.data.DisplaySample

/**
 * 디스플레이 레이아웃 확인용 테스트 화면.
 *
 * LLM/쇼핑 연동과 무관하게, 상태별 샘플을 글라스로 전송해 출력 형태를 눈으로 검증한다.
 * ViewModel에 직접 의존하지 않도록 콜백으로만 연결 — 나중에 NavHost에 아래처럼 끼운다:
 *
 * ```kotlin
 * composable("displayTest") {
 *     DisplayTestScreen(
 *         isDisplayReady = sessionUiState.isDisplayReady,
 *         statusMessage  = sessionUiState.statusMessage,
 *         onSendResult   = { sessionViewModel.displayResult(it) },
 *         onBack         = { navController.popBackStack() },
 *     )
 * }
 * ```
 * (물리 글라스가 없으면 MockDeviceKit로 mock 글라스를 페어링해 세션을 띄운 뒤 사용.)
 */
@Composable
fun DisplayTestScreen(
    isDisplayReady: Boolean,
    statusMessage: String?,
    onSendResult: (AnalysisResult) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    samples: List<DisplaySample> = DISPLAY_SAMPLES,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("디스플레이 테스트", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (isDisplayReady) "글라스 준비됨 — 샘플을 전송하세요"
            else "글라스 디스플레이 미준비 (세션 연결 후 이용 가능)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(samples) { sample ->
                SampleRow(
                    sample = sample,
                    enabled = isDisplayReady,
                    onSend = { onSendResult(sample.result) },
                )
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("돌아가기")
        }
    }
}

@Composable
private fun SampleRow(
    sample: DisplaySample,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(sample.label, style = MaterialTheme.typography.titleSmall)
            Text(
                sample.result.headline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSend, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("글라스에 전송")
            }
        }
    }
}
