package com.example.raybanvision.network

import com.example.raybanvision.data.AnalysisResult
import com.example.raybanvision.data.ProductCandidate
import com.example.raybanvision.data.ResultStatus
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.delay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

data class ProductAnalysisResult(
    val strategy: String? = null,
    val status: String? = null,
    val product_name: String? = null,
    val original_product_name: String? = null,
    val identified_product_name: String? = null,
    val detected_product_name: String? = null,
    val recognized_product_name: String? = null,
    val item_name: String? = null,
    val name: String? = null,
    val brand: String? = null,
    val original_brand: String? = null,
    val model_number: String? = null,
    val category: String? = null,
    val product_description: String? = null,
    val specifications: List<String>? = null,
    val confidence: Double? = null,
    val prices: List<PriceInfo>? = null,
    @SerializedName(
        value = "image_url",
        alternate = ["imageUrl"],
    )
    val image_url: String? = null,
    @SerializedName(
        value = "uploaded_image_url",
        alternate = ["uploadedImageUrl", "imgbb_url", "imgbbUrl", "display_url", "displayUrl", "captured_image_url", "capturedImageUrl"],
    )
    val uploaded_image_url: String? = null,
    val positive_reviews: List<String>? = null,
    val negative_reviews: List<String>? = null,
    val average_rating: Double? = null,
    val message: String? = null,
)

data class PriceInfo(
    val price: String? = null,
    val currency: String? = null,
    val store: String? = null,
    val link: String? = null,
    @SerializedName(
        value = "image_url",
        alternate = ["imageUrl", "thumbnail", "thumbnail_url", "thumbnailUrl", "thumb"],
    )
    val image_url: String? = null,
    val is_korean_market: Boolean? = null,
)

data class AnalyzeStartResponse(
    val job_id: String,
)

data class AnalyzeStatusResponse(
    val job_id: String? = null,
    val state: String? = null,
    val status: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val error: JsonElement? = null,
    val detail: JsonElement? = null,
    val result: ProductAnalysisResult? = null,
    val strategy: String? = null,
    val product_name: String? = null,
    val original_product_name: String? = null,
    val identified_product_name: String? = null,
    val detected_product_name: String? = null,
    val recognized_product_name: String? = null,
    val item_name: String? = null,
    val name: String? = null,
    val brand: String? = null,
    val original_brand: String? = null,
    val model_number: String? = null,
    val category: String? = null,
    val product_description: String? = null,
    val specifications: List<String>? = null,
    val confidence: Double? = null,
    val prices: List<PriceInfo>? = null,
    @SerializedName(
        value = "image_url",
        alternate = ["imageUrl"],
    )
    val image_url: String? = null,
    @SerializedName(
        value = "uploaded_image_url",
        alternate = ["uploadedImageUrl", "imgbb_url", "imgbbUrl", "display_url", "displayUrl", "captured_image_url", "capturedImageUrl"],
    )
    val uploaded_image_url: String? = null,
    val positive_reviews: List<String>? = null,
    val negative_reviews: List<String>? = null,
    val average_rating: Double? = null,
)

data class AnalysisStatusUpdate(
    val result: AnalysisResult?,
    val isPausedIdentity: Boolean,
    val isFinished: Boolean,
    val isFailed: Boolean,
    val message: String?,
    val state: String?,
    val stage: String?,
    val rawStatus: String?,
)

private interface ShoppingApi {
    @Multipart
    @POST("analyze/start")
    suspend fun startAnalysis(@Part file: MultipartBody.Part): AnalyzeStartResponse

    @GET("analyze/status/{job_id}")
    suspend fun analysisStatus(@Path("job_id") jobId: String): AnalyzeStatusResponse

    @POST("analyze/continue/{job_id}")
    suspend fun continueAnalysis(@Path("job_id") jobId: String)

    @POST("analyze/cancel/{job_id}")
    suspend fun cancelAnalysis(@Path("job_id") jobId: String)
}

