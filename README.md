# Smartglass Shopping — Ray-Ban Meta Display Android 앱

Meta Ray-Ban Display 글라스로 사진을 촬영하고, LLM 분석 결과(텍스트·이미지·링크)를 글라스 디스플레이에 출력하는 Android 앱입니다.

---

## 프로젝트 구조

```
app/src/main/java/com/example/raybanvision/
│
├── MainActivity.kt                   # 권한 요청 → DAT 초기화 → 네비게이션 (Connect → Main)
│
├── data/
│   └── AnalysisResult.kt             # LLM 결과 데이터 클래스 + 테스트용 SAMPLE_RESULT
│
├── wearables/
│   ├── WearablesRepository.kt        # Wearables SDK 상태 수집 (등록·디바이스·메타데이터)
│   ├── WearablesViewModel.kt         # 디바이스 목록, 등록, 펌웨어 업데이트
│   └── WearablesUiState.kt           # 등록 상태 / 디바이스 목록 UI 상태
│
├── session/
│   ├── SessionViewModel.kt           # 카메라 스트림 + 디스플레이 통합 세션 관리
│   └── SessionUiState.kt             # 세션·스트림·디스플레이 상태 / 촬영 파일 경로
│
└── ui/
    ├── ConnectScreen.kt              # 디바이스 선택 화면 (Display 가능 글라스만 표시)
    └── MainScreen.kt                 # 촬영 버튼 + 디스플레이 테스트 버튼
```

---

## 역할 분담

| 역할 | 담당 |
|---|---|
| 카메라 촬영 → 이미지 임시 저장 | 이 프로젝트 (나) |
| LLM 이미지 분석 | 팀원 |
| 분석 결과 → 글라스 디스플레이 출력 | 이 프로젝트 (나) |

---

## LLM / 쇼핑 팀 인터페이스

```kotlin
// LLM 팀이 collect — 사용자가 [검색]을 눌러 분석 요청한 이미지 파일.
// 촬영만 한 상태(재촬영/검색 결정 전)에서는 방출하지 않고, [검색] 시에만 새 값이 방출된다.
sessionViewModel.capturedPhotoFile  // StateFlow<File?>

// 분석 완료 후 호출 — 글라스 디스플레이에 결과 표시
sessionViewModel.displayResult(
    AnalysisResult(
        status   = ResultStatus.MATCHED,   // MATCHED / UNCERTAIN / RETRY_REQUIRED / ERROR
        headline = "스탠리 퀜처 텀블러 1.18L",
        candidates = listOf(               // 쇼핑 API 결과 (첫 항목 = 최저가/대표)
            ProductCandidate(
                title    = "스탠리 퀜처 H2.0 텀블러 1.18L",
                price    = "45,900원",
                store    = "네이버쇼핑 최저가",  // null 가능
                imageUrl = "https://...",        // null 가능
                linkUrl  = "https://...",        // null 가능
            ),
        ),
        message = null,   // RETRY_REQUIRED / ERROR 일 때 안내 문구
    )
)
```

상태별 글라스 출력:
- **MATCHED / UNCERTAIN** — 대표 후보의 썸네일·상품명·최저가 + "폰에서 자세히 보기" 버튼 (링크 탭 시 폰 브라우저에서 열림). UNCERTAIN이면 후보 개수 안내를 추가로 표시.
- **RETRY_REQUIRED** — 재촬영 안내 문구.
- **ERROR** — 오류 안내 문구.

> AI는 상품명·가격을 생성하지 않고 검색 속성만 만들며, `ProductCandidate`에는 쇼핑 API 실제 결과를 담는다 (개발 계획서 §6).

---

## 화면 흐름

```
ConnectScreen
  └─ Meta AI 등록 → 디스플레이 글라스 선택
        ↓
MainScreen
  ├─ 라이브 카메라 프리뷰 (임시, 목 디바이스 테스트용)
  ├─ [사진 촬영] → 미리보기 표시 → 다음 중 선택:
  │     ├─ [재촬영] → 캐시 파일 삭제 → 라이브 프리뷰 복귀
  │     └─ [검색]   → capturedPhotoFile 방출(LLM 전송) + 글라스 "분석 중..." 표시
  ├─ LLM 팀이 displayResult() 호출 → 글라스에 결과 표시
  └─ [샘플 결과 전송] → 디스플레이 출력 테스트 (LLM 연동 전)
```

---

## 시작하기

### 1. local.properties 설정

`local.properties.template`을 복사해서 `local.properties`로 저장 후 아래 값 입력:

```properties
sdk.dir=/Users/yourname/Library/Android/sdk
github_username=YOUR_GITHUB_USERNAME
github_token=YOUR_GITHUB_PAT_HERE   # classic PAT, read:packages 스코프 필요
```

### 2. Meta AI 앱 설정

- Meta AI 앱 설치 (테스트 기기)
- **Developer Mode** 활성화 (개발 빌드용)
- Ray-Ban Meta Display 글라스 페어링

### 3. 빌드 & 실행

```bash
./gradlew assembleDebug
./gradlew installDebug
```

### 4. 디스플레이 테스트

앱 실행 → 글라스 선택 → 연결 완료 후 **"샘플 결과 글라스에 전송"** 버튼으로 텍스트·이미지·링크 출력 확인

---

## 사용 SDK

- **mwdat-core** `0.8.0` — 등록, 권한, 세션
- **mwdat-camera** `0.8.0` — 카메라 스트리밍, 사진 캡처
- **mwdat-display** `0.8.0` — 글라스 디스플레이 출력

참고: [Meta Wearables DAT 개발자 문서](https://wearables.developer.meta.com/docs/develop/)
