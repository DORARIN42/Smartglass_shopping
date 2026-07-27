package com.example.raybanvision.data

// LLM/쇼핑 팀 → 디스플레이 팀 사이의 계약(Contract).
// AI는 상품명·가격을 생성하지 않고, 쇼핑 API 결과(ProductCandidate)를 그대로 담아 전달한다.

/** 분석 결과 상태. 개발 계획서 §7 주요 상태와 대응된다. */
enum class ResultStatus {
    MATCHED,          // 대표 상품 확정
    UNCERTAIN,        // 후보 2~3개 중 선택 필요
    RETRY_REQUIRED,   // 어두움·흔들림·단서 부족 → 재촬영 안내
    ERROR,            // 네트워크·서버·권한 오류
}

/** 쇼핑 API가 돌려준 상품 후보 1건. 상품명·가격·링크는 API 결과를 그대로 사용한다. */
data class ProductCandidate(
    val title: String,             // 상품명
    val price: String,             // 표시용 가격 문자열 (예: "12,900원")
    val store: String? = null,     // 판매처
    val imageUrl: String? = null,  // 썸네일 URL
    val linkUrl: String? = null,   // 구매/상세 링크
)

/**
 * 디스플레이에 표시할 분석 결과.
 * 글라스에는 요약(headline + 대표 후보 + 최저가)만, 상세는 폰에서 확인하도록 유도한다.
 */
data class AnalysisResult(
    val status: ResultStatus,
    val headline: String,                                // 글라스 상단 요약 문구
    val candidates: List<ProductCandidate> = emptyList(),
    val message: String? = null,                         // UNCERTAIN/RETRY/ERROR 안내 문구
) {
    /** 최저가(대표) 후보. candidates가 비어 있으면 null. */
    val topCandidate: ProductCandidate? get() = candidates.firstOrNull()
}

// LLM 연동 전 디스플레이 출력 테스트용 샘플 데이터 (쇼핑 도우미 도메인)
val SAMPLE_RESULT = AnalysisResult(
    status = ResultStatus.MATCHED,
    headline = "스탠리 퀜처 텀블러 1.18L",
    candidates = listOf(
        ProductCandidate(
            title = "스탠리 퀜처 H2.0 플로우스테이트 텀블러 1.18L",
            price = "45,900원",
            store = "네이버쇼핑 최저가",
            imageUrl = "https://www.facebook.com/assets/wearables_dat_display/oil.png", // TODO: 실제 상품 썸네일로 교체
            linkUrl = "https://search.shopping.naver.com/search/all?query=스탠리+퀜처+텀블러",
        ),
        ProductCandidate(
            title = "스탠리 퀜처 텀블러 1.18L (정품)",
            price = "48,000원",
            store = "쿠팡",
            linkUrl = "https://www.coupang.com",
        ),
    ),
)