class ShoppingApiClient(baseUrl: String) {
    private val api = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ShoppingApi::class.java)

    suspend fun analyzeImage(
        imageFile: File,
        onUpdate: (AnalysisResult) -> Unit = {},
    ): AnalysisResult {
        val jobId = startAnalysis(imageFile)
        var latestResult: AnalysisResult? = null

        repeat(MAX_STATUS_POLLS) {
            delay(STATUS_POLL_INTERVAL_MS)

            val update = getAnalysisStatus(jobId)
            update.result?.let { partial ->
                latestResult = latestResult.mergeWith(partial)
                onUpdate(latestResult!!)
            }

            if (update.isPausedIdentity) {
                return latestResult ?: error("Product identity paused without result.")
            }

            if (update.isFinished) {
                return latestResult ?: error("Analysis completed without result.")
            }

            if (update.isFailed) {
                error(update.message ?: "Analysis job failed.")
            }
        }

        return latestResult ?: error("Analysis timed out.")
    }

    suspend fun startAnalysis(imageFile: File): String {
        return api.startAnalysis(imageFile.toMultipartPart()).job_id
    }

    suspend fun getAnalysisStatus(jobId: String): AnalysisStatusUpdate {
        val status = api.analysisStatus(jobId)
        val partialProduct = status.toProductAnalysisResult()
        val result = partialProduct.takeIf { it.hasDisplayableContent() }?.toAnalysisResult()

        return AnalysisStatusUpdate(
            result = result,
            isPausedIdentity = status.isPausedIdentity(),
            isFinished = status.isFinished(),
            isFailed = status.isFailed(),
            message = status.errorMessage(),
            state = status.state,
            stage = status.stage,
            rawStatus = status.status,
        )
    }

    suspend fun continueAnalysis(jobId: String) {
        api.continueAnalysis(jobId)
    }

    suspend fun cancelAnalysis(jobId: String) {
        api.cancelAnalysis(jobId)
    }

    private companion object {
        private const val STATUS_POLL_INTERVAL_MS = 1500L
        private const val MAX_STATUS_POLLS = 120
    }
}

private fun File.toMultipartPart(): MultipartBody.Part {
    val mediaType = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }.toMediaTypeOrNull()
    val requestBody = asRequestBody(mediaType)
    return MultipartBody.Part.createFormData(
        name = "file",
        filename = name.ifBlank { "product.png" },
        body = requestBody,
    )
}

fun AnalysisResult?.mergeWith(next: AnalysisResult): AnalysisResult {
    val previous = this
    if (previous == null) return next

    return AnalysisResult(
        status = next.status,
        headline = if (next.headline != "분석 결과") next.headline else previous.headline,
        candidates = next.candidates.takeIf { it.isNotEmpty() } ?: previous.candidates,
        message = next.message ?: previous.message,
        imageUrl = next.imageUrl ?: previous.imageUrl,
        rawStatus = next.rawStatus ?: previous.rawStatus,
        strategy = next.strategy ?: previous.strategy,
        productName = next.productName ?: previous.productName,
        originalProductName = next.originalProductName ?: previous.originalProductName,
        brand = next.brand ?: previous.brand,
        originalBrand = next.originalBrand ?: previous.originalBrand,
        modelNumber = next.modelNumber ?: previous.modelNumber,
        category = next.category ?: previous.category,
        productDescription = next.productDescription ?: previous.productDescription,
        specifications = next.specifications.takeUnless { it.isNullOrEmpty() } ?: previous.specifications,
        confidence = next.confidence ?: previous.confidence,
        positiveReviews = next.positiveReviews.takeUnless { it.isEmpty() } ?: previous.positiveReviews,
        negativeReviews = next.negativeReviews.takeUnless { it.isEmpty() } ?: previous.negativeReviews,
        averageRating = next.averageRating ?: previous.averageRating,
    )
}

private fun AnalyzeStatusResponse.isFinished(): Boolean {
    val value = state.orEmpty().trim().lowercase()
    return value in listOf("complete", "completed", "done", "success", "succeeded", "finished")
}

private fun AnalyzeStatusResponse.isPausedIdentity(): Boolean {
    return state?.trim()?.lowercase() == "paused_identity" &&
        stage?.trim()?.lowercase() == "identity_ready"
}

