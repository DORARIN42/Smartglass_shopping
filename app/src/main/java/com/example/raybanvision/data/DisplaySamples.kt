package com.example.raybanvision.data

// 디스플레이 레이아웃을 상태별로 눈으로 확인하기 위한 샘플 모음.
// LLM/쇼핑 연동 전에도 displayResult(sample)로 각 화면을 글라스에 띄워볼 수 있다.
// (물리 글라스가 없으면 MockDeviceKit로 mock 글라스를 페어링한 뒤 동일하게 사용 — 하단 주석 참고.)
//
// 분석 결과는 5개 카테고리로 나뉘어 넘어온다.
//  - 카테고리별 정의 필드 + 공통 후기(장점/단점)는 message에 담는다.
//  - 공통 구매처 예시(가격/판매처/링크) 3건은 candidates에 담는다.
// 링크는 각 쇼핑몰의 검색 결과 페이지(항상 유효)로 넣었고, 가격은 샘플 예시값이다.

/** 화면에 표시할 이름 + 실제 전송할 결과. DisplayTestScreen이 이 목록을 순회한다. */
data class DisplaySample(
    val label: String,
    val result: AnalysisResult,
)

/** DisplayTestScreen에서 버튼으로 노출되는 전체 샘플 목록. (카테고리별 1건) */
val DISPLAY_SAMPLES: List<DisplaySample> = listOf(
    /* 식품 샘플만 활성화 — 다른 카테고리를 보려면 원하는 DisplaySample을 이 주석 밖으로 옮기면 됨.
    // ── 1. 전자제품 & 기기 ──────────────────────────────────────────────
    // 필드: 모델명/제조사, 주요 사양(CPU/GPU·RAM·용량·화면크기 등), 색상/옵션, 무게
    DisplaySample(
        "전자제품 · Galaxy Watch 8 Classic",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "Samsung Galaxy Watch 8 Classic (46mm)",
            message = "모델명/제조사: Galaxy Watch 8 Classic (SM-L500) / 삼성전자\n" +
                "주요 사양: Exynos W1000 · RAM 2GB · 저장 64GB · 1.34\" 원형 AMOLED\n" +
                "색상/옵션: 블랙, 화이트 / 46mm·44mm\n" +
                "무게: 63.5g\n" +
                "후기 장점: 프리미엄 디자인, 회전 베젤 조작 편리, 야외 가독성 우수\n" +
                "후기 단점: 배터리 하루 남짓, 가격대 높음",
            candidates = listOf(
                ProductCandidate(
                    title = "Galaxy Watch 8 Classic 46mm",
                    price = "380,000원",
                    store = "쿠팡",
                    linkUrl = "https://www.coupang.com/np/search?q=갤럭시+워치8+클래식+46mm",
                ),
                ProductCandidate(
                    title = "Galaxy Watch 8 Classic 46mm",
                    price = "399,000원",
                    store = "삼성닷컴",
                    linkUrl = "https://www.samsung.com/sec/watches/all-watches/?galaxy-watch8-classic",
                ),
                ProductCandidate(
                    title = "Galaxy Watch 8 Classic 46mm",
                    price = "378,000원",
                    store = "다나와",
                    linkUrl = "https://search.danawa.com/dsearch.php?query=갤럭시+워치8+클래식+46mm",
                ),
            ),
        ),
    ),

    // ── 2. 뷰티 ────────────────────────────────────────────────────────
    // 필드: 제조사/브랜드, 용량(ml or g), 주요 성분(탑 3-5), 피부타입/타겟
    DisplaySample(
        "뷰티 · 토리든 다이브인 세럼",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "토리든 다이브인 저분자 히알루론산 세럼",
            message = "제조사/브랜드: 토리든 (Torriden)\n" +
                "용량: 50ml\n" +
                "주요 성분: 저분자 히알루론산 5종, 판테놀, 마데카소사이드, 베타글루칸\n" +
                "피부타입/타겟: 건성·수분부족 지성, 예민 피부\n" +
                "후기 장점: 끈적임 없는 빠른 흡수, 진정 효과, 가성비 우수\n" +
                "후기 단점: 지속 보습력은 아쉬움, 대용량 펌프 아쉬움",
            candidates = listOf(
                ProductCandidate(
                    title = "토리든 다이브인 세럼 50ml",
                    price = "15,900원",
                    store = "올리브영",
                    linkUrl = "https://www.oliveyoung.co.kr/store/search/getSearchMain.do?query=토리든+다이브인+세럼",
                ),
                ProductCandidate(
                    title = "토리든 다이브인 세럼 50ml",
                    price = "14,500원",
                    store = "쿠팡",
                    linkUrl = "https://www.coupang.com/np/search?q=토리든+다이브인+세럼",
                ),
                ProductCandidate(
                    title = "토리든 다이브인 세럼 50ml",
                    price = "15,200원",
                    store = "네이버쇼핑",
                    linkUrl = "https://search.shopping.naver.com/search/all?query=토리든+다이브인+세럼",
                ),
            ),
        ),
    ),

    */
    // ── 3. 식품 & 음료 ─────────────────────────────────────────────────
    // 필드: 제조사/브랜드, 용량/무게(g·ml·개수), 주요 성분/함량, 알레르기 정보
    DisplaySample(
        "식품 · 오리온 초코파이情",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "오리온 초코파이情 (12개입)",
            message = "제조사/브랜드: 오리온\n" +
                "용량/무게: 468g (39g × 12개입)\n" +
                "주요 성분/함량: 밀가루, 설탕, 마시멜로 / 1개당 172kcal, 당류 15g\n" +
                "알레르기 정보: 밀·대두·우유·계란 함유\n" +
                "후기 장점: 부드러운 마시멜로, 호불호 적음, 개별포장 간편\n" +
                "후기 단점: 당류 높음, 여름철 물러짐",
            candidates = listOf(
                ProductCandidate(
                    title = "오리온 초코파이情 468g (12개입)",
                    price = "5,480원",
                    store = "쿠팡",
                    linkUrl = "https://www.coupang.com/np/search?q=오리온+초코파이+12개입",
                ),
                ProductCandidate(
                    title = "오리온 초코파이情 468g (12개입)",
                    price = "5,680원",
                    store = "이마트몰",
                    linkUrl = "https://emart.ssg.com/search.ssg?query=오리온+초코파이+12개입",
                ),
                ProductCandidate(
                    title = "오리온 초코파이情 468g (12개입)",
                    price = "5,500원",
                    store = "네이버쇼핑",
                    linkUrl = "https://search.shopping.naver.com/search/all?query=오리온+초코파이+12개입",
                ),
            ),
        ),
    ),

    /* 식품만 활성화 — 나머지 비활성화.
    // ── 4. 도서 & 문구 ─────────────────────────────────────────────────
    // 필드: 저자/출판사, 페이지 수(도서), 수량(문구), 언어, 출판년도(도서)
    DisplaySample(
        "도서 · 사피엔스",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "사피엔스 — 유발 하라리",
            message = "저자/출판사: 유발 하라리 / 김영사\n" +
                "페이지 수: 636쪽\n" +
                "언어: 한국어 (조현욱 옮김)\n" +
                "출판년도: 2015년 (개정판)\n" +
                "후기 장점: 통찰력 있는 서술, 매끄러운 번역, 재독 가치 높음\n" +
                "후기 단점: 분량 많아 완독 부담, 일부 주장은 논쟁적",
            candidates = listOf(
                ProductCandidate(
                    title = "사피엔스 (유발 하라리)",
                    price = "20,700원",
                    store = "교보문고",
                    linkUrl = "https://search.kyobobook.co.kr/search?keyword=사피엔스+유발+하라리",
                ),
                ProductCandidate(
                    title = "사피엔스 (유발 하라리)",
                    price = "19,800원",
                    store = "예스24",
                    linkUrl = "https://www.yes24.com/product/search?query=사피엔스+유발+하라리",
                ),
                ProductCandidate(
                    title = "사피엔스 (유발 하라리)",
                    price = "19,800원",
                    store = "알라딘",
                    linkUrl = "https://www.aladin.co.kr/search/wsearchresult.aspx?SearchWord=사피엔스+유발+하라리",
                ),
            ),
        ),
    ),

    // ── 5. 기타 ────────────────────────────────────────────────────────
    // 필드: 제품명/타입, 크기/규격, 재질, 색상/옵션
    DisplaySample(
        "기타 · 스탠리 퀜처 텀블러",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "스탠리 퀜처 H2.0 플로우스테이트 텀블러",
            message = "제품명/타입: 스탠리 퀜처 H2.0 진공 보온·보냉 텀블러\n" +
                "크기/규격: 1.18L (40oz)\n" +
                "재질: 18/8 스테인리스 스틸 (BPA-free 뚜껑)\n" +
                "색상/옵션: 로즈쿼츠, 차콜, 크림 외\n" +
                "후기 장점: 압도적 보냉력, 대용량, 차량 컵홀더 호환\n" +
                "후기 단점: 무겁고 부피 큼, 정품 구분 필요",
            candidates = listOf(
                ProductCandidate(
                    title = "스탠리 퀜처 H2.0 텀블러 1.18L",
                    price = "45,900원",
                    store = "네이버쇼핑",
                    linkUrl = "https://search.shopping.naver.com/search/all?query=스탠리+퀜처+텀블러+1.18L",
                ),
                ProductCandidate(
                    title = "스탠리 퀜처 H2.0 텀블러 1.18L",
                    price = "48,000원",
                    store = "쿠팡",
                    linkUrl = "https://www.coupang.com/np/search?q=스탠리+퀜처+텀블러+1.18L",
                ),
                ProductCandidate(
                    title = "스탠리 퀜처 H2.0 텀블러 1.18L",
                    price = "46,500원",
                    store = "다나와",
                    linkUrl = "https://search.danawa.com/dsearch.php?query=스탠리+퀜처+텀블러+1.18L",
                ),
            ),
        ),
    ),
    */
)
