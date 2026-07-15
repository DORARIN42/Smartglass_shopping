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
import com.example.raybanvision.data.DISPLAY_SAMPLES
import com.example.raybanvision.data.ProductCandidate
import com.example.raybanvision.data.ResultStatus
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
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
                                if (top != null) {
                                    text(top.price, style = TextStyle.HEADING)
                                    top.store?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                }
                                if (result.status == ResultStatus.UNCERTAIN && result.candidates.size > 1) {
                                    text(
                                        "후보 ${result.candidates.size}개 · 폰에서 선택",
                                        style = TextStyle.BODY,
                                        color = TextColor.SECONDARY,
                                    )
                                }
                            }
                            top?.linkUrl?.let { url ->
                                button(
                                    label = "폰에서 자세히 보기",
                                    style = ButtonStyle.PRIMARY,
                                    iconName = IconName.CHECKMARK,
                                    onClick = { openUrlOnPhone(url) },
                                )
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
                        statusMessage = if (file != null) "촬영 완료 — 재촬영 또는 검색" else "파일 저장 실패",
                    )
                }
                if (file == null) startPreview()
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
                            statusMessage = if (file != null) "촬영 완료 — 재촬영 또는 검색" else "파일 저장 실패",
                        )
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
        val file = _uiState.value.pendingPhotoFile
        viewModelScope.launch(Dispatchers.IO) {
            if (file?.delete() == false) Log.w(TAG, "Failed to delete ${file.absolutePath}")
        }
        _uiState.update {
            it.copy(pendingPhotoFile = null, isSearching = false, searchResult = null, statusMessage = "다시 촬영해주세요")
        }
        startPreview() // 라이브 프리뷰 재개
    }

    // [검색] 미리보기 사진을 LLM 파이프라인으로 전송하고 폰 화면에 결과를 표시한다.
    fun submitForSearch() {
        val file = _uiState.value.pendingPhotoFile ?: return
        _uiState.update { it.copy(isSearching = true, statusMessage = "검색 완료") }
        _capturedPhotoFile.value = file // LLM 팀이 collect하는 실제 전송 지점

        // 샘플 검색 결과를 폰 화면에 바로 표시
        DISPLAY_SAMPLES.firstOrNull()?.let { sample ->
            _uiState.update { it.copy(searchResult = sample.result) }
        }
    }

    fun displayLoading() {
        val currentDisplay = display ?: return
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
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    text("준비 완료", style = TextStyle.HEADING)
                    text("궁금한 상품을 바라보고 촬영해주세요", style = TextStyle.BODY, color = TextColor.SECONDARY)
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
