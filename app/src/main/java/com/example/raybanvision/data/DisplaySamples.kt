package com.example.raybanvision.data

// 디스플레이 레이아웃을 상태별로 눈으로 확인하기 위한 샘플 모음.
// LLM/쇼핑 연동 전에도 displayResult(sample)로 각 화면을 글라스에 띄워볼 수 있다.
// (물리 글라스가 없으면 MockDeviceKit로 mock 글라스를 페어링한 뒤 동일하게 사용 — 하단 주석 참고.)

/** 화면에 표시할 이름 + 실제 전송할 결과. DisplayTestScreen이 이 목록을 순회한다. */
data class DisplaySample(
    val label: String,
    val result: AnalysisResult,
)

/** DisplayTestScreen에서 버튼으로 노출되는 전체 샘플 목록. */
val DISPLAY_SAMPLES: List<DisplaySample> = listOf(
    DisplaySample(
        "Samsung Galaxy Watch 8 Classic",
        AnalysisResult(
            status = ResultStatus.MATCHED,
            headline = "Samsung Galaxy Watch 8 Classic (46mm)",
            message = "원형 AMOLED 디스플레이(1.34인치, 3000니트)와 물리 회전 베젤이 특징. 심박계, 혈중산소, ECG, 스트레스 측정 가능. Exynos W1000 칩셋, 64GB 저장공간.\n\n장점: 프리미엄 디자인, 야외 가독성 우수, 건강 추적 기능 강화\n단점: AOD 활성화 시 배터리 소비, 가격이 높은 편",
            candidates = listOf(
                ProductCandidate(
                    title = "Samsung Galaxy Watch 8 Classic (46mm)",
                    price = "380,000원",
                    store = "Coupang (최저가)",
                    linkUrl = "https://www.coupang.com/np/search?q=갤럭시+워치8+클래식+46mm",
                ),
                ProductCandidate(
                    title = "Samsung Galaxy Watch 8 Classic (46mm)",
                    price = "399,000원",
                    store = "Samsung 공식스토어",
                    linkUrl = "https://www.samsung.com/sec/watches/galaxywatch8-l500/SM-L500NZKAKOO/",
                ),
                ProductCandidate(
                    title = "Samsung Galaxy Watch 8 Classic (46mm)",
                    price = "380,000원",
                    store = "다나와",
                    linkUrl = "https://prod.danawa.com/info/?pcode=93383717",
                ),
            ),
        ),
    ),
)