private fun AnalyzeStatusResponse.isFailed(): Boolean {
    val value = state.orEmpty().trim().lowercase()
    return value in listOf("error", "failed", "failure", "cancelled", "canceled")
}

private fun AnalyzeStatusResponse.errorMessage(): String? {
    return listOfNotNull(
        message,
        error.toReadableText(),
        detail.toReadableText(),
        stage,
    ).firstOrNull { it.isNotBlank() }
}

private fun JsonElement?.toReadableText(): String? {
    return when {
        this == null || isJsonNull -> null
        isJsonPrimitive -> asString
        else -> toString()
    }
}

private fun String?.toDisplayStatusText(): String? {
    val value = this?.toUiText()?.takeIf { it.isNotBlank() } ?: return null
    return when (value.trim().lowercase()) {
        "uploading_image" -> "이미지 업로드 중"
        "searching_lens" -> "일치하는 상품 찾는 중"
        "identifying_product" -> "상품명 확인 중"
        "identity_ready" -> "상품 확인 완료"
        "paused_identity" -> "상품 확인 대기 중"
        "searching_specs" -> "스펙 검색 중"
        "summarizing_reviews" -> "후기 정리 중"
        "searching_prices" -> "가격 검색 중"
        "checking_image_with_claude" -> "이미지 확인 중"
        "done" -> "분석 완료"
        "running" -> "분석 중"
        "finished", "completed" -> "분석 완료"
        "failed", "failure", "error" -> "분석 실패"
        else -> if (value.contains('_')) "분석 중" else value
    }
}

private fun AnalyzeStatusResponse.toProductAnalysisResult(): ProductAnalysisResult {
    val body = result
    return ProductAnalysisResult(
        strategy = body?.strategy ?: strategy,
        status = body?.status ?: status ?: state,
        product_name = body?.product_name ?: product_name,
        original_product_name = body?.original_product_name ?: original_product_name,
        identified_product_name = body?.identified_product_name ?: identified_product_name,
        detected_product_name = body?.detected_product_name ?: detected_product_name,
        recognized_product_name = body?.recognized_product_name ?: recognized_product_name,
        item_name = body?.item_name ?: item_name,
        name = body?.name ?: name,
        brand = body?.brand ?: brand,
        original_brand = body?.original_brand ?: original_brand,
        model_number = body?.model_number ?: model_number,
        category = body?.category ?: category,
        product_description = body?.product_description ?: product_description,
        specifications = body?.specifications ?: specifications,
        confidence = body?.confidence ?: confidence,
        prices = body?.prices ?: prices,
        image_url = body?.image_url ?: image_url,
        uploaded_image_url = body?.uploaded_image_url ?: uploaded_image_url,
        positive_reviews = body?.positive_reviews ?: positive_reviews,
        negative_reviews = body?.negative_reviews ?: negative_reviews,
        average_rating = body?.average_rating ?: average_rating,
        message = body?.message ?: message ?: stage,
    )
}

private fun ProductAnalysisResult.hasDisplayableContent(): Boolean {
    return listOf(
        strategy,
        product_name,
        original_product_name,
        identified_product_name,
        detected_product_name,
        recognized_product_name,
        item_name,
        name,
        brand,
        original_brand,
        model_number,
        category,
        product_description,
        message,
    ).any { !it.isNullOrBlank() } ||
        !specifications.isNullOrEmpty() ||
        !prices.isNullOrEmpty() ||
        !positive_reviews.isNullOrEmpty() ||
        !negative_reviews.isNullOrEmpty() ||
        confidence != null ||
        average_rating != null
}

