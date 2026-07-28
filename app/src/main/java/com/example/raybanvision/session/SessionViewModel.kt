package com.example.raybanvision.session

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.raybanvision.BuildConfig
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.data.ProductCandidate
import com.example.raybanvision.data.ResultStatus
import com.example.raybanvision.network.mergeWith
import com.example.raybanvision.network.ShoppingApiClient
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "SessionViewModel"
        private const val CAPTURE_REVIEW_DELAY_MS = 1200L
        private const val DISPLAY_TEXT_LIMIT = 90
        private const val PREVIEW_FRAME_INTERVAL_MS = 100L
        private const val RAW_PREVIEW_FRAME_RATE = 15
    }

    private data class RawPreviewFrame(
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
        val isCompressed: Boolean,
        val isCodecConfig: Boolean,
        val capturedAtMs: Long,
    )

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    // (임시) 목 디바이스 테스트용 실시간 카메라 프리뷰. 스트림 프레임을 Bitmap 으로 변환해 방출.
    // 실제 글라스 운용에서는 프리뷰가 불필요하므로 debug/테스트 편의 기능이다.
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var display: Display? = null
    private var previewJob: kotlinx.coroutines.Job? = null
    private var analysisMessageJob: kotlinx.coroutines.Job? = null
    private var analysisJob: kotlinx.coroutines.Job? = null
    private var currentAnalysisJobId: String? = null
    private var latestAnalysisResult: AnalysisResult? = null
    @Volatile
    private var latestRawPreviewFrame: RawPreviewFrame? = null
    private val shoppingApiClient = ShoppingApiClient(BuildConfig.ANALYSIS_BASE_URL)

    // ─── LLM 팀 인터페이스 ────────────────────────────────────────────────
    // 사용자가 [검색]을 눌러 LLM 분석을 요청한 사진. LLM 팀은 이 StateFlow를 collect한다.
    // 촬영만 한 상태(재촬영/검색 결정 전)에서는 방출하지 않고, [검색] 시에만 새 값이 방출된다.
    private val _capturedPhotoFile = MutableStateFlow<File?>(null)
    val capturedPhotoFile: StateFlow<File?> = _capturedPhotoFile.asStateFlow()

    // LLM/쇼핑 팀이 분석 완료 후 이 함수를 호출하면 글라스 디스플레이에 결과를 표시.
    // 글라스에는 요약(대표 상품 + 최저가)만 보여주고, 상세는 폰에서 확인하도록 유도한다.
    fun displayResult(result: AnalysisResult) {
        val currentDisplay = display ?: run {
            Log.w(TAG, "displayResult called but display is not ready")
            _uiState.update { it.copy(statusMessage = "디스플레이 미준비 — 결과 표시 실패") }
            return
        }
        if (result.status == ResultStatus.MATCHED || result.status == ResultStatus.UNCERTAIN) {
            displayDetailedResult(currentDisplay, result)
            return
        }
        _uiState.update { it.copy(statusMessage = "결과 표시: ${result.status.name}") }

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                // sendContent는 정확히 하나의 루트 뷰(flexBox)만 허용한다.
                when (result.status) {
                    ResultStatus.MATCHED, ResultStatus.UNCERTAIN -> {
                        val top: ProductCandidate? = result.topCandidate
                        flexBox(direction = Direction.COLUMN, gap = 12) {
                            flexBox(padding = 24, background = FlexBoxBackground.CARD) {
                                top?.imageUrl?.let { url -> image(uri = url, sizePreset = ImageSize.FILL) }
                                text(result.headline, style = TextStyle.BODY)
                                if (result.status == ResultStatus.UNCERTAIN && result.candidates.size > 1) {
                                    text(
                                        "후보 ${result.candidates.size}개 · 폰에서 선택",
                                        style = TextStyle.BODY,
                                        color = TextColor.SECONDARY,
                                    )
                                }
                            }
                            // 구매처 후보를 가로로 균등한 3칸으로 나눠 노출 (링크 있는 것만).
                            // 각 칸: 사이트명 + 가격, 칸 전체를 누르면 폰에서 해당 사이트가 열린다.
                            // 세 칸 모두 동일한 스타일(flexGrow=1f로 폭 균등).
                            flexBox(direction = Direction.ROW, gap = 8) {
                                result.candidates
                                    .filter { it.linkUrl != null }
                                    .forEach { candidate ->
                                        flexBox(
                                            direction = Direction.COLUMN,
                                            gap = 4,
                                            padding = 12,
                                            background = FlexBoxBackground.CARD,
                                            flexGrow = 1f,
                                            onClick = { openUrlOnPhone(candidate.linkUrl!!) },
                                        ) {
                                            candidate.store?.let {
                                                text(it, style = TextStyle.BODY, color = TextColor.SECONDARY)
                                            }
                                            text(candidate.price, style = TextStyle.HEADING)
                                        }
                                    }
                            }
                        }
                    }

                    ResultStatus.RETRY_REQUIRED -> flexBox(
                        direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD,
                    ) {
                        text("다시 촬영해주세요", style = TextStyle.HEADING)
                        text(
                            result.message ?: "상품이 잘 보이도록 밝은 곳에서 가까이 촬영해주세요.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }

                    ResultStatus.ERROR -> flexBox(
                        direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD,
                    ) {
                        text("오류가 발생했어요", style = TextStyle.HEADING)
                        text(
                            result.message ?: "잠시 후 다시 시도해주세요.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayResult sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "디스플레이 전송 실패: ${error.description}") }
            }
        }
    }
    // ─────────────────────────────────────────────────────────────────────

    private fun displayDetailedResult(currentDisplay: Display, result: AnalysisResult) {
        _uiState.update { it.copy(statusMessage = "결과 표시: ${result.status.name}") }

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8) {
                    text(result.headline, style = TextStyle.HEADING)
                    result.topCandidate?.imageUrl?.let { url ->
                        image(uri = url, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM)
                    }
                    flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                        text("상품정보", style = TextStyle.HEADING)
                        text("상품명: ${result.productName ?: result.headline}", style = TextStyle.BODY)
                        result.originalProductName?.let { text("원본 상품명: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.brand?.let { text("브랜드: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.originalBrand?.let { text("원본 브랜드: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    result.productDescription?.let {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("상품 설명", style = TextStyle.HEADING)
                            text(it, style = TextStyle.BODY)
                        }
                    }
                    if (result.specifications.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("스펙", style = TextStyle.HEADING)
                            result.specifications.forEach {
                                text("• $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.positiveReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("긍정 리뷰", style = TextStyle.HEADING)
                            result.positiveReviews.forEach {
                                text("• $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.negativeReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("부정 리뷰", style = TextStyle.HEADING)
                            result.negativeReviews.forEach {
                                text("• $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (false && result.candidates.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 8, padding = 14, background = FlexBoxBackground.CARD) {
                            text("가격 정보", style = TextStyle.HEADING)
                            result.candidates.forEachIndexed { index, candidate ->
                                flexBox(
                                    direction = Direction.COLUMN,
                                    gap = 4,
                                    padding = 10,
                                    background = FlexBoxBackground.CARD,
                                    onClick = candidate.linkUrl?.let { url -> { openUrlOnPhone(url) } },
                                ) {
                                    text("${index + 1}. ${candidate.store ?: "판매처 없음"}", style = TextStyle.BODY)
                                    text(candidate.price, style = TextStyle.HEADING)
                                    candidate.title?.let { text("상품명: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                    candidate.currency?.let { text("통화: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                    candidate.isKoreanMarket?.let { text("한국 마켓: ${if (it) "예" else "아니오"}", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                }
                            }
                        }
                    }
                    flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                        text("추가 정보", style = TextStyle.HEADING)
                        text("상태: ${result.rawStatus ?: result.status.name}", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        result.strategy?.let { text("전략: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.modelNumber?.let { text("모델 번호: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.category?.let { text("카테고리: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.confidence?.let { text("신뢰도: %.2f".format(it), style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.averageRating?.let { text("평점: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    if (result.candidates.isNotEmpty()) {
                        button(
                            label = "\uAC00\uACA9\uBE44\uAD50",
                            style = ButtonStyle.PRIMARY,
                            onClick = { showPriceComparison(result) },
                        )
                    }
                    button(
                        label = "다시 촬영",
                        style = ButtonStyle.PRIMARY,
                        onClick = { retakePhoto() },
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayDetailedResult sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "디스플레이 전송 실패: ${error.description}") }
            }
        }
    }

    fun showPriceComparison() {
        val result = _uiState.value.searchResult ?: latestAnalysisResult ?: return
        showPriceComparison(result)
    }

    private fun showPriceComparison(result: AnalysisResult) {
        _uiState.update { it.copy(showPriceComparison = true) }
        displayPriceComparison(result)
    }

    private fun displayPriceComparison(result: AnalysisResult) {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8) {
                    text("\uAC00\uACA9\uBE44\uAD50", style = TextStyle.HEADING)
                    result.candidates.forEachIndexed { index, candidate ->
                        val linkUrl = candidate.linkUrl
                        flexBox(
                            direction = Direction.COLUMN,
                            gap = 4,
                            padding = 10,
                            background = FlexBoxBackground.CARD,
                            onClick = linkUrl?.let { url -> { openUrlOnPhone(url) } },
                        ) {
                            text("${index + 1}. ${candidate.store ?: "판매처 없음"}", style = TextStyle.BODY)
                            text(candidate.price, style = TextStyle.HEADING)
                            candidate.title?.let { text("상품명: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                            candidate.currency?.let { text("통화: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                            candidate.isKoreanMarket?.let {
                                text("한국 마켓: ${if (it) "예" else "아니오"}", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    button(
                        label = "\uB2E4\uC2DC \uCD2C\uC601",
                        style = ButtonStyle.PRIMARY,
                        onClick = { retakePhoto() },
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayPriceComparison sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "디스플레이 가격비교 전송 실패: ${error.description}") }
            }
        }
    }

    private fun displayProductConfirmation(result: AnalysisResult) {
        stopAnalysisMessages()
        val currentDisplay = display ?: return
        val productName = result.productName ?: result.originalProductName ?: result.headline
        val brand = result.brand ?: result.originalBrand
        val capturedPhotoUri = capturedPhotoDisplayUri(result.imageUrl)

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 12) {
                    text("\uC774 \uC0C1\uD488\uC774 \uB9DE\uB098\uC694?", style = TextStyle.HEADING)
                    capturedPhotoUri?.let { uri ->
                        image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    }
                    flexBox(direction = Direction.COLUMN, gap = 4, padding = 12, background = FlexBoxBackground.CARD) {
                        text(productName, style = TextStyle.BODY)
                        brand?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.category?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    flexBox(direction = Direction.ROW, gap = 8) {
                        button(
                            label = "\uC7AC\uCD2C\uC601",
                            style = ButtonStyle.PRIMARY,
                            onClick = { retakePhoto() },
                        )
                        button(
                            label = "\uBD84\uC11D",
                            style = ButtonStyle.PRIMARY,
                            onClick = { submitForSearch() },
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayProductConfirmation with image failed: ${error.description}")
                currentDisplay.sendContent {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 12) {
                        text("\uC774 \uC0C1\uD488\uC774 \uB9DE\uB098\uC694?", style = TextStyle.HEADING)
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 12, background = FlexBoxBackground.CARD) {
                            text(productName, style = TextStyle.BODY)
                            brand?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                            result.category?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        }
                        flexBox(direction = Direction.ROW, gap = 8) {
                            button(
                                label = "\uC7AC\uCD2C\uC601",
                                style = ButtonStyle.PRIMARY,
                                onClick = { retakePhoto() },
                            )
                            button(
                                label = "\uBD84\uC11D",
                                style = ButtonStyle.PRIMARY,
                                onClick = { submitForSearch() },
                            )
                        }
                    }
                }
            }
        }
        return

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 12) {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                        text("이 상품이 맞나요?", style = TextStyle.HEADING)
                        capturedPhotoUri?.let { uri ->
                            image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                        }
                        text(productName, style = TextStyle.BODY)
                        brand?.let {
                            text(it, style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        result.category?.let {
                            text(it, style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        text("폰에서 확인하거나 아래 버튼을 선택하세요", style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                    flexBox(direction = Direction.ROW, gap = 8) {
                        button(
                            label = "재촬영",
                            style = ButtonStyle.PRIMARY,
                            onClick = { retakePhoto() },
                        )
                        button(
                            label = "분석",
                            style = ButtonStyle.PRIMARY,
                            onClick = { submitForSearch() },
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayProductConfirmation sendContent failed: ${error.description}")
                currentDisplay.sendContent {
                    flexBox(direction = Direction.COLUMN, gap = 12) {
                        flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                            text("\uC774 \uC0C1\uD488\uC774 \uB9DE\uB098\uC694?", style = TextStyle.HEADING)
                            text(productName, style = TextStyle.BODY)
                            brand?.let {
                                text(it, style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                            result.category?.let {
                                text(it, style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                            text("\uD3F0\uC5D0\uC11C \uD655\uC778\uD558\uACE0 \uC544\uB798 \uBC84\uD2BC\uC744 \uC120\uD0DD\uD574\uC8FC\uC138\uC694.", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        flexBox(direction = Direction.ROW, gap = 8) {
                            button(
                                label = "\uC7AC\uCD2C\uC601",
                                style = ButtonStyle.PRIMARY,
                                onClick = { retakePhoto() },
                            )
                            button(
                                label = "\uBD84\uC11D",
                                style = ButtonStyle.PRIMARY,
                                onClick = { submitForSearch() },
                            )
                        }
                    }
                }
                _uiState.update { it.copy(statusMessage = "디스플레이 확인 화면 전송 실패: ${error.description}") }
            }
        }
    }

    fun startSession(deviceId: DeviceIdentifier) {
        if (session != null || stream != null || display != null) {
            Log.i(TAG, "Clearing existing session before starting a new one.")
            stopSession()
        }
        _uiState.update { it.copy(statusMessage = "세션 연결 중...") }

        Wearables.createSession(SpecificDeviceSelector(deviceId))
            .onSuccess { newSession ->
                session = newSession

                viewModelScope.launch {
                    newSession.errors.collect { error -> handleSessionError(error) }
                }
                viewModelScope.launch {
                    newSession.state.collect { state ->
                        _uiState.update { it.copy(sessionState = state) }
                        when (state) {
                            DeviceSessionState.STARTED -> attachCapabilities()
                            DeviceSessionState.STOPPED -> onSessionStopped()
                            else -> Unit
                        }
                    }
                }

                newSession.start()
            }
            .onFailure { error, _ ->
                Log.e(TAG, "Session create failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "연결 실패: ${error.description}") }
                if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                    Wearables.openDATGlassesAppUpdate(getApplication())
                }
            }
    }

    private fun attachCapabilities() {
        val currentSession = session ?: return

        // 스트림 연결. capturePhoto 용도 + (임시) 실시간 프리뷰용.
        // compressVideo = false 로 raw 프레임을 받아 Bitmap 으로 바로 변환한다(H.265 디코딩 불필요).
        currentSession.addStream(
            StreamConfiguration(
                videoQuality = VideoQuality.LOW,
                frameRate = RAW_PREVIEW_FRAME_RATE,
                compressVideo = false,
            ),
        )
            .onSuccess { addedStream ->
                stream = addedStream
                viewModelScope.launch {
                    addedStream.state.collect { state ->
                        Log.i(TAG, "Stream state changed: $state")
                        _uiState.update { it.copy(streamState = state) }
                    }
                }
                startPreview()
                addedStream.start()
            }
            .onFailure { error, _ -> Log.e(TAG, "Stream attach failed: ${error.description}") }

        // 디스플레이 연결
        currentSession.addDisplay()
            .onSuccess { addedDisplay ->
                display = addedDisplay
                viewModelScope.launch {
                    addedDisplay.state.collect { state ->
                        _uiState.update { it.copy(displayState = state) }
                        if (state == DisplayState.STARTED) showReadyScreen()
                    }
                }
            }
            .onFailure { error, _ -> Log.e(TAG, "Display attach failed: ${error.description}") }
    }

    // 실시간 프리뷰 수집 시작/중단. capturePhoto 는 videoStream 과 버퍼를 공유하므로
    // 촬영 중에는 프리뷰 수집을 중단해야 캡처 프레임이 깨지지 않는다.
    private fun startPreview() {
        val currentStream = stream ?: return
        if (previewJob?.isActive == true) return
        // 프레임 → Bitmap. conflate 로 처리 못 따라가면 최신 프레임만 유지.
        // 이전 Bitmap 을 recycle 하면 Compose 렌더링 중 크래시 위험이 있어 GC 에 맡긴다.
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPreviewAt = 0L
            var loggedFirstRawFrame = false
            currentStream.videoStream.conflate().collect { frame ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastPreviewAt < PREVIEW_FRAME_INTERVAL_MS) return@collect
                lastPreviewAt = now
                latestRawPreviewFrame = frame.toRawPreviewFrame(now)
                if (!loggedFirstRawFrame) {
                    loggedFirstRawFrame = true
                    Log.i(
                        TAG,
                        "Raw preview stream active: ${frame.width}x${frame.height}, " +
                            "compressed=${frame.isCompressed}, codecConfig=${frame.isCodecConfig}, " +
                            "bytes=${latestRawPreviewFrame?.bytes?.size ?: 0}, " +
                            "format=${latestRawPreviewFrame?.formatHint()}",
                    )
                }
                videoFrameToBitmap(frame)?.let { bmp -> _previewFrame.value = bmp }
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    fun capturePhoto() {
        val currentStream = stream ?: run {
            Log.w(TAG, "Capture ignored: stream is not attached")
            _uiState.update { it.copy(statusMessage = "Camera stream is not ready.") }
            return
        }
        val previewBitmap = _previewFrame.value
        val rawPreviewFrame = latestRawPreviewFrame
        val state = _uiState.value
        if (!state.isCapturing && state.pendingPhotoFile == null && previewBitmap != null) {
            Log.i(
                TAG,
                "Capturing from latest raw preview frame. streamState=${state.streamState}, " +
                    "rawBytes=${rawPreviewFrame?.bytes?.size ?: 0}, rawFormat=${rawPreviewFrame?.formatHint()}",
            )
            savePreviewFrameAsCapture(previewBitmap, rawPreviewFrame)
            if (state.streamState == StreamState.STOPPED) currentStream.start()
            return
        }
        if (!_uiState.value.canCapture) {
            if (
                _uiState.value.streamState == StreamState.STOPPED &&
                !_uiState.value.isCapturing &&
                _uiState.value.pendingPhotoFile == null
            ) {
                val fallbackBitmap = previewBitmap ?: createStoppedStreamPlaceholder()
                Log.i(TAG, "Capturing with fallback image because stream is stopped. hasPreview=${previewBitmap != null}")
                savePreviewFrameAsCapture(fallbackBitmap, rawPreviewFrame)
                currentStream.start()
                return
            }
            if (_uiState.value.streamState == StreamState.STOPPED) {
                Log.i(TAG, "Capture requested while stream is stopped; restarting stream.")
                _uiState.update { it.copy(statusMessage = "Camera stream is restarting. Try again in a moment.") }
                currentStream.start()
                return
            }
            Log.w(
                TAG,
                "Capture ignored: streamState=${_uiState.value.streamState}, " +
                    "isCapturing=${_uiState.value.isCapturing}, pendingPhoto=${_uiState.value.pendingPhotoFile != null}",
            )
            _uiState.update { it.copy(statusMessage = "Capture is not ready yet. Try again in a moment.") }
            return
        }

        // 캡처 프레임 손상 방지: 촬영 동안 프리뷰(videoStream 수집) 중단.
        stopPreview()
        _uiState.update { it.copy(isCapturing = true, statusMessage = "촬영 중...") }

        // 목 디바이스에서는 capturePhoto()가 스트리밍 버퍼와 경합해 찢김/부분 프레임이 발생한다.
        // debug 빌드(목)에서는 이미 깨끗한 현재 프리뷰 프레임을 그대로 저장한다.
        // 실제 글라스(release)는 고화질 capturePhoto()를 사용한다.
        if (BuildConfig.DEBUG && previewBitmap != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val file = saveBitmapToCache(previewBitmap)
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        pendingPhotoFile = file,
                        statusMessage = if (file != null) "상품명 확인 중..." else "파일 저장 실패",
                    )
                }
                if (file != null) {
                    showCapturedPhoto(file)
                    delay(CAPTURE_REVIEW_DELAY_MS)
                    identifyProduct(file)
                } else {
                    startPreview()
                }
            }
            return
        }

        Log.w(TAG, "Capture ignored: no raw preview frame yet. streamState=${state.streamState}")
        _uiState.update { it.copy(statusMessage = "Waiting for the first camera frame. Try again in a moment.") }
        return

        viewModelScope.launch {
            currentStream.capturePhoto()
                .onSuccess { photoData ->
                    val file = savePhotoToCache(photoData)
                    // 촬영만 하고 미리보기로 표시. LLM 전송/로딩은 [검색]을 눌러야 진행된다.
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            pendingPhotoFile = file,
                            statusMessage = if (file != null) "상품명 확인 중..." else "파일 저장 실패",
                        )
                    }
                    if (file != null) {
                        showCapturedPhoto(file)
                        delay(CAPTURE_REVIEW_DELAY_MS)
                        identifyProduct(file)
                    } else {
                        startPreview()
                    }
                }
                .onFailure { error, _ ->
                    Log.e(TAG, "Photo capture failed: ${error.description}")
                    _uiState.update { it.copy(isCapturing = false, statusMessage = "촬영 실패: ${error.description}") }
                    startPreview() // 라이브 유지 → 프리뷰 재개
                }
        }
    }

    // [재촬영] 미리보기 사진을 버리고(캐시 파일 삭제) 라이브 프리뷰로 돌아간다.
    private fun savePreviewFrameAsCapture(previewBitmap: Bitmap, rawPreviewFrame: RawPreviewFrame?) {
        stopPreview()
        _uiState.update { it.copy(isCapturing = true, statusMessage = "Capturing from last preview frame...") }
        viewModelScope.launch(Dispatchers.IO) {
            val rawFile = rawPreviewFrame?.let { saveRawFrameToCache(it) }
            val file = saveBitmapToCache(previewBitmap)
            if (rawFile != null) {
                Log.i(TAG, "Saved raw preview capture: ${rawFile.absolutePath}")
            } else {
                Log.w(TAG, "No raw preview frame was available to save with this capture.")
            }
            _uiState.update {
                it.copy(
                    isCapturing = false,
                    pendingPhotoFile = file,
                    statusMessage = if (file != null) "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911..." else "\uC774\uBBF8\uC9C0 \uC800\uC7A5 \uC2E4\uD328",
                )
            }
            if (file != null) {
                showCapturedPhoto(file)
                delay(CAPTURE_REVIEW_DELAY_MS)
                identifyProduct(file)
            } else {
                startPreview()
            }
        }
    }

    private fun createStoppedStreamPlaceholder(): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(18, 18, 18))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 44f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textAlign = Paint.Align.CENTER
            textSize = 30f
        }

        canvas.drawText("Camera stream stopped", bitmap.width / 2f, bitmap.height / 2f - 24f, titlePaint)
        canvas.drawText("Fallback capture image", bitmap.width / 2f, bitmap.height / 2f + 34f, bodyPaint)
        return bitmap
    }

    fun retakePhoto() {
        stopAnalysisMessages()
        analysisJob?.cancel()
        analysisJob = null
        val jobId = currentAnalysisJobId
        currentAnalysisJobId = null
        latestAnalysisResult = null
        val file = _uiState.value.pendingPhotoFile
        viewModelScope.launch(Dispatchers.IO) {
            if (jobId != null) {
                runCatching { shoppingApiClient.cancelAnalysis(jobId) }
                    .onFailure { Log.w(TAG, "Failed to cancel analysis job $jobId", it) }
            }
            if (file?.delete() == false) Log.w(TAG, "Failed to delete ${file.absolutePath}")
        }
        _uiState.update {
            it.copy(
                pendingPhotoFile = null,
                isSearching = false,
                awaitingProductConfirmation = false,
                showPriceComparison = false,
                searchResult = null,
                statusMessage = "다시 촬영해주세요",
            )
        }
        startPreview() // 라이브 프리뷰 재개
        showReadyScreen()
    }

    // [검색] 미리보기 사진을 LLM 파이프라인으로 전송하고 폰 화면에 결과를 표시한다.
    fun submitForSearch() {
        val file = _uiState.value.pendingPhotoFile ?: return
        continueAnalysis(file)
    }

    private fun identifyProduct(file: File) {
        _capturedPhotoFile.value = file
        _uiState.update {
            it.copy(
                isSearching = true,
                awaitingProductConfirmation = false,
                showPriceComparison = false,
                searchResult = null,
                statusMessage = "상품명 확인 중...",
            )
        }
        displayLoading()

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val jobId = shoppingApiClient.startAnalysis(file)
                currentAnalysisJobId = jobId

                repeat(40) {
                    delay(1000)
                    val update = shoppingApiClient.getAnalysisStatus(jobId)
                    Log.d(
                        TAG,
                        "identity status job=$jobId state=${update.state} stage=${update.stage} " +
                            "rawStatus=${update.rawStatus} paused=${update.isPausedIdentity} " +
                            "finished=${update.isFinished} failed=${update.isFailed} " +
                            "product=${update.result?.productName} original=${update.result?.originalProductName} " +
                            "brand=${update.result?.brand} message=${update.message}",
                    )
                    displayAnalysisProgress(
                        message = analysisStageLabel(update.stage, update.message),
                        partial = latestAnalysisResult,
                    )
                    update.result?.let { partial ->
                        latestAnalysisResult = latestAnalysisResult.mergeWith(partial)
                        displayAnalysisProgress(
                            message = analysisStageLabel(update.stage, update.message),
                            partial = latestAnalysisResult,
                        )
                    }
                    if (update.isPausedIdentity) {
                        val identified = latestAnalysisResult
                        if (!identified?.productName.isNullOrBlank() || !identified?.originalProductName.isNullOrBlank()) {
                            return@runCatching identified!!
                        }
                        error("Product identity paused without product_name.")
                    }
                    if (update.isFailed) error(update.message ?: "상품 식별 실패")
                    if (update.isFinished) {
                        return@runCatching latestAnalysisResult ?: error("상품을 식별하지 못했습니다.")
                    }
                }
                error("상품 식별 시간이 초과되었습니다.")
            }
                .onSuccess { identified ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = true,
                            showPriceComparison = false,
                            searchResult = identified,
                            statusMessage = "해당 상품이 맞습니까?",
                        )
                    }
                    displayProductConfirmation(identified)
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) {
                        Log.i(TAG, "Product identification cancelled.")
                        return@onFailure
                    }
                    Log.e(TAG, "Product identification failed", throwable)
                    val isConnectionError = throwable is SocketTimeoutException || throwable is ConnectException
                    val result = AnalysisResult(
                        status = ResultStatus.ERROR,
                        headline = if (isConnectionError) "서버 연결 실패" else "상품 식별 실패",
                        message = throwable.message ?: "분석 서버 연결을 확인해주세요.",
                    )
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = false,
                            showPriceComparison = false,
                            searchResult = result,
                            statusMessage = if (isConnectionError) "서버 연결 실패" else "상품 식별 실패",
                        )
                    }
                }
        }
    }

    private fun continueAnalysis(file: File) {
        startAnalysisMessages()
        _capturedPhotoFile.value = file
        displayLoading()

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val jobId = currentAnalysisJobId ?: shoppingApiClient.startAnalysis(file).also { currentAnalysisJobId = it }
                shoppingApiClient.continueAnalysis(jobId)
                var result = latestAnalysisResult

                repeat(120) {
                    delay(1500)
                    val update = shoppingApiClient.getAnalysisStatus(jobId)
                    displayAnalysisProgress(
                        message = analysisStageLabel(update.stage, update.message),
                        partial = result,
                    )
                    update.result?.let { partial ->
                        result = result.mergeWith(partial)
                        latestAnalysisResult = result
                        result?.let { merged ->
                            _uiState.update {
                                it.copy(
                                    isSearching = true,
                                    awaitingProductConfirmation = false,
                                    showPriceComparison = false,
                                    searchResult = merged,
                                )
                            }
                            displayAnalysisProgress(
                                message = analysisStageLabel(update.stage, update.message),
                                partial = merged,
                            )
                        }
                    }

                    if (update.isFinished) return@runCatching result ?: error("Analysis completed without result.")
                    if (update.isFailed) error(update.message ?: "Analysis job failed.")
                }

                result ?: error("Analysis timed out.")
            }
                .onSuccess { result ->
                    latestAnalysisResult = result
                    stopAnalysisMessages()
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = false,
                            showPriceComparison = false,
                            searchResult = result,
                            statusMessage = "완료",
                        )
                    }
                    displayResult(result)
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) {
                        Log.i(TAG, "Product analysis cancelled.")
                        return@onFailure
                    }
                    stopAnalysisMessages()
                    Log.e(TAG, "Product analysis failed", throwable)
                    val result = AnalysisResult(
                        status = ResultStatus.ERROR,
                        headline = "분석 실패",
                        message = throwable.message ?: "분석 서버 연결을 확인해주세요.",
                    )
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = false,
                            showPriceComparison = false,
                            searchResult = result,
                            statusMessage = "분석 실패",
                        )
                    }
                    displayResult(result)
                }
        }
    }

    private fun displayAnalysisProgress(message: String, partial: AnalysisResult? = null) {
        val currentDisplay = display ?: return
        val capturedPhotoUri = capturedPhotoDisplayUri(
            uploadedImageUrl = partial?.imageUrl ?: latestAnalysisResult?.imageUrl,
            allowLocalFallback = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 18, background = FlexBoxBackground.CARD) {
                    capturedPhotoUri?.let { uri ->
                        image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    }
                    text("\uC0C1\uD488 \uBD84\uC11D \uC911", style = TextStyle.HEADING)
                    text(message, style = TextStyle.BODY, color = TextColor.SECONDARY)
                    partial?.let { result ->
                        text(result.productName ?: result.headline, style = TextStyle.BODY)
                        result.brand?.let { text("\uBE0C\uB79C\uB4DC: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.category?.let { text("\uCE74\uD14C\uACE0\uB9AC: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.productDescription?.let {
                            text(it.take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        result.specifications.take(2).forEach {
                            text("\uC2A4\uD399: ${it.take(DISPLAY_TEXT_LIMIT)}", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "displayAnalysisProgress sendContent failed: ${error.description}")
            }
        }
    }

    private fun analysisStageLabel(stage: String?, fallback: String?): String {
        return when (stage?.trim()?.lowercase()) {
            "uploading_image" -> "\uC774\uBBF8\uC9C0 \uC5C5\uB85C\uB4DC \uC911..."
            "searching_lens" -> "\uC77C\uCE58\uD558\uB294 \uC0C1\uD488 \uCC3E\uB294 \uC911..."
            "identifying_product" -> "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911..."
            "identity_ready" -> "\uC0C1\uD488 \uD655\uC778 \uC644\uB8CC"
            "searching_specs" -> "\uC2A4\uD399 \uAC80\uC0C9 \uC911..."
            "summarizing_reviews" -> "\uD6C4\uAE30 \uC815\uB9AC \uC911..."
            "searching_prices" -> "\uAC00\uACA9 \uAC80\uC0C9 \uC911..."
            "checking_image_with_claude" -> "\uC774\uBBF8\uC9C0 \uD655\uC778 \uC911..."
            "done" -> "\uBD84\uC11D \uC644\uB8CC"
            else -> "\uBD84\uC11D \uC911..."
        }
    }

    private fun startAnalysisMessages() {
        analysisMessageJob?.cancel()
        val koreanMessages = listOf(
            "\uC0C1\uD488 \uBD84\uC11D \uC911...",
            "\uC2A4\uD399 \uAC80\uC0C9 \uC911...",
            "\uD6C4\uAE30 \uC815\uB9AC \uC911...",
            "\uAC00\uACA9 \uAC80\uC0C9 \uC911...",
        )
        analysisMessageJob = viewModelScope.launch {
            var index = 0
            while (true) {
                _uiState.update { it.copy(isSearching = true, statusMessage = koreanMessages[index]) }
                displayAnalysisProgress(koreanMessages[index], partial = latestAnalysisResult)
                index = (index + 1) % koreanMessages.size
                delay(1800)
            }
        }
        return

        val messages = listOf(
            "상품 식별 중...",
            "스펙 검색 중...",
            "후기 정리 중...",
            "가격 검색 중...",
        )
        analysisMessageJob = viewModelScope.launch {
            var index = 0
            while (true) {
                _uiState.update { it.copy(isSearching = true, statusMessage = messages[index]) }
                displayAnalysisProgress(messages[index], partial = latestAnalysisResult)
                index = (index + 1) % messages.size
                delay(1800)
            }
        }
    }

    private fun stopAnalysisMessages() {
        analysisMessageJob?.cancel()
        analysisMessageJob = null
    }

    fun displayLoading() {
        val currentDisplay = display ?: return
        val capturedPhotoUri = capturedPhotoDisplayUri(
            uploadedImageUrl = latestAnalysisResult?.imageUrl,
            allowLocalFallback = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    capturedPhotoUri?.let { uri ->
                        image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    }
                    text("\uC0C1\uD488 \uBD84\uC11D \uC911", style = TextStyle.HEADING)
                    text("\uC7A0\uC2DC\uB9CC \uAE30\uB2E4\uB824\uC8FC\uC138\uC694", style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }
        }
        return

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    text("상품 분석 중...", style = TextStyle.HEADING)
                    text("잠시만 기다려주세요", style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }
        }
    }

    fun stopSession() {
        stopAnalysisMessages()
        analysisJob?.cancel()
        analysisJob = null
        currentAnalysisJobId = null
        latestAnalysisResult = null
        stopPreview()
        stream?.stop()
        session?.removeDisplay()
        session?.stop()
        stream = null
        display = null
        session = null
        _previewFrame.value = null
        _capturedPhotoFile.value = null
        _uiState.value = SessionUiState()
    }

    private fun showReadyScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 12, padding = 16, onClick = { capturePhoto() }) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 8,
                        padding = 28,
                        background = FlexBoxBackground.CARD,
                    ) {
                        text("<-", style = TextStyle.HEADING)
                    }
                    text(
                        "\uC774\uACF3\uC5D0 \uBB3C\uAC74\uC744 \uB450\uACE0 \uCC0D\uC5B4\uC8FC\uC138\uC694",
                        style = TextStyle.BODY,
                        color = TextColor.SECONDARY,
                    )
                    button(
                        label = "촬영",
                        style = ButtonStyle.PRIMARY,
                        onClick = { capturePhoto() },
                    )
                }
            }
        }
    }

    private fun showCapturedPhoto(file: File) {
        val currentDisplay = display ?: return
        val uri = capturedPhotoDisplayUri() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 12) {
                    image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    text("촬영 완료", style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "Captured photo display failed: ${error.description}")
                currentDisplay.sendContent {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                        text("촬영 완료", style = TextStyle.HEADING)
                        text("이미지를 분석합니다", style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                }
            }
        }
    }

    private fun handleSessionError(error: DeviceSessionError) {
        Log.e(TAG, "Session error: ${error.description}")
        _uiState.update { it.copy(statusMessage = "세션 오류: ${error.description}") }
        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
            Wearables.openDATGlassesAppUpdate(getApplication())
        }
    }

    private fun onSessionStopped() {
        stopAnalysisMessages()
        analysisJob?.cancel()
        analysisJob = null
        stopPreview()
        stream = null
        display = null
        session = null
        _previewFrame.value = null
        _uiState.update {
            it.copy(
                streamState = null,
                displayState = null,
                isCapturing = false,
                statusMessage = "Session stopped. Reconnect the glasses.",
            )
        }
    }

    // raw VideoFrame → Bitmap. 버퍼 크기로 픽셀 포맷을 추정한다(RGBA_8888 vs NV21/YUV).
    // 압축·코덱설정 프레임은 건너뛴다. (임시 프리뷰용이라 완벽한 색 정확도는 목표가 아님)
    private fun videoFrameToBitmap(frame: VideoFrame): Bitmap? {
        if (frame.isCompressed || frame.isCodecConfig) return null
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null

        val buf = frame.buffer.duplicate().apply { rewind() }
        val remaining = buf.remaining()
        return try {
            when {
                // RGBA_8888: w*h*4 바이트
                remaining >= w * h * 4 -> {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buf) }
                }
                // YUV 4:2:0 planar(I420): Y(w*h) + U(w*h/4) + V(w*h/4).
                // Convert directly to ARGB for preview to avoid per-frame JPEG encode/decode.
                remaining >= w * h * 3 / 2 -> {
                    val src = ByteArray(remaining).also { buf.get(it) }
                    yuv420PlanarToBitmap(src, w, h)
                }
                else -> {
                    Log.w(TAG, "Unrecognized frame: ${w}x$h remaining=$remaining")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "videoFrameToBitmap failed (${w}x$h remaining=$remaining)", e)
            null
        }
    }

    private fun VideoFrame.toRawPreviewFrame(capturedAtMs: Long): RawPreviewFrame? {
        val buf = buffer.duplicate().apply { rewind() }
        if (width <= 0 || height <= 0 || buf.remaining() <= 0) return null
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        return RawPreviewFrame(
            width = width,
            height = height,
            bytes = bytes,
            isCompressed = isCompressed,
            isCodecConfig = isCodecConfig,
            capturedAtMs = capturedAtMs,
        )
    }

    private fun RawPreviewFrame.formatHint(): String {
        val rgbaSize = width * height * 4
        val yuv420Size = width * height * 3 / 2
        return when {
            isCompressed -> "COMPRESSED"
            isCodecConfig -> "CODEC_CONFIG"
            bytes.size >= rgbaSize -> "RGBA_8888"
            bytes.size >= yuv420Size -> "YUV_420_PLANAR"
            else -> "UNKNOWN"
        }
    }

    private fun yuv420PlanarToBitmap(src: ByteArray, width: Int, height: Int): Bitmap {
        val ySize = width * height
        val chromaWidth = width / 2
        val uStart = ySize
        val vStart = ySize + ySize / 4
        val pixels = IntArray(ySize)

        for (row in 0 until height) {
            val yRow = row * width
            val chromaRow = (row / 2) * chromaWidth
            for (col in 0 until width) {
                val y = src[yRow + col].toInt() and 0xFF
                val chromaIndex = chromaRow + col / 2
                val u = (src[uStart + chromaIndex].toInt() and 0xFF) - 128
                val v = (src[vStart + chromaIndex].toInt() and 0xFF) - 128

                val r = clampRgb(y + ((1436 * v) shr 10))
                val g = clampRgb(y - ((352 * u + 731 * v) shr 10))
                val b = clampRgb(y + ((1815 * u) shr 10))
                pixels[yRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun clampRgb(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    private fun savePhotoToCache(photoData: PhotoData): File? {
        return try {
            val bitmap = when (photoData) {
                is PhotoData.Bitmap -> photoData.bitmap
                is PhotoData.HEIC -> decodeHeic(photoData)
            }
            saveBitmapToCache(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
            null
        }
    }

    private fun saveRawFrameToCache(frame: RawPreviewFrame): File? {
        return try {
            val dir = File(getApplication<Application>().cacheDir, "captures")
            dir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val rawFile = File(dir, "photo_${timestamp}_${frame.width}x${frame.height}_${frame.formatHint()}.raw")
            FileOutputStream(rawFile).use { out -> out.write(frame.bytes) }
            File(dir, rawFile.nameWithoutExtension + ".txt").writeText(
                buildString {
                    appendLine("width=${frame.width}")
                    appendLine("height=${frame.height}")
                    appendLine("bytes=${frame.bytes.size}")
                    appendLine("formatHint=${frame.formatHint()}")
                    appendLine("isCompressed=${frame.isCompressed}")
                    appendLine("isCodecConfig=${frame.isCodecConfig}")
                    appendLine("capturedAtMs=${frame.capturedAtMs}")
                },
            )
            rawFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save raw preview frame", e)
            null
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap): File? {
        return try {
            val dir = File(getApplication<Application>().cacheDir, "captures")
            dir.mkdirs()
            val file = File(dir, "photo_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Saved PNG capture: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap", e)
            null
        }
    }

    private fun decodeHeic(photo: PhotoData.HEIC): Bitmap {
        val bytes = ByteArray(photo.data.remaining()).also { photo.data.get(it) }
        val matrix = getExifTransform(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return if (matrix.isIdentity) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getExifTransform(bytes: ByteArray): Matrix {
        val matrix = Matrix()
        return try {
            ByteArrayInputStream(bytes).use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                }
                matrix
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read EXIF", e)
            matrix
        }
    }

    private fun capturedPhotoDisplayUri(
        uploadedImageUrl: String? = null,
        allowLocalFallback: Boolean = true,
    ): String? {
        uploadedImageUrl?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "Using uploaded capture image URL for glasses display: $it")
            return it
        }
        if (!allowLocalFallback) return null
        val file = _uiState.value.pendingPhotoFile ?: return null
        return FileProvider.getUriForFile(
            getApplication(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        ).toString().also {
            Log.i(TAG, "Using local capture image URI for glasses display: $it")
        }
    }

    private fun openUrlOnPhone(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        getApplication<Application>().startActivity(intent)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
