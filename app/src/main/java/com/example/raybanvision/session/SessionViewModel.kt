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
import com.example.raybanvision.R
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
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.FlexBoxScope
import com.meta.wearable.dat.display.views.IconName
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val DISPLAY_CARD_TEXT_LIMIT = 120
        private const val PREVIEW_FRAME_INTERVAL_MS = 100L
        private const val RAW_PREVIEW_FRAME_RATE = 15
        private const val SHOPLY_META_PNG_BASE =
            "https://raw.githubusercontent.com/DORARIN42/Smartglass_shopping/feature/link-save-toggle-and-expand/root/Shoply_meta_png"
        private const val META_PNG_BASE =
            "https://raw.githubusercontent.com/DORARIN42/Smartglass_shopping/master/root/Meta%20PNG"
        private const val SHOPLY_LOGO_BLACK_URL =
            "https://raw.githubusercontent.com/DORARIN42/Smartglass_shopping/master/root/Meta%20PNG/Logo_black.png"
    }

    private data class ShoplyDisplayAssets(
        val logo: String?,
        val guideline: String?,
        val cameraClear: String?,
        val cameraColor: String?,
        val cameraRetake: String?,
        val productInfo: String?,
        val priceInfo: String?,
        val saveLink: String?,
        val searchColor: String?,
        val searchProduct: String?,
    )

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

    // (?????밸븶筌믩끃異? ??????거?琉뱀땡?????욱룏嶺???????꿔꺂??????????ㅻ쿋??????ㅳ늾??????????쇈궘?觀愿?? ??????깅렰???????밸븶??????밸븶???텣?Bitmap ???????????ㅼ뒧???????????ш끽維??λ궔??
    // ???濚밸Ŧ?김???????맜???嚥싲갭큔?댁빢???????쇨덧????????????쇈궘?觀愿????? ???怨쀫뎐?????????癲???debug/????????꿔꺂????釉띯뵛?????????????
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
    private var pendingDisplayResult: AnalysisResult? = null
    private var pendingDisplayProductConfirmation: AnalysisResult? = null
    @Volatile
    private var latestRawPreviewFrame: RawPreviewFrame? = null
    private val shoppingApiClient = ShoppingApiClient(BuildConfig.ANALYSIS_BASE_URL)

    // ?????? LLM ?? ??꿔꺂??琉몃쨨?????蹂κ텤???????????????????????????????????????????????????????????????????????????????????????????????????
    // ?????? [??β뼯援???????????LLM ???怨쀫뮝力???????거???????? LLM ???? ??StateFlow??collect??癲ル슢????
    // ??????????????釉먮빱?????????β뼯援??????β뼯援??????????????ш끽維??λ궔???? ?????꾢쳞?? [??β뼯援???? ??癲???????????ル봿??????ш끽維??λ궔???癲ル슢????
    private val _capturedPhotoFile = MutableStateFlow<File?>(null)
    val capturedPhotoFile: StateFlow<File?> = _capturedPhotoFile.asStateFlow()

    // LLM/???嶺뚮ㅎ??????????怨쀫뮝力???????밸븶????????逆?????꿔꺂???????????????맜???嚥싲갭큔?댁빢??????거?醫귣쐪?????????욱룕????β뼯援??????ル꺄椰???癲?嶺?
    // ?????맜???嚥싲갭큔?댁빢??????????거???????????釉뚯뺏? + ?轅붽틓????彛????ル봿??)?????ㅼ뒧??????????泳?뿀?? ?????몃뱥???????????????꿔꺂??틝???????棺堉?댆????????ル뭸癲??癲ル슢????
    fun displayResult(result: AnalysisResult) {
        val currentDisplay = display ?: run {
            Log.w(TAG, "displayResult called but display is not ready")
            pendingDisplayResult = result
            _uiState.update { it.copy(statusMessage = "\uB514\uC2A4\uD50C\uB808\uC774 \uC900\uBE44 \uC911...") }
            return
        }
        if (result.status == ResultStatus.MATCHED || result.status == ResultStatus.UNCERTAIN) {
            displayShoplyDetailedResult(currentDisplay, result)
            return
        }
        _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uACB0\uACFC: ${result.status.name}") }

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                // sendContent????꿔꺂?€??⑤슣瑗?????棺堉?뤃管??????룸ℓ??????flexBox)?????濚밸Ŧ援앾㎘??癲ル슢????
                when (result.status) {
                    ResultStatus.MATCHED, ResultStatus.UNCERTAIN -> {
                        val top: ProductCandidate? = result.topCandidate
                        flexBox(direction = Direction.COLUMN, gap = 12) {
                            flexBox(padding = 24, background = FlexBoxBackground.CARD) {
                                (result.imageUrl ?: top?.imageUrl)?.let { url ->
                                    Log.i(TAG, "Displaying result image on glasses: $url")
                                    image(uri = url, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                                }
                                text(result.headline, style = TextStyle.BODY)
                                if (result.status == ResultStatus.UNCERTAIN && result.candidates.size > 1) {
                                    text(
                                        "\uC0C1\uD488 \uD6C4\uBCF4 ${result.candidates.size}\uAC1C \uC911 \uD655\uC778\uC774 \uD544\uC694\uD569\uB2C8\uB2E4.",
                                        style = TextStyle.BODY,
                                        color = TextColor.SECONDARY,
                                    )
                                }
                            }
                            // ????壤???????????밸븶???????ル봿???黎??筌???異????渦깅맧遊??섎뎨??3???ㅳ늾??異????????棺堉?댆洹⑥춿???꿔꺂?????(?轅붽틓????ル竊숃눧???????뀀땽 ??β뼯援???곌램?뽳쭕?.
                            // ???? ?????꿔꺂????븍갭夷?+ ???ル봿???? ???????밸븶????????쇈궘????????????????????????? ??????
                            // ?????轅붽틓??熬곥끇釉????????⑤베????????flexGrow=1f???????渦깅맧遊??섎뎨?.
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
                        text("\uC0C1\uD488\uC744 \uB2E4\uC2DC \uCD2C\uC601\uD574\uC8FC\uC138\uC694", style = TextStyle.HEADING)
                        text(
                            result.message ?: "\uC0C1\uD488\uC744 \uB2E4\uC2DC \uCD2C\uC601\uD574\uC8FC\uC138\uC694.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }

                    ResultStatus.ERROR -> flexBox(
                        direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD,
                    ) {
                        text("\uBD84\uC11D \uC2E4\uD328", style = TextStyle.HEADING)
                        text(
                            result.message ?: "\uBD84\uC11D \uC11C\uBC84 \uC5F0\uACB0\uC744 \uD655\uC778\uD574\uC8FC\uC138\uC694.",
                            style = TextStyle.BODY,
                            color = TextColor.SECONDARY,
                        )
                    }
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayResult sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uACB0\uACFC \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
            }
        }
    }
    // ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private fun displayDetailedResult(currentDisplay: Display, result: AnalysisResult) {
        _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uACB0\uACFC: ${result.status.name}") }

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8) {
                    text(result.headline, style = TextStyle.HEADING)
                    (result.imageUrl ?: result.topCandidate?.imageUrl)?.let { url ->
                        Log.i(TAG, "Displaying detailed result image on glasses: $url")
                        image(uri = url, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    }
                    flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                        text("\uC0C1\uD488\uC815\uBCF4", style = TextStyle.HEADING)
                        text("\uC0C1\uD488\uBA85: ${result.productName ?: result.headline}", style = TextStyle.BODY)
                        result.originalProductName?.let { text("\uC6D0\uBCF8 \uC0C1\uD488\uBA85: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.brand?.let { text("\uBE0C\uB79C\uB4DC: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.originalBrand?.let { text("\uC6D0\uBCF8 \uBE0C\uB79C\uB4DC: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    result.productDescription?.let {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uC0C1\uD488 \uC124\uBA85", style = TextStyle.HEADING)
                            text(it, style = TextStyle.BODY)
                        }
                    }
                    if (result.specifications.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uC2A4\uD399", style = TextStyle.HEADING)
                            result.specifications.forEach {
                                text("\u2022 $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.positiveReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uAE0D\uC815\uD6C4\uAE30", style = TextStyle.HEADING)
                            result.positiveReviews.forEach {
                                text("\u2022 $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.negativeReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uBD80\uC815\uD6C4\uAE30", style = TextStyle.HEADING)
                            result.negativeReviews.forEach {
                                text("\u2022 $it", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (false && result.candidates.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 8, padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uAC00\uACA9\uC815\uBCF4", style = TextStyle.HEADING)
                            result.candidates.forEachIndexed { index, candidate ->
                                flexBox(
                                    direction = Direction.COLUMN,
                                    gap = 4,
                                    padding = 10,
                                    background = FlexBoxBackground.CARD,
                                    onClick = candidate.linkUrl?.let { url -> { openUrlOnPhone(url) } },
                                ) {
                                    text("${index + 1}. ${candidate.store ?: "\uD310\uB9E4\uCC98"}", style = TextStyle.BODY)
                                    text(candidate.price, style = TextStyle.HEADING)
                                    candidate.title?.let { text("\uC0C1\uD488\uBA85: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                    candidate.currency?.let { text("\uD1B5\uD654: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                    candidate.isKoreanMarket?.let { text("\uAD6D\uB0B4 \uC2DC\uC7A5: ${if (it) "\uC608" else "\uC544\uB2C8\uC624"}", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                                }
                            }
                        }
                    }
                    flexBox(direction = Direction.COLUMN, gap = 4, padding = 14, background = FlexBoxBackground.CARD) {
                        text("\uC0C1\uD0DC \uC815\uBCF4", style = TextStyle.HEADING)
                        text("\uC0C1\uD0DC: ${result.rawStatus ?: result.status.name}", style = TextStyle.BODY, color = TextColor.SECONDARY)
                        result.strategy?.let { text("\uC804\uB7B5: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.modelNumber?.let { text("\uBAA8\uB378\uBC88\uD638: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.category?.let { text("\uCE74\uD14C\uACE0\uB9AC: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.confidence?.let { text("\uC2E0\uB8B0\uB3C4: %.2f".format(it), style = TextStyle.BODY, color = TextColor.SECONDARY) }
                        result.averageRating?.let { text("\uD3C9\uC810: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    if (result.candidates.isNotEmpty()) {
                        button(
                            label = "\uAC00\uACA9\uBCF4\uAE30",
                            style = ButtonStyle.PRIMARY,
                            onClick = { showPriceComparison(result) },
                        )
                    }
                    button(
                        label = "\uC7AC\uCD2C\uC601",
                        style = ButtonStyle.PRIMARY,
                        onClick = { retakePhoto() },
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayDetailedResult sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uACB0\uACFC \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
            }
        }
    }

    fun showPriceComparison() {
        val result = _uiState.value.searchResult ?: latestAnalysisResult ?: return
        showPriceComparison(result)
    }

    private fun shoplyPng(fileName: String): String = "$SHOPLY_META_PNG_BASE/$fileName"

    private fun metaPng(fileName: String): String = "$META_PNG_BASE/$fileName"

    private fun shoplyLocalAssetUri(resourceId: Int, fileName: String): String? {
        return runCatching {
            val dir = File(getApplication<Application>().cacheDir, "display_assets").apply { mkdirs() }
            val file = File(dir, fileName)
            if (!file.exists() || file.length() == 0L) {
                getApplication<Application>().resources.openRawResource(resourceId).use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
            }
            FileProvider.getUriForFile(
                getApplication(),
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            ).toString()
        }.onFailure {
            Log.w(TAG, "Failed to prepare local display asset $fileName", it)
        }.getOrNull()
    }

    private fun shoplyDisplayAssets(): ShoplyDisplayAssets = ShoplyDisplayAssets(
        logo = SHOPLY_LOGO_BLACK_URL,
        guideline = shoplyPng("Guideline.png"),
        cameraClear = shoplyPng("CameraClear.png"),
        cameraColor = shoplyPng("CameraColor.png"),
        cameraRetake = shoplyPng("CameraRetake.png"),
        productInfo = shoplyPng("ProductInfo.png"),
        priceInfo = shoplyPng("PriceInfo.png"),
        saveLink = shoplyPng("SaveLink-small.png"),
        searchColor = shoplyPng("search-color.png"),
        searchProduct = shoplyPng("SearchProduct.png"),
    )

    private fun FlexBoxScope.shoplyPngButton(
        uri: String?,
        fallbackLabel: String,
        fallbackIcon: IconName,
        onClick: () -> Unit,
    ) {
        if (uri == null) {
            button(
                label = fallbackLabel,
                style = ButtonStyle.PRIMARY,
                iconName = fallbackIcon,
                onClick = onClick,
                flexGrow = 1f,
            )
            return
        }
        flexBox(
            direction = Direction.COLUMN,
            gap = 0,
            padding = 0,
            background = FlexBoxBackground.CARD,
            onClick = onClick,
            flexGrow = 1f,
            crossAlignment = Alignment.STRETCH,
        ) {
            image(uri = uri, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM)
        }
    }

    private fun productTitle(result: AnalysisResult): String =
        result.productName ?: result.originalProductName ?: result.headline

    private fun productMeta(result: AnalysisResult): String? =
        listOfNotNull(result.brand ?: result.originalBrand, result.category)
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }

    private fun productSummary(result: AnalysisResult): String =
        result.productDescription
            ?: result.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?: "\uC0C1\uD488 \uC815\uBCF4\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4."

    private fun showSearchCompleteScreen(currentDisplay: Display) {
        val assets = shoplyDisplayAssets()
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 32,
                    padding = 24,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    assets.searchColor?.let {
                        image(uri = it, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.NONE)
                    }
                    flexBox(padding = 14, background = FlexBoxBackground.CARD) {
                        text("\uAC80\uC0C9\uC774 \uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4.", style = TextStyle.BODY)
                    }
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "showSearchCompleteScreen sendContent failed: ${error.description}")
            }
        }
    }

    private fun showSplashScreen(autoAdvance: Boolean = true) {
        val currentDisplay = display ?: return
        val splashUri = metaPng("Splash.png")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 0,
                    padding = 0,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = splashUri, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.NONE)
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "showSplashScreen sendContent failed: ${error.description}")
            }
            if (autoAdvance) {
                delay(3000)
                if (display === currentDisplay && pendingDisplayResult == null && pendingDisplayProductConfirmation == null) {
                    showReadyScreen()
                }
            }
        }
    }

    private fun displayShoplyDetailedResult(currentDisplay: Display, result: AnalysisResult) {
        val assets = shoplyDisplayAssets()
        val shoplyProductName = productTitle(result)
        val shoplyBrandLine = productMeta(result)
        _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4\uC5D0 \uC0C1\uD488 \uC815\uBCF4\uB97C \uD45C\uC2DC\uD569\uB2C8\uB2E4.") }
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 8, crossAlignment = Alignment.STRETCH) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 12,
                        padding = 16,
                        background = FlexBoxBackground.CARD,
                        crossAlignment = Alignment.STRETCH,
                    ) {
                        text(shoplyProductName, style = TextStyle.HEADING)
                        shoplyBrandLine?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 16, background = FlexBoxBackground.CARD) {
                        text("\uC0C1\uD488 \uC694\uC57D", style = TextStyle.BODY)
                        text(productSummary(result).take(DISPLAY_CARD_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                    if (result.specifications.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 16, background = FlexBoxBackground.CARD) {
                            text("\uC8FC\uC694 \uC815\uBCF4", style = TextStyle.BODY)
                            result.specifications.take(3).forEach {
                                text(it.take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.positiveReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 16, background = FlexBoxBackground.CARD) {
                            text("\uAE0D\uC815\uD6C4\uAE30", style = TextStyle.BODY)
                            result.positiveReviews.take(1).forEach {
                                text(it.take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    if (result.negativeReviews.isNotEmpty()) {
                        flexBox(direction = Direction.COLUMN, gap = 4, padding = 16, background = FlexBoxBackground.CARD) {
                            text("\uBD80\uC815\uD6C4\uAE30", style = TextStyle.BODY)
                            result.negativeReviews.take(1).forEach {
                                text(it.take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    flexBox(direction = Direction.ROW, gap = 16, crossAlignment = Alignment.STRETCH) {
                        shoplyPngButton(
                            uri = assets.cameraRetake,
                            fallbackLabel = "\uB2E4\uC2DC \uCD2C\uC601",
                            fallbackIcon = IconName.TWO_ARROWS_CLOCKWISE,
                            onClick = { retakePhoto() },
                        )
                        shoplyPngButton(
                            uri = assets.priceInfo,
                            fallbackLabel = "\uAC00\uACA9\uC815\uBCF4",
                            fallbackIcon = IconName.CART,
                            onClick = { showPriceComparison(result) },
                        )
                    }
                }
            }
                .onSuccess { Log.i(TAG, "displayShoplyDetailedResult sendContent succeeded") }
                .onFailure { error, _ ->
                    Log.e(TAG, "displayShoplyDetailedResult sendContent failed: ${error.description}")
                    _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uC0C1\uD488 \uC815\uBCF4 \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
                }
        }
    }

    private fun displayShoplyPriceComparison(result: AnalysisResult) {
        val assets = shoplyDisplayAssets()
        val currentDisplay = display ?: run {
            Log.w(TAG, "displayShoplyPriceComparison called but display is not ready")
            _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4\uC5D0 \uAC00\uACA9\uBE44\uAD50\uB97C \uD45C\uC2DC\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.") }
            return
        }
        val shoplyProductName = productTitle(result)
        val shoplyBrandLine = productMeta(result)
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 12, padding = 8, crossAlignment = Alignment.STRETCH) {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 16, background = FlexBoxBackground.CARD) {
                        text(shoplyProductName, style = TextStyle.HEADING)
                        shoplyBrandLine?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    result.candidates.take(3).forEach { candidate ->
                        val saveLinkClick = candidate.linkUrl?.let {
                            {
                                saveCandidateLinkFromGlasses(
                                    productName = shoplyProductName,
                                    candidate = candidate,
                                    returnResult = result,
                                )
                            }
                        }
                        flexBox(
                            direction = Direction.ROW,
                            gap = 12,
                            padding = 16,
                            background = FlexBoxBackground.CARD,
                            crossAlignment = Alignment.CENTER,
                        ) {
                            flexBox(direction = Direction.COLUMN, gap = 4, flexGrow = 1f) {
                                text(candidate.store ?: "\uD310\uB9E4\uCC98", style = TextStyle.BODY, color = TextColor.SECONDARY)
                                text(candidate.price, style = TextStyle.HEADING)
                            }
                            button(
                                label = "\uB9C1\uD06C\uC800\uC7A5",
                                style = ButtonStyle.PRIMARY,
                                iconName = IconName.ARROW_DOWN_SHALLOW_U,
                                onClick = saveLinkClick ?: {},
                            )
                        }
                    }
                    flexBox(direction = Direction.ROW, gap = 16, crossAlignment = Alignment.STRETCH) {
                        shoplyPngButton(
                            uri = assets.cameraRetake,
                            fallbackLabel = "\uB2E4\uC2DC \uCD2C\uC601",
                            fallbackIcon = IconName.TWO_ARROWS_CLOCKWISE,
                            onClick = { retakePhoto() },
                        )
                        shoplyPngButton(
                            uri = assets.productInfo,
                            fallbackLabel = "\uC0C1\uD488 \uC815\uBCF4",
                            fallbackIcon = IconName.I_CIRCLE,
                            onClick = { displayShoplyDetailedResult(currentDisplay, result) },
                        )
                    }
                }
            }
                .onSuccess { Log.i(TAG, "displayShoplyPriceComparison sendContent succeeded") }
                .onFailure { error, _ ->
                    Log.e(TAG, "displayShoplyPriceComparison sendContent failed: ${error.description}")
                    _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uAC00\uACA9\uBE44\uAD50 \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
                }
        }
    }

    private fun displayShoplyProductConfirmation(result: AnalysisResult) {
        stopAnalysisMessages()
        val assets = shoplyDisplayAssets()
        val currentDisplay = display ?: run {
            Log.w(TAG, "displayShoplyProductConfirmation called but display is not ready")
            pendingDisplayProductConfirmation = result
            _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4\uC5D0 \uC0C1\uD488 \uD655\uC778 \uD654\uBA74\uC744 \uD45C\uC2DC\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.") }
            return
        }
        val shoplyProductName = productTitle(result)
        val shoplyBrandLine = productMeta(result)

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 32, padding = 8, crossAlignment = Alignment.STRETCH) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 16,
                        padding = 16,
                        background = FlexBoxBackground.CARD,
                        flexGrow = 1f,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.STRETCH,
                    ) {
                        text(shoplyProductName, style = TextStyle.HEADING)
                        shoplyBrandLine?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    }
                    flexBox(direction = Direction.COLUMN, gap = 24, crossAlignment = Alignment.CENTER) {
                        flexBox(padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uC774 \uC0C1\uD488\uC774 \uB9DE\uB098\uC694?", style = TextStyle.BODY)
                        }
                        flexBox(direction = Direction.ROW, gap = 16, crossAlignment = Alignment.STRETCH) {
                            shoplyPngButton(
                                uri = assets.cameraRetake,
                                fallbackLabel = "\uB2E4\uC2DC \uCD2C\uC601",
                                fallbackIcon = IconName.TWO_ARROWS_CLOCKWISE,
                                onClick = { retakePhoto() },
                            )
                            shoplyPngButton(
                                uri = assets.productInfo,
                                fallbackLabel = "\uC0C1\uD488 \uAC80\uC0C9",
                                fallbackIcon = IconName.CART,
                                onClick = { submitForSearch() },
                            )
                        }
                    }
                }
            }
                .onSuccess { Log.i(TAG, "displayShoplyProductConfirmation sendContent succeeded") }
                .onFailure { error, _ ->
                    Log.e(TAG, "displayShoplyProductConfirmation sendContent failed: ${error.description}")
                    _uiState.update { it.copy(statusMessage = "\uAE00\uB798\uC2A4 \uC0C1\uD488 \uD655\uC778 \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
                }
        }
    }

    private fun showPriceComparison(result: AnalysisResult) {
        _uiState.update { it.copy(showPriceComparison = true) }
        displayShoplyPriceComparison(result)
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
                            onClick = linkUrl?.let {
                                {
                                    saveCandidateLinkFromGlasses(
                                        productName = result.productName ?: result.originalProductName ?: result.headline,
                                        candidate = candidate,
                                    )
                                }
                            },
                        ) {
                            text("${index + 1}. ${candidate.store ?: "\uD310\uB9E4\uCC98"}", style = TextStyle.BODY)
                            text(candidate.price, style = TextStyle.HEADING)
                            candidate.title?.let { text("\uC0C1\uD488\uBA85: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                            candidate.currency?.let { text("\uD1B5\uD654: $it", style = TextStyle.BODY, color = TextColor.SECONDARY) }
                            candidate.isKoreanMarket?.let {
                                text("\uAD6D\uB0B4 \uC2DC\uC7A5: ${if (it) "\uC608" else "\uC544\uB2C8\uC624"}", style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
                        }
                    }
                    button(
                        label = "\uC7AC\uCD2C\uC601",
                        style = ButtonStyle.PRIMARY,
                        onClick = { retakePhoto() },
                    )
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "displayPriceComparison sendContent failed: ${error.description}")
                _uiState.update { it.copy(statusMessage = "\uB514\uC2A4\uD50C\uB808\uC774 \uAC00\uACA9\uBE44\uAD50 \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
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
                flexBox(direction = Direction.COLUMN, gap = 12) {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                        text("\uC774 \uC0C1\uD488\uC774 \uB9DE\uB098\uC694?", style = TextStyle.HEADING)
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
                _uiState.update { it.copy(statusMessage = "\uB514\uC2A4\uD50C\uB808\uC774 \uD655\uC778 \uD654\uBA74 \uC804\uC1A1 \uC2E4\uD328: ${error.description}") }
            }
        }
    }

    fun startSession(deviceId: DeviceIdentifier) {
        if (session != null || stream != null || display != null) {
            Log.i(TAG, "Clearing existing session before starting a new one.")
            stopSession()
        }
        _uiState.update { it.copy(statusMessage = "\uC138\uC158 \uC2DC\uC791 \uC911...") }

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
                _uiState.update { it.copy(statusMessage = "\uC138\uC158 \uC624\uB958: ${error.description}") }
                if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                    Wearables.openDATGlassesAppUpdate(getApplication())
                }
            }
    }

    private fun attachCapabilities() {
        val currentSession = session ?: return

        // ??????깅렰???????곕츣?? capturePhoto ??癲ル슢????+ (?????밸븶筌믩끃異? ?????ㅻ쿋????????쇈궘?觀愿??????
        // compressVideo = false ??raw ?????밸븶??????밸븶???텣???ш끽維뽳쭩??룸챶猶??Bitmap ??????????ш끽維뽳쭩??嚥????ㅼ뒧??????????H.265 ????거??異?????怨쀫뎐????.
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

        // ????거?醫귣쐪???????????곕츣??
        currentSession.addDisplay()
            .onSuccess { addedDisplay ->
                Log.i(TAG, "Display attach succeeded")
                display = addedDisplay
                viewModelScope.launch {
                    addedDisplay.state.collect { state ->
                        Log.i(TAG, "Display state changed: $state")
                        _uiState.update { it.copy(displayState = state) }
                        if (state == DisplayState.STARTED) flushPendingDisplayContent(addedDisplay)
                    }
                }
            }
            .onFailure { error, _ -> Log.e(TAG, "Display attach failed: ${error.description}") }
    }

    // ?????ㅻ쿋????????쇈궘?觀愿??????蹂κ텥????癲ル슢??節녿쨨??μ떝?띄몭??袁㏉떄?? capturePhoto ??videoStream ???嶺?????????????곷쿀???????
    // ?????? ?μ떝?띄몭??袁㏉떋????????쇈궘?觀愿??????蹂κ텥????μ떝?띄몭??袁㏉떄??????욱룕???轅붽틓?蹂ｋ눀?????????밸븶??????밸븶??뫢??關?쒎첎?癲??轅붽틓??? ??????껊땽??
    private fun flushPendingDisplayContent(currentDisplay: Display) {
        pendingDisplayProductConfirmation?.let { result ->
            pendingDisplayProductConfirmation = null
            Log.i(TAG, "Flushing pending product confirmation to glasses")
            displayShoplyProductConfirmation(result)
            return
        }
        pendingDisplayResult?.let { result ->
            pendingDisplayResult = null
            Log.i(TAG, "Flushing pending result to glasses")
            displayShoplyDetailedResult(currentDisplay, result)
            return
        }
        showSplashScreen()
    }

    private fun startPreview() {
        val currentStream = stream ?: return
        if (previewJob?.isActive == true) return
        // ?????밸븶?????Bitmap. conflate ???轅붽틓??影?뽧걤?????????곕춴???れ뫊鸚????쎛???轅붽틓????彛???????밸븶??????밸븶???????.
        // ?????쇨덫??Bitmap ??recycle ??????Compose ??????????????????袁ｋ쨨????????Β?띾쭡 GC ???轅붽틓????ш낄????
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
            showCapturingScreen()
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

        // ?轅붽틓?蹂ｋ눀?????????밸븶??????癲???ш끽維??λ궔?: ?????? ??????낅폆 ?????쇈궘?觀愿??videoStream ????蹂κ텥?? ?μ떝?띄몭??袁㏉떄??
        stopPreview()
        _uiState.update { it.copy(isCapturing = true, statusMessage = "\uCD2C\uC601 \uC911...") }
        showCapturingScreen()

        // ??????거?琉뱀땡?????욱룏嶺??????capturePhoto()???ル봿?? ??????깅렰???숆강?붺춯??쳱???嶺?????? ??β뼯援??????????/????뉖????????밸븶??????밸븶??뫢???ш끽維뽳쭩?좊쐪筌먲퐢????癲ル슢????
        // debug ?????????????????? ?關?쒎첎?癲??????????밸븶???????쇈궘?觀愿???????밸븶??????밸븶???텣?????얠뺏癲???????μ떝?롳쭗???
        // ???濚밸Ŧ?김???????맜???嚥싲갭큔?댁빢??release)??????숈??癲ル슢캉???capturePhoto()???????癲ル슢????
        if (BuildConfig.DEBUG && previewBitmap != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val file = saveBitmapToCache(previewBitmap)
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        pendingPhotoFile = file,
                        statusMessage = if (file != null) "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911..." else "\uC774\uBBF8\uC9C0 \uC800\uC7A5 \uC2E4\uD328",
                    )
                }
                if (file != null) {
                    showCapturedPhotoText()
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
                    // ????????????롪퍓肉?????遺븍き?寃밸윿???????繹먮굟爰????癲?嶺? LLM ?????밸븶筌믍됰튉??黎??筌??裕ㅒ?? [??β뼯援?????????????轅붽틓????筌뤾쑴???癲ル슢????
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            pendingPhotoFile = file,
                            statusMessage = if (file != null) "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911..." else "\uC774\uBBF8\uC9C0 \uC800\uC7A5 \uC2E4\uD328",
                        )
                    }
                    if (file != null) {
                    showCapturedPhotoText()
                        delay(CAPTURE_REVIEW_DELAY_MS)
                        identifyProduct(file)
                    } else {
                        startPreview()
                    }
                }
                .onFailure { error, _ ->
                    Log.e(TAG, "Photo capture failed: ${error.description}")
                    _uiState.update { it.copy(isCapturing = false, statusMessage = "\uCD2C\uC601 \uC2E4\uD328: ${error.description}") }
                    startPreview() // ??嚥싲갭큔??????? ???????쇈궘?觀愿??????
                }
        }
    }

    // [????? ???遺븍き?寃밸윿???????繹먮굟爰???????嶺???????留???????????????? ??嚥싲갭큔?????????쇈궘?觀愿???????덉땃??????沃섃뫗쨘??ш끽維뽫댚??
    private fun savePreviewFrameAsCapture(previewBitmap: Bitmap, rawPreviewFrame: RawPreviewFrame?) {
        stopPreview()
        _uiState.update { it.copy(isCapturing = true, statusMessage = "Capturing from last preview frame...") }
        showCapturingScreen()
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
                showCapturedPhotoText()
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
                statusMessage = "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911...",
            )
        }
        startPreview() // ??嚥싲갭큔?????????쇈궘?觀愿??????
        showReadyScreen()
    }

    // [??β뼯援???? ???遺븍き?寃밸윿???????繹먮굟爰??????LLM ?????????밸븶?④섣???꿔꺂????癒?떻??戮?츐??????밸븶筌믍됰튉????롪퍓肉????????거?쭛?????β뼯援??????ル꺄椰???癲?嶺??癲ル슢????
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
                statusMessage = "\uC0C1\uD488\uBA85 \uD655\uC778 \uC911...",
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
                    if (update.isFailed) error(update.message ?: "\uC0C1\uD488 \uBD84\uC11D \uC2E4\uD328")
                    if (update.isFinished) {
                        return@runCatching latestAnalysisResult ?: error("\uC0C1\uD488 \uBD84\uC11D \uACB0\uACFC\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.")
                    }
                }
                error("\uC0C1\uD488 \uBD84\uC11D \uC2DC\uAC04\uC774 \uCD08\uACFC\uB418\uC5C8\uC2B5\uB2C8\uB2E4.")
            }
                .onSuccess { identified ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = true,
                            showPriceComparison = false,
                            searchResult = identified,
                            statusMessage = "\uC0C1\uD488\uBA85 \uD655\uC778 \uC644\uB8CC",
                        )
                    }
                    displayShoplyProductConfirmation(identified)
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
                        headline = if (isConnectionError) "\uBD84\uC11D \uC11C\uBC84 \uC5F0\uACB0 \uC2E4\uD328" else "\uC0C1\uD488 \uBD84\uC11D \uC2E4\uD328",
                        message = throwable.message ?: "\uBD84\uC11D \uC11C\uBC84 \uC5F0\uACB0\uC744 \uD655\uC778\uD574\uC8FC\uC138\uC694.",
                    )
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = false,
                            showPriceComparison = false,
                            searchResult = result,
                            statusMessage = if (isConnectionError) "\uBD84\uC11D \uC11C\uBC84 \uC5F0\uACB0 \uC2E4\uD328" else "\uC0C1\uD488 \uBD84\uC11D \uC2E4\uD328",
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
                            statusMessage = "\uBD84\uC11D \uC2E4\uD328",
                        )
                    }
                    display?.let { currentDisplay ->
                        showSearchCompleteScreen(currentDisplay)
                        delay(700)
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
                        headline = "\uBD84\uC11D \uC2E4\uD328",
                        message = throwable.message ?: "\uBD84\uC11D \uC11C\uBC84 \uC5F0\uACB0\uC744 \uD655\uC778\uD574\uC8FC\uC138\uC694.",
                    )
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            awaitingProductConfirmation = false,
                            showPriceComparison = false,
                            searchResult = result,
                            statusMessage = "\uAC80\uC0C9 \uC2E4\uD328",
                        )
                    }
                    displayResult(result)
                }
        }
    }

    private fun displayAnalysisProgress(message: String, partial: AnalysisResult? = null) {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 16,
                    padding = 24,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 8,
                        padding = 18,
                        background = FlexBoxBackground.CARD,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                    ) {
                        text(message.ifBlank { "\uC0C1\uD488\uC744 \uBD84\uC11D \uC911\uC785\uB2C8\uB2E4." }, style = TextStyle.HEADING)
                    }
                    partial?.let { result ->
                        flexBox(
                            direction = Direction.COLUMN,
                            gap = 6,
                            padding = 16,
                            background = FlexBoxBackground.CARD,
                            crossAlignment = Alignment.STRETCH,
                        ) {
                            text(productTitle(result).take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY)
                            productMeta(result)?.let {
                                text(it.take(DISPLAY_TEXT_LIMIT), style = TextStyle.BODY, color = TextColor.SECONDARY)
                            }
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
            "\uC0C1\uD488 \uBD84\uC11D \uC911...",
            "\uC2A4\uD399 \uAC80\uC0C9 \uC911...",
            "\uD6C4\uAE30 \uC815\uB9AC \uC911...",
            "\uAC00\uACA9 \uAC80\uC0C9 \uC911...",
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
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 16,
                    padding = 24,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 8,
                        padding = 18,
                        background = FlexBoxBackground.CARD,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                    ) {
                        text("\uC0C1\uD488\uBA85 \uD655\uC778 \uC911...", style = TextStyle.HEADING)
                    }
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "displayLoading sendContent failed: ${error.description}")
            }
        }
    }
    fun stopSession() {
        stopAnalysisMessages()
        analysisJob?.cancel()
        analysisJob = null
        currentAnalysisJobId = null
        latestAnalysisResult = null
        pendingDisplayResult = null
        pendingDisplayProductConfirmation = null
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
        val guidelineUri = shoplyPng("Guideline.png")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.ROW,
                    gap = 24,
                    padding = 0,
                    crossAlignment = Alignment.STRETCH,
                ) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 0,
                        padding = 0,
                        flexGrow = 0.18f,
                        crossAlignment = Alignment.STRETCH,
                    ) {
                        image(uri = guidelineUri, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.NONE)
                    }
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 0,
                        padding = 24,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                        flexGrow = 1f,
                    ) {
                        flexBox(
                            direction = Direction.ROW,
                            gap = 10,
                            padding = 16,
                            background = FlexBoxBackground.CARD,
                            alignment = Alignment.CENTER,
                            crossAlignment = Alignment.CENTER,
                            flexShrink = 0f,
                            onClick = {
                                Log.i(TAG, "Ready capture card clicked from glasses")
                                capturePhoto()
                            },
                        ) {
                            icon(name = IconName.EYE, flexShrink = 0f)
                            text(
                                "\uC67C\uCABD \uB80C\uC988\uC55E\uC5D0 \uC0C1\uD488\uBC30\uCE58",
                                style = TextStyle.BODY,
                            )
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "showReadyScreen sendContent failed: ${error.description}")
            }
        }
    }
    private fun showCapturingScreen() {
        val currentDisplay = display ?: return
        val assets = shoplyDisplayAssets()
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.ROW,
                    gap = 32,
                    padding = 0,
                    crossAlignment = Alignment.STRETCH,
                ) {
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 0,
                        padding = 0,
                        flexGrow = 0.18f,
                        crossAlignment = Alignment.STRETCH,
                    ) {
                        assets.guideline?.let {
                            image(uri = it, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.NONE)
                        }
                    }
                    flexBox(
                        direction = Direction.COLUMN,
                        gap = 32,
                        padding = 24,
                        alignment = Alignment.CENTER,
                        crossAlignment = Alignment.CENTER,
                        flexGrow = 1f,
                    ) {
                        assets.cameraColor?.let {
                            image(uri = it, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.NONE)
                        }
                        flexBox(padding = 14, background = FlexBoxBackground.CARD) {
                            text("\uCD2C\uC601 \uC911\uC785\uB2C8\uB2E4.", style = TextStyle.BODY)
                        }
                    }
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "showCapturingScreen sendContent failed: ${error.description}")
            }
        }
    }

    private fun showCapturedPhotoText() {
        val currentDisplay = display ?: return
        val capturedUri = metaPng("Camera3.png")
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 0,
                    padding = 0,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    image(uri = capturedUri, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.NONE)
                }
            }
                .onSuccess { Log.i(TAG, "showCapturedPhotoText sendContent succeeded") }
                .onFailure { error, _ -> Log.w(TAG, "showCapturedPhotoText sendContent failed: ${error.description}") }
        }
    }
    private fun showCapturedPhoto(file: File) {
        val currentDisplay = display ?: return
        val uri = capturedPhotoDisplayUri() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 12) {
                    image(uri = uri, sizePreset = ImageSize.ICON, cornerRadius = CornerRadius.MEDIUM)
                    text("\uCD2C\uC601 \uC644\uB8CC", style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }.onFailure { error, _ ->
                Log.w(TAG, "Captured photo display failed: ${error.description}")
                currentDisplay.sendContent {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                        text("\uCD2C\uC601 \uC644\uB8CC", style = TextStyle.HEADING)
                        text("\uC774\uBBF8\uC9C0\uB97C \uBD84\uC11D\uD569\uB2C8\uB2E4.", style = TextStyle.BODY, color = TextColor.SECONDARY)
                    }
                }
            }
        }
    }

    private fun handleSessionError(error: DeviceSessionError) {
        Log.e(TAG, "Session error: ${error.description}")
        _uiState.update { it.copy(statusMessage = "\uC138\uC158 \uC624\uB958: ${error.description}") }
        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
            Wearables.openDATGlassesAppUpdate(getApplication())
        }
    }

    private fun onSessionStopped() {
        stopAnalysisMessages()
        analysisJob?.cancel()
        analysisJob = null
        pendingDisplayResult = null
        pendingDisplayProductConfirmation = null
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

    // raw VideoFrame ??Bitmap. ?嶺????????濚밸Þ??????? ?????????살퓢癲???癲ル슢????RGBA_8888 vs NV21/YUV).
    // ??꿔꺂??틝?????????????거?????濚밸Ŧ????????밸븶???? ?轅몄뫅????????? (?????밸븶筌믩끃異??????쇈궘?觀愿???????????????숈춻???????꿔꺂?€??⑤슣瑗?????밸븶????轅붽틓??熬곥끇釉??룸????猷???? ?????밸븶?癲?
    private fun videoFrameToBitmap(frame: VideoFrame): Bitmap? {
        if (frame.isCompressed || frame.isCodecConfig) return null
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null

        val buf = frame.buffer.duplicate().apply { rewind() }
        val remaining = buf.remaining()
        return try {
            when {
                // RGBA_8888: w*h*4 ??ш끽維뽳쭩????
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

    private fun saveCandidateLinkFromGlasses(
        productName: String,
        candidate: ProductCandidate,
        returnResult: AnalysisResult? = null,
    ) {
        val linkUrl = candidate.linkUrl ?: return
        val savedAt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date()) + " \uC800\uC7A5"
        viewModelScope.launch {
            SavedLinksStore.save(
                SavedLink(
                    productName = productName,
                    store = candidate.store ?: "\uD310\uB9E4\uCC98",
                    price = candidate.price.removeSuffix("\uC6D0").trim(),
                    linkUrl = linkUrl,
                    savedAt = savedAt,
                ),
            )
            Log.i(TAG, "Saved link from glasses pinch: $linkUrl")
            _uiState.update { it.copy(statusMessage = "\uB9C1\uD06C\uB97C \uC800\uC7A5\uD588\uC2B5\uB2C8\uB2E4.") }
            showGlassesSavedLinkToast(candidate, returnResult)
        }
    }

    private fun showGlassesSavedLinkToast(candidate: ProductCandidate, returnResult: AnalysisResult?) {
        val currentDisplay = display ?: return
        viewModelScope.launch(Dispatchers.IO) {
            currentDisplay.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 8, padding = 18, background = FlexBoxBackground.CARD) {
                    text("\uB9C1\uD06C \uC800\uC7A5 \uC644\uB8CC", style = TextStyle.HEADING)
                    text(candidate.store ?: "\uD310\uB9E4\uCC98", style = TextStyle.BODY, color = TextColor.SECONDARY)
                    text(candidate.price, style = TextStyle.BODY)
                    text("\uD3F0\uC758 \uC800\uC7A5\uD55C \uB9C1\uD06C\uC5D0\uC11C \uD655\uC778\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.", style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            }
                .onSuccess { Log.i(TAG, "showGlassesSavedLinkToast sendContent succeeded") }
                .onFailure { error, _ -> Log.w(TAG, "showGlassesSavedLinkToast sendContent failed: ${error.description}") }
            if (returnResult != null) {
                delay(3000)
                displayShoplyPriceComparison(returnResult)
            }
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
