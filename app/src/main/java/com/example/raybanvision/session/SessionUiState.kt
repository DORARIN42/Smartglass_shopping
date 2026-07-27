package com.example.raybanvision.session

import com.example.raybanvision.data.AnalysisResult
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.display.types.DisplayState
import java.io.File

data class SessionUiState(
    val sessionState: DeviceSessionState? = null,
    val streamState: StreamState? = null,
    val displayState: DisplayState? = null,
    val isCapturing: Boolean = false,
    // 촬영 직후 사용자의 재촬영/검색 결정을 기다리는 미리보기 사진. null 이면 라이브 프리뷰 상태.
    val pendingPhotoFile: File? = null,
    // 검색(LLM 전송) 진행 중 여부.
    val isSearching: Boolean = false,
    val awaitingProductConfirmation: Boolean = false,
    // 검색 결과 (폰 화면에 표시용).
    val searchResult: AnalysisResult? = null,
    val statusMessage: String? = null,
) {
    val canCapture: Boolean
        get() = streamState == StreamState.STREAMING && !isCapturing && pendingPhotoFile == null

    // 촬영본이 있고 아직 검색 전이면 재촬영/검색 선택 단계.
    val awaitingDecision: Boolean
        get() = pendingPhotoFile != null && !isSearching && !awaitingProductConfirmation

    val isDisplayReady: Boolean
        get() = displayState == DisplayState.STARTED
}