fun ProductAnalysisResult.toAnalysisResult(): AnalysisResult {
    val productName = listOf(
        product_name,
        original_product_name,
        identified_product_name,
        detected_product_name,
        recognized_product_name,
        item_name,
        name,
    ).mapNotNull { it?.takeUnless { value -> value.isProgressMessage() } }
        .firstOrNull { it.isNotBlank() }
    val displayName = listOfNotNull(brand ?: original_brand, productName)
        .joinToString(" ")
        .ifBlank { productName ?: message ?: "분석 결과" }

    val uploadedImageUrl = uploaded_image_url.toUiText()
        ?.takeIf { it.isLikelyDirectImageUrl() }
        ?: image_url.toUiText()?.takeIf { it.isLikelyImgbbDirectImageUrl() }

    val priceCandidates = prices.orEmpty()
        .filter { it.price != null || it.store != null || it.link != null }
        .map { price ->
            ProductCandidate(
                title = (productName ?: displayName).toUiText() ?: "분석 결과",
                price = price.price.toUiText()?.ifBlank { "-" } ?: "-",
                store = price.store.toUiText(),
                imageUrl = price.image_url.toUiText(),
                linkUrl = price.link,
                currency = price.currency.toUiText(),
                isKoreanMarket = price.is_korean_market,
            )
        }

    val candidates = if (priceCandidates.isNotEmpty()) {
        priceCandidates
    } else if (false && (!productName.isNullOrBlank() || displayName.isNotBlank())) {
        listOf(
            ProductCandidate(
                title = (productName ?: displayName).toUiText() ?: "상품명 확인됨",
                price = "-",
                store = "가격 분석 전",
            ),
        )
    } else {
        emptyList()
    }

    val normalizedStatus = status?.lowercase()
    val resultStatus = when {
        normalizedStatus in listOf("error", "failed", "failure") -> ResultStatus.ERROR
        normalizedStatus in listOf("retry", "retry_required", "not_found", "no_product", "not_a_product") -> ResultStatus.RETRY_REQUIRED
        productName == null && displayName.isBlank() -> ResultStatus.RETRY_REQUIRED
        confidence != null && confidence < 0.55 && candidates.size > 1 -> ResultStatus.UNCERTAIN
        else -> ResultStatus.MATCHED
    }

    val details = buildList {
        product_description?.takeIf { it.isNotBlank() }?.let(::add)
        specifications.orEmpty().filter { it.isNotBlank() }.take(4).forEach(::add)
        average_rating?.let { add("평점: $it") }
        positive_reviews.orEmpty().filter { it.isNotBlank() }.take(2).forEach { add("장점: $it") }
        negative_reviews.orEmpty().filter { it.isNotBlank() }.take(2).forEach { add("단점: $it") }
    }.joinToString("\n")

    return AnalysisResult(
        status = resultStatus,
        headline = displayName.toUiText() ?: "분석 결과",
        candidates = candidates,
        message = (message ?: details.ifBlank { null }).toDisplayStatusText(),
        imageUrl = uploadedImageUrl,
        rawStatus = status.toDisplayStatusText(),
        strategy = strategy.toUiText(),
        productName = productName.toUiText(),
        originalProductName = original_product_name.toUiText(),
        brand = brand.toUiText(),
        originalBrand = original_brand.toUiText(),
        modelNumber = model_number.toUiText(),
        category = category.toUiText(),
        productDescription = product_description.toUiText(),
        specifications = specifications.orEmpty().mapNotNull { it.toUiText() },
        confidence = confidence,
        positiveReviews = positive_reviews.orEmpty().mapNotNull { it.toUiText() },
        negativeReviews = negative_reviews.orEmpty().mapNotNull { it.toUiText() },
        averageRating = average_rating,
    )
}

private fun String?.toUiText(): String? = this?.replace(Regex("claude", RegexOption.IGNORE_CASE), "AI")

private fun String.isLikelyDirectImageUrl(): Boolean {
    val normalized = trim().lowercase()
    if (!normalized.startsWith("https://")) return false
    return normalized.matches(Regex(""".*\.(png|jpe?g|webp|gif)(\?.*)?$"""))
}

private fun String.isLikelyImgbbDirectImageUrl(): Boolean {
    val normalized = trim().lowercase()
    return normalized.contains("i.ibb.co/") && isLikelyDirectImageUrl()
}

private fun String.isProgressMessage(): Boolean {
    val normalized = trim().lowercase()
    return listOf(
        "업로드",
        "upload",
        "식별",
        "확인 중",
        "확인중",
        "분석 중",
        "분석중",
        "검색 중",
        "검색중",
        "정리 중",
        "정리중",
        "processing",
        "running",
        "pending",
        "started",
    ).any { normalized.contains(it) }
}
