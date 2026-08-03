package com.example.raybanvision.session

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.raybanvision.BuildConfig
import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.data.ProductCandidate
import com.example.raybanvision.data.ResultStatus
import com.example.raybanvision.data.SavedLink
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
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.IconStyle
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
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

        // PNG assets served from GitHub raw — glasses fetch via HTTPS.
        // Files live in root/Shoply_meta_png/ in the repo.
        // Update BRANCH if assets are on a different branch.
        private const val ASSET_BASE =
            "https://raw.githubusercontent.com/DORARIN42/Smartglass_shopping/main/root/Shoply_meta_png"
        private const val IMG_LOGO          = "$ASSET_BASE/logo.png"
        private const val IMG_GUIDELINE     = "$ASSET_BASE/Guideline.png"
        private const val IMG_CAMERA_CLEAR  = "$ASSET_BASE/CameraClear.png"
        private const val IMG_CAMERA_COLOR  = "$ASSET_BASE/CameraColor.png"
        private const val IMG_SEARCH_COLOR  = "$ASSET_BASE/search-color.png"
        // Loading animation — pre-rendered GIF hosted alongside other assets.
        private const val IMG_LOADING_GIF   =
            "https://raw.githubusercontent.com/DORARIN42/Smartglass_shopping/main/root/Animation/Loading.gif"

        // Duration (ms) the splash screen stays before transitioning to ready.
        private const val SPLASH_DURATION_MS = 2_500L
        // Duration (ms) search-complete screen stays before showing the full result.
        private const val SEARCH_COMPLETE_DISPLAY_MS = 1_500L
    }

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
    private var currentAnalysisJobId: String? = null
    private var latestAnalysisResult: AnalysisResult? = null
    private val shoppingApiClient = ShoppingApiClient(BuildConfig.ANALYSIS_BASE_URL)

    // ─── LLM 팀 인터페이스 ────────────────────────────────────────────────
    // 사용자가 [검색]을 눌러 LLM 분석을 요청한 사진. LLM 팀은 이 StateFlow를 collect한다.
    // 촬영만 한 상태(재촬영/검색 결정 전)에서는 방출하지 않고, [검색] 시에만 새 값이 방출된다.
    private val _capturedPhotoFile = MutableStateFlow<File?>(null)
    val capturedPhotoFile: StateFlow<File?> = _capturedPhotoFile.asStateFlow()

    // 분석 완료 후 결과에 따라 적절한 화면으로 라우팅한다.
    // MATCHED/UNCERTAIN → 상품검색결과(199:2707), RETRY/ERROR → 기존 카드 형태.
    fun displayResult(result: AnalysisResult) {
        if (display == null) {
            Log.w(TAG, "displayResult called but display is not ready")
            _uiState.update { it.copy(statusMessage = "디스플레이 미준비 — 결과 표시 실패") }
            return
        }
        _uiState.update { it.copy(statusMessage = "결과 표시: ${result.status.name}") }
        when (result.status) {
            ResultStatus.MATCHED, ResultStatus.UNCERTAIN -> showProductResultScreen(result)
            ResultStatus.RETRY_REQUIRED -> showRetryScreen(result)
            ResultStatus.ERROR -> showErrorScreen(result)
        }
    }

    // Figma 199:2707 — 상품검색결과.
    // ProductName 카드(상단) + "이 상품이 맞나요?" 칩 + [다시 찍기 | 상품 정보] 버튼.
    private fun showProductResultScreen(result: AnalysisResult) {
        val currentDisplay = display ?: return
        val sub = listOfNotNull(result.brand, result.modelNumber).joinToString(", ")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 32, padding = 24) {
                    // ProductName card — flex-1 to fill remaining space
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 16,
                        padding = 16,
                        background = FlexBoxBackground.CARD,
                        flexGrow = 1f,
                    ) {
                        text(result.headline, style = TextStyle.HEADING)
                        if (sub.isNotEmpty()) text(sub, style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                    // Bottom section: confirmation chip + action buttons
                    flexBox(direction = Direction.COLUMN, gap = 32, crossAlignment = Alignment.CENTER) {
                        text("이 상품이 맞나요?", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        flexBox(direction = Direction.ROW, gap = 16) {
                            // 다시 찍기
                            flexBox(
                                direction = Direction.ROW, gap = 8, padding = 24,
                                background = FlexBoxBackground.CARD, flexGrow = 1f,
                                crossAlignment = Alignment.CENTER,
                                onClick = { retakePhoto(); showReadyScreen() },
                            ) {
                                icon(name = IconName.TWO_ARROWS_CLOCKWISE, style = IconStyle.OUTLINE)
                                text("다시 찍기", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                            // 상품 정보
                            flexBox(
                                direction = Direction.ROW, gap = 8, padding = 24,
                                background = FlexBoxBackground.CARD, flexGrow = 1f,
                                crossAlignment = Alignment.CENTER,
                                onClick = { showProductInfoScreen(result) },
                            ) {
                                icon(name = IconName.I_CIRCLE, style = IconStyle.OUTLINE)
                                text("상품 정보", style = TextStyle.BODY)
                            }
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showProductResultScreen failed: ${error.description}")
            }
        }
    }

    // Figma 199:3034 — 상품 정보 (스크롤).
    // ProductName 카드 + 정보 카드 목록(상품 요약·영양성분·후기·주요성분) + [다시 찍기 | 가격 정보].
    // 600px 초과 시 글라스 터치패드로 스크롤 가능.
    private fun showProductInfoScreen(result: AnalysisResult) {
        val currentDisplay = display ?: return
        val sub = listOfNotNull(result.brand, result.modelNumber).joinToString(", ")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24) {
                    // ProductName card
                    flexBox(direction = Direction.COLUMN, gap = 16, padding = 16, background = FlexBoxBackground.CARD) {
                        text(result.headline, style = TextStyle.HEADING)
                        if (sub.isNotEmpty()) text(sub, style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                    // Scrollable information cards
                    flexBox(direction = Direction.COLUMN, gap = 8) {
                        // 상품 요약 — active/selected card style (prominent)
                        result.productDescription?.takeIf { it.isNotBlank() }?.let { desc ->
                            flexBox(direction = Direction.COLUMN, gap = 12, padding = 16, background = FlexBoxBackground.CARD) {
                                text("상품 요약", style = TextStyle.BODY)
                                text(desc, style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                        // 영양성분 / 스펙
                        if (result.specifications.isNotEmpty()) {
                            flexBox(direction = Direction.COLUMN, gap = 12, padding = 16, background = FlexBoxBackground.CARD) {
                                text("영양성분", style = TextStyle.BODY)
                                text(result.specifications.joinToString("\n"), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                        // 후기
                        val reviewParts = buildList {
                            if (result.positiveReviews.isNotEmpty()) add("[장점] ${result.positiveReviews.joinToString(", ")}")
                            if (result.negativeReviews.isNotEmpty()) add("[단점] ${result.negativeReviews.joinToString(", ")}")
                        }
                        if (reviewParts.isNotEmpty()) {
                            flexBox(direction = Direction.COLUMN, gap = 12, padding = 16, background = FlexBoxBackground.CARD) {
                                text("후기", style = TextStyle.BODY)
                                text(reviewParts.joinToString("\n"), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                        // 주요 성분
                        result.message?.takeIf { it.isNotBlank() }?.let { msg ->
                            flexBox(direction = Direction.COLUMN, gap = 12, padding = 16, background = FlexBoxBackground.CARD) {
                                text("주요 성분", style = TextStyle.BODY)
                                text(msg, style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    // Action buttons
                    flexBox(direction = Direction.ROW, gap = 16) {
                        flexBox(
                            direction = Direction.ROW, gap = 8, padding = 24,
                            background = FlexBoxBackground.CARD, flexGrow = 1f,
                            crossAlignment = Alignment.CENTER,
                            onClick = { retakePhoto(); showReadyScreen() },
                        ) {
                            icon(name = IconName.TWO_ARROWS_CLOCKWISE, style = IconStyle.OUTLINE)
                            text("다시 찍기", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        flexBox(
                            direction = Direction.ROW, gap = 8, padding = 24,
                            background = FlexBoxBackground.CARD, flexGrow = 1f,
                            crossAlignment = Alignment.CENTER,
                            onClick = { showPriceInfoScreen(result) },
                        ) {
                            icon(name = IconName.CHECKMARK_CIRCLE, style = IconStyle.OUTLINE)
                            text("가격 정보", style = TextStyle.BODY)
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showProductInfoScreen failed: ${error.description}")
            }
        }
    }

    // Figma 199:2716 — 가격 정보 (스크롤).
    // ProductName 카드 + 가격 카드 목록(스토어·가격·링크 저장) + [다시 찍기 | 상품 정보].
    // 600px 초과 시 글라스 터치패드로 스크롤 가능.
    private fun showPriceInfoScreen(result: AnalysisResult) {
        val currentDisplay = display ?: return
        val sub = listOfNotNull(result.brand, result.modelNumber).joinToString(", ")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 16, padding = 32) {
                    // ProductName card
                    flexBox(direction = Direction.COLUMN, gap = 16, padding = 16, background = FlexBoxBackground.CARD) {
                        text(result.headline, style = TextStyle.HEADING)
                        if (sub.isNotEmpty()) text(sub, style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                    // Scrollable price cards
                    flexBox(direction = Direction.COLUMN, gap = 8, flexGrow = 1f) {
                        result.candidates.forEach { candidate ->
                            flexBox(
                                direction = Direction.ROW, gap = 24, padding = 16,
                                background = FlexBoxBackground.CARD,
                                crossAlignment = Alignment.CENTER,
                            ) {
                                // Store + price
                                flexBox(
                                    direction = Direction.ROW, gap = 8,
                                    flexGrow = 1f, crossAlignment = Alignment.CENTER,
                                ) {
                                    candidate.store?.let { text(it, style = TextStyle.BODY) }
                                    val priceDisplay = candidate.price.removeSuffix("원").trim()
                                    text("₩$priceDisplay", style = TextStyle.BODY)
                                }
                                // 링크 저장 button
                                flexBox(
                                    direction = Direction.ROW, gap = 8, padding = 12,
                                    background = FlexBoxBackground.CARD,
                                    crossAlignment = Alignment.CENTER,
                                    onClick = {
                                        candidate.linkUrl?.let { url ->
                                            if (SavedLinksStore.links.none { it.linkUrl == url }) {
                                                SavedLinksStore.save(
                                                    SavedLink(
                                                        productName = result.headline,
                                                        store = candidate.store ?: "",
                                                        price = candidate.price.removeSuffix("원").trim(),
                                                        linkUrl = url,
                                                        savedAt = "글라스에서 저장",
                                                    )
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    icon(name = IconName.CHECKMARK_CIRCLE, style = IconStyle.OUTLINE)
                                    text("링크 저장", style = TextStyle.META, color = TextColor.SECONDARY)
                                }
                            }
                        }
                    }
                    // Action buttons
                    flexBox(direction = Direction.ROW, gap = 16) {
                        flexBox(
                            direction = Direction.ROW, gap = 8, padding = 24,
                            background = FlexBoxBackground.CARD, flexGrow = 1f,
                            crossAlignment = Alignment.CENTER,
                            onClick = { retakePhoto(); showReadyScreen() },
                        ) {
                            icon(name = IconName.TWO_ARROWS_CLOCKWISE, style = IconStyle.OUTLINE)
                            text("다시 찍기", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        }
                        flexBox(
                            direction = Direction.ROW, gap = 8, padding = 24,
                            background = FlexBoxBackground.CARD, flexGrow = 1f,
                            crossAlignment = Alignment.CENTER,
                            onClick = { showProductInfoScreen(result) },
                        ) {
                            icon(name = IconName.I_CIRCLE, style = IconStyle.OUTLINE)
                            text("상품 정보", style = TextStyle.BODY)
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showPriceInfoScreen failed: ${error.description}")
            }
        }
    }

    private fun showRetryScreen(result: AnalysisResult) {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
                        icon(name = IconName.TWO_ARROWS_CLOCKWISE, style = IconStyle.OUTLINE)
                        text("다시 촬영해주세요", style = TextStyle.HEADING)
                    }
                    text(
                        result.message ?: "상품이 잘 보이도록 밝은 곳에서 가까이 촬영해주세요.",
                        style = TextStyle.BODY, color = TextColor.SECONDARY,
                    )
                }
            }.onFailure { error, _ -> Log.e(TAG, "showRetryScreen failed: ${error.description}") }
        }
    }

    private fun showErrorScreen(result: AnalysisResult) {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    flexBox(direction = Direction.ROW, gap = 8, crossAlignment = Alignment.CENTER) {
                        icon(name = IconName.EXCLAMATION_TRIANGLE, style = IconStyle.FILLED)
                        text("오류가 발생했어요", style = TextStyle.HEADING)
                    }
                    text(
                        result.message ?: "잠시 후 다시 시도해주세요.",
                        style = TextStyle.BODY, color = TextColor.SECONDARY,
                    )
                }
            }.onFailure { error, _ -> Log.e(TAG, "showErrorScreen failed: ${error.description}") }
        }
    }
    // ─────────────────────────────────────────────────────────────────────

    fun startSession(deviceId: DeviceIdentifier) {
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
            StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7, compressVideo = false),
        )
            .onSuccess { addedStream ->
                stream = addedStream
                viewModelScope.launch {
                    addedStream.state.collect { state ->
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
                        if (state == DisplayState.STARTED) showSplashScreen()
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
            currentStream.videoStream.conflate().collect { frame ->
                videoFrameToBitmap(frame)?.let { bmp -> _previewFrame.value = bmp }
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    fun capturePhoto() {
        val currentStream = stream ?: return
        if (!_uiState.value.canCapture) return

        // 캡처 프레임 손상 방지: 촬영 동안 프리뷰(videoStream 수집) 중단.
        stopPreview()
        _uiState.update { it.copy(isCapturing = true, statusMessage = "촬영 중...") }
        showCapturingScreen() // Figma 199:2680 — camera button pressed

        // 목 디바이스에서는 capturePhoto()가 스트리밍 버퍼와 경합해 찢김/부분 프레임이 발생한다.
        // debug 빌드(목)에서는 이미 깨끗한 현재 프리뷰 프레임을 그대로 저장한다.
        // 실제 글라스(release)는 고화질 capturePhoto()를 사용한다.
        val previewBitmap = _previewFrame.value
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
                    showCaptureCompleteScreen() // Figma 199:2863 — capture done
                    identifyProduct(file)
                } else {
                    startPreview()
                }
            }
            return
        }

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
                        showCaptureCompleteScreen() // Figma 199:2863 — capture done
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
    fun retakePhoto() {
        stopAnalysisMessages()
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
                searchResult = null,
                statusMessage = "다시 촬영해주세요",
            )
        }
        startPreview() // 라이브 프리뷰 재개
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
                searchResult = null,
                statusMessage = "상품명 확인 중...",
            )
        }
        displayLoading()

        viewModelScope.launch(Dispatchers.IO) {
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
                    update.result?.let { partial ->
                        latestAnalysisResult = latestAnalysisResult.mergeWith(partial)
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
                            searchResult = identified,
                            statusMessage = "해당 상품이 맞습니까?",
                        )
                    }
                }
                .onFailure { throwable ->
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

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val jobId = currentAnalysisJobId ?: shoppingApiClient.startAnalysis(file).also { currentAnalysisJobId = it }
                shoppingApiClient.continueAnalysis(jobId)
                var result = latestAnalysisResult

                repeat(120) {
                    delay(1500)
                    val update = shoppingApiClient.getAnalysisStatus(jobId)
                    update.result?.let { partial ->
                        result = result.mergeWith(partial)
                        latestAnalysisResult = result
                        result?.let { merged ->
                            _uiState.update {
                                it.copy(
                                    isSearching = true,
                                    awaitingProductConfirmation = false,
                                    searchResult = merged,
                                )
                            }
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
                            searchResult = result,
                            statusMessage = "완료",
                        )
                    }
                    showSearchCompleteScreen() // Figma 199:3013 — search done
                    delay(SEARCH_COMPLETE_DISPLAY_MS)
                    displayResult(result)
                }
                .onFailure { throwable ->
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
                            searchResult = result,
                            statusMessage = "분석 실패",
                        )
                    }
                    displayResult(result)
                }
        }
    }

    private fun startAnalysisMessages() {
        analysisMessageJob?.cancel()
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
                index = (index + 1) % messages.size
                delay(1800)
            }
        }
    }

    private fun stopAnalysisMessages() {
        analysisMessageJob?.cancel()
        analysisMessageJob = null
    }

    // Figma node 199:3562 — Shown while product search/analysis is running.
    // Loading.gif is the pre-rendered version of root/Animation/Loading.json.
    fun displayLoading() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 32,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = IMG_LOADING_GIF, sizePreset = ImageSize.FILL)
                    text(
                        "상품을 검색 중입니다.",
                        style = TextStyle.BODY,
                        color = TextColor.SECONDARY,
                    )
                }
            }
        }
    }

    fun stopSession() {
        stopAnalysisMessages()
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

    // Figma node 199:2768 — Splash screen shown immediately when display connects.
    // Layout: logo image (325×203) centred + tagline text below, gap=40dp.
    // Transitions to showReadyScreen() after SPLASH_DURATION_MS.
    private fun showSplashScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 40,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = IMG_LOGO, sizePreset = ImageSize.FILL)
                    text(
                        "Shop smarter, Spend wiser",
                        style = TextStyle.BODY,
                        color = TextColor.SECONDARY,
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showSplashScreen failed: ${error.description}")
            }
            delay(SPLASH_DURATION_MS)
            showReadyScreen()
        }
    }

    // Figma node 199:2667 — Camera-ready screen shown after the splash.
    // Layout: horizontal row — Guideline strip (left) + Column (camera icon + instruction chip).
    // gap between the two halves matches the Figma 70dp spacing.
    private fun showReadyScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.ROW, gap = 32) {
                    // Left: glasses guideline strip (Figma: 85×600px)
                    image(uri = IMG_GUIDELINE, sizePreset = ImageSize.FILL)

                    // Center: camera icon + instruction text
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 32,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                        flexGrow = 1f,
                    ) {
                        // CameraClear — circular camera button graphic (Figma: 160×160px)
                        image(uri = IMG_CAMERA_CLEAR, sizePreset = ImageSize.FILL)

                        // Instruction chip (Figma: White300 bg pill)
                        text(
                            "왼쪽에 물체를 배치해주세요.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showReadyScreen failed: ${error.description}")
            }
        }
    }

    // Figma node 199:2680 — Shown immediately when the user presses the capture button.
    // Same row layout as ready screen, but uses CameraColor.png (brand gradient camera graphic).
    private fun showCapturingScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.ROW, gap = 32) {
                    image(uri = IMG_GUIDELINE, sizePreset = ImageSize.FILL)
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 32,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                        flexGrow = 1f,
                    ) {
                        image(uri = IMG_CAMERA_COLOR, sizePreset = ImageSize.FILL)
                        text(
                            "왼쪽에 물체를 배치해주세요.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showCapturingScreen failed: ${error.description}")
            }
        }
    }

    // Figma node 199:2863 — Shown when the photo capture finishes (~2s after button press).
    // Guideline strip removed; camera graphic centred on the full 600×600 canvas.
    private fun showCaptureCompleteScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 32,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = IMG_CAMERA_COLOR, sizePreset = ImageSize.FILL)
                    text(
                        "사진 촬영이 완료되었습니다!",
                        style = TextStyle.BODY,
                        color = TextColor.SECONDARY,
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showCaptureCompleteScreen failed: ${error.description}")
            }
        }
    }

    // Figma node 199:3013 — Shown when product analysis finishes successfully.
    // Briefly displayed (~1.5s) before the full result screen.
    private fun showSearchCompleteScreen() {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 32,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = IMG_SEARCH_COLOR, sizePreset = ImageSize.FILL)
                    text(
                        "검색이 완료되었습니다!",
                        style = TextStyle.BODY,
                        color = TextColor.SECONDARY,
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "showSearchCompleteScreen failed: ${error.description}")
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
        stream = null
        display = null
        session = null
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
                // YUV 4:2:0 planar(I420): Y(w*h) + U(w*h/4) + V(w*h/4). 별도 U/V 평면을
                // Android YuvImage 가 기대하는 NV21(Y + 인터리브 V,U)로 재구성한 뒤 JPEG 경유 디코드.
                remaining >= w * h * 3 / 2 -> {
                    val src = ByteArray(remaining).also { buf.get(it) }
                    val ySize = w * h
                    val chromaSize = ySize / 4
                    val uStart = ySize
                    val vStart = ySize + chromaSize
                    val nv21 = ByteArray(ySize + chromaSize * 2)
                    System.arraycopy(src, 0, nv21, 0, ySize)
                    for (j in 0 until chromaSize) {
                        nv21[ySize + j * 2] = src[vStart + j]     // V
                        nv21[ySize + j * 2 + 1] = src[uStart + j] // U
                    }
                    val out = ByteArrayOutputStream()
                    YuvImage(nv21, ImageFormat.NV21, w, h, null)
                        .compressToJpeg(Rect(0, 0, w, h), 85, out)
                    val jpeg = out.toByteArray()
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
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

    private fun saveBitmapToCache(bitmap: Bitmap): File? {
        return try {
            val dir = File(getApplication<Application>().cacheDir, "captures")
            dir.mkdirs()
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
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
