# Design.md — `root/` 디자인 시스템 ↔ 실제 앱 통합 가이드

> 이 문서는 디자이너(`root/` 폴더 제작)와 개발팀이 같은 그림을 보기 위한 다리 역할을 합니다.
> **`root/`가 디자인 결정의 유일한 기준(source of truth)**이고, 이 문서는 "그 결정이 실제 코드 어디에, 어떻게 반영되어야 하는지"를 추적합니다.
> 최종 업데이트: 2026-07-28

---

## 0. 필수 규칙 (예외 없음)

아래 3가지는 "권장"이 아니라 **반드시 지켜야 하는 규칙**입니다. 코드 리뷰 시 이 기준으로 반려할 것.

1. **하드코딩 금지 — 값은 전부 `tokens.css` 토큰에서만 나온다.**
   색상, 간격, 타이포 크기, radius, 그림자, 아이콘 크기 등 어떤 값도 `Color(0xFF...)`, `16.dp`, `24.sp`처럼 직접 숫자를 박아 넣으면 안 됩니다. 반드시 `tokens.css`를 옮긴 Compose 테마 객체(§3.1의 `Color.kt`/`Type.kt`/`Dimens.kt`)를 통해서만 값을 가져다 씁니다.
   - **필요한 값이 `tokens.css`에 없는 경우**: 임의로 값을 만들어 쓰지 말고, 반드시 먼저 `tokens.css`에 새 토큰을 추가한 뒤(디자이너와 협의 — 기존에도 `ProductInfoScreen.json`의 `newTokensIntroduced`처럼 새 토큰을 추가하며 근거를 남겨온 방식과 동일) 그 토큰을 참조합니다. `tokens.css`에 없는 값이 코드에만 존재하는 상태는 허용하지 않습니다.
2. **컴포넌트는 반드시 재사용 — 화면마다 새로 만들지 않는다.**
   버튼, 카드, 아이콘 버튼 등 `Resources/Components/*.json`에 정의된 컴포넌트가 있다면 화면 Composable 안에서 즉석으로 스타일을 다시 짜지 말고, §3.3에서 정의하는 공용 Composable(`ShoplyButton`, `ProductInformationCard` 등)을 가져다 씁니다. 기존 컴포넌트로 커버되는데 별도 구현을 새로 만드는 것도 금지 — 먼저 기존 컴포넌트에 필요한 variant/state가 있는지부터 확인.
3. **아이콘은 `root/Resources/Icons`(및 `NavBar`/`StatusBar`/`CameraStatus`/`Guideline`/`Brand`) 안에 있는 것만 사용.**
   `androidx.compose.material.icons.extended` 같은 안드로이드 기본 아이콘 라이브러리에서 비슷한 아이콘을 대신 쓰는 것 금지 (지금 `gradle/libs.versions.toml`에 이 라이브러리가 이미 포함되어 있는데, 화면 UI에는 쓰지 않는 것이 원칙 — §3.2 참고). 필요한 아이콘이 `root/Resources/Icons`에 없으면 임의 대체하지 말고 디자이너에게 요청해서 그 폴더에 추가한 뒤 써야 합니다.

---

## 1. 지금 상황

- `root/` 폴더에는 Figma(`Shopping-XR`, fileKey `Oo9W0U9SyNGNZXyD8urNJZ`)에서 나온 토큰·컴포넌트 스펙·인터랙션·아이콘이 매우 꼼꼼하게 문서화되어 있습니다 (`tokens.css`, `Resources/Components/*.json`, `*.css`, `preview/*.html`).
- 하지만 실제 제품(`app/`)은 **완전히 다른 기술 스택**(Android Jetpack Compose 네이티브 앱)으로 개발자가 별도로 만들었고, 지금은 `root/`의 색상·타이포·컴포넌트를 전혀 참조하지 않습니다.
  - `MainActivity.kt:58` — `MaterialTheme { ... }` 를 커스터마이징 없이 그대로 사용 (Android 기본 Material 3 테마).
  - `MainScreen.kt` 등에서 `Color(0xFF151515)`, `Color(0xFF9E9E9E)` 같은 임의 하드코딩 색상 사용 (`tokens.css`의 색상 토큰과 무관).
  - 버튼은 Compose 기본 `Button`/`OutlinedButton`을 그대로 사용 (`Button.json`이 정의한 Primary/Neutral/Subtle × Transparent/Solid × Medium/Small 조합이 반영 안 됨).
  - `root/Resources/Icons`, `NavBar`, `StatusBar` 등 커스텀 SVG 아이콘 세트가 앱에 전혀 들어가 있지 않음 (`res/drawable` 폴더 자체가 없음).
- 즉 "디자인은 다 만들어졌는데 UI에 안 붙어있다"는 게 정확한 진단입니다.

---

## 2. 왜 `root/` 파일을 그대로 가져다 쓸 수 없는가

`root/`는 **웹 기술(HTML/CSS/SVG/JS)로 만든 디자인 명세 + 프로토타입**입니다. 반면 실제 제품은 화면이 두 곳입니다:

| 화면 | 기술 | root/의 대응 자산 |
|---|---|---|
| ① 폰 앱 화면 | Android Jetpack Compose (Kotlin, 네이티브 UI) | `preview/*.html`, `tokens.css`, `Resources/Components/*` |
| ② 글라스 디스플레이 (Meta Ray-Ban Display) | Meta Wearables Display SDK 전용 레이아웃 API — `flexBox` / `text` / `image` / `button` 같은 제한된 자체 컴포넌트만 지원. CSS·SVG·임의 레이아웃 불가 | `CameraStatus.json`(device=Glasses), `Guideline.json`(phone=Glasses) 같이 "Glasses" 변형으로 표시된 부분 |

**핵심: HTML/CSS 코드를 복사-붙여넣기 하는 방식은 불가능합니다.** Compose는 CSS를 읽지 못하고, 글라스 디스플레이는 그보다 훨씬 더 제한적입니다. 따라서 `root/`는 "이 색을 이 값으로, 이 간격을 이 값으로, 이 상태에서는 이렇게" 라는 **명세서**로 쓰이고, 개발자가 그 명세를 Kotlin/Compose 코드로 다시 구현해야 합니다.

좋은 소식은 `root/`가 이미 이 작업을 굉장히 쉽게 만들어 놨다는 점입니다 — 색상/간격/타이포가 전부 변수(토큰)로 정리되어 있고, 각 컴포넌트가 variant×state 조합까지 JSON으로 명시되어 있어서, 개발자가 "감으로 비슷하게" 만들 필요 없이 표를 그대로 옮기면 됩니다.

---

## 3. 자산 매핑 — `root/`의 무엇이 코드의 무엇이 되어야 하는가

상태 표기: ❌ 미착수 · 🔄 부분 구현(디자인과 불일치) · ✅ 완료

### 3.1 토대 (Foundation)

| root/ 자산 | 되어야 할 것 | 코드 위치(예정) | 상태 |
|---|---|---|---|
| `tokens.css` 색상 변수 | Compose `Color` 팔레트 + `ColorScheme`/커스텀 테마 객체 | `ui/theme/Color.kt`, `ui/theme/Theme.kt` (신규) | ❌ |
| `tokens.css` 타이포(`.TextStyle*`) | Compose `Typography`/`TextStyle` 세트 | `ui/theme/Type.kt` (신규) | ❌ |
| `tokens.css` spacing/radius/icon-size 변수 | `object Spacing { ... }` 같은 Kotlin 상수 객체 | `ui/theme/Dimens.kt` (신규) | ❌ |
| Inter 폰트 | `res/font/`에 폰트 파일 추가 후 `FontFamily`로 등록 | `res/font/` (신규) | ❌ |
| `interactions.css` (hover/disabled 규칙) | Compose의 `interactionSource`/상태별 `Modifier` 로직 | 각 컴포넌트 내부 | ❌ |

### 3.2 아이콘 / 이미지 자산

| root/ 자산 | 되어야 할 것 | 상태 |
|---|---|---|
| `Resources/Icons/*.svg` (12개) | Android Vector Drawable로 변환 (`res/drawable/ic_*.xml`) — Android Studio의 *Vector Asset* 임포트로 SVG→XML 변환 가능 | ❌ |
| `Resources/NavBar`, `Resources/StatusBar`, `Resources/CameraStatus`, `Resources/Guideline` SVG | 동일하게 Vector Drawable화 (라이트/다크, 디바이스별 변형 포함) | ❌ |
| `Resources/Brand/Logo.svg` | Vector Drawable | ❌ |
| `Animation/Loading.json` (Lottie) | `lottie-compose` 의존성 추가 후 재생 (`Animation/Loading.gif`는 미리보기용, 실제 구현엔 `.json` 사용 — 설계 스펙 `CameraViewfinder.json` 참고) | ❌ (Lottie 의존성 자체가 아직 없음, `gradle/libs.versions.toml` 확인) |

> §0 규칙 3 참고: `gradle/libs.versions.toml`의 `androidx-material-icons-extended`는 화면 아이콘 용도로 쓰지 않습니다. 화면에 노출되는 모든 아이콘은 위 표의 Vector Drawable(= `root/Resources/Icons` 등에서 변환된 것)만 사용합니다.

### 3.3 컴포넌트

| root/ 컴포넌트 스펙 | 되어야 할 Composable | 현재 앱에서 대체 중인 것 | 상태 |
|---|---|---|---|
| `Button.json` / `Button.css` | `ShoplyButton` (variant/color/size/state 파라미터) | Compose 기본 `Button`/`OutlinedButton` | ❌ |
| `IconButton.json` / `IconButton.css` | `ShoplyIconButton` | 미구현 | ❌ |
| `ProductName.json` / `.css` | `ProductNameCard` | 미구현 | ❌ |
| `ProductInformation.json` / `.css` | `ProductInformationCard` | `ResultSection`(`MainScreen.kt`)가 하드코딩 스타일로 유사 역할 수행 중 | 🔄 |
| `PriceInformation.json` / `.css` | `PriceInformationCard` | `PriceResultRow`(`MainScreen.kt`)가 임시 대체 | 🔄 |
| `SavedLink.json` / `.css` | `SavedLinkRow` | 미구현 | ❌ |
| `CameraStatus.json` | `CameraStatusIndicator` (Phone/Glasses 변형) | 미구현 | ❌ |
| `Guideline.json` | `CameraGuideline` | `CameraGuideOverlay`(`MainScreen.kt`)가 Canvas로 원+십자가를 그리지만 색/두께/크기 등이 토큰 기준이 아님 | 🔄 |
| `AndroidStatusBar.json` / `AndroidNavBar.json` | 상태바/내비게이션바 오버레이 (또는 시스템 UI로 대체 검토) | 미구현 | ❌ |

### 3.4 화면

| root/ 프리뷰 | 되어야 할 화면 | 현재 앱 화면 | 상태 |
|---|---|---|---|
| `preview/onboarding.html` | 온보딩 화면 | 없음 — `ConnectScreen.kt`가 기능적으로 비슷한 역할(글라스 선택)을 하지만 디자인은 무관 | ❌ · **JSON 스펙 자체가 없음 (아래 5번 열린 질문 참고)** |
| `preview/camera.html` (= `CameraViewfinder.json`, 상태: framing/analyzing/result) | 카메라 촬영 화면 | `MainScreen.kt`의 `CameraScreen` — 촬영/재촬영/검색 흐름은 동작하지만 비주얼이 디자인과 다름 (가이드라인 색상, 로딩 애니메이션, 버튼 스타일, "이 제품이 맞나요?" 확인 카드 레이아웃 등) | 🔄 |
| `preview/product-info.html` (= `ProductInfoScreen.json`) | 상품 정보 화면 | 없음 — `MainScreen.kt`의 `ResultScreen`이 전혀 다른 레이아웃으로 유사 정보를 보여줌 | ❌ |
| `preview/price-compare.html` (= `PriceCompareScreen.json`) | 가격 비교 화면 | 없음 | ❌ |
| `preview/saved-link.html` (= `SavedLink.json`) | 저장한 링크 화면 | 없음, 내비게이션 흐름에도 아직 연결 안 됨 | ❌ |
| `preview/components.html` | (화면 아님) 컴포넌트 갤러리 — 모든 variant/state를 한눈에 보는 카탈로그 | — | 개발 중 육안 대조용으로 계속 활용 권장 |

`DisplayTestScreen.kt`/`ConnectScreen.kt`는 `root/`에 대응하는 화면 디자인이 아직 없습니다 (§5 참고).

---

## 4. 추천 진행 순서

한 번에 다 바꾸려 하지 말고, 아래 순서로 "토대 → 부품 → 화면" 순으로 쌓는 걸 추천합니다. 순서를 지켜야 나중 단계가 앞 단계 위에서 재사용됩니다.

1. **Phase 0 — 테마 토대**: `tokens.css`의 색상/타이포/spacing/radius를 Compose 테마 객체로 옮기고, `MainActivity.kt`의 `MaterialTheme { }`를 커스텀 테마로 교체. Inter 폰트 적용.
2. **Phase 1 — 아이콘**: `Resources/Icons` 등 SVG를 Vector Drawable로 일괄 변환.
3. **Phase 2 — 기본 컴포넌트**: `Button`, `IconButton`부터 구현. `components.html`을 열어 놓고 나란히 비교하며 variant×state 전부 맞는지 확인.
4. **Phase 3 — 복합 컴포넌트**: `ProductName`, `ProductInformation`, `PriceInformation`, `SavedLink`, `CameraStatus`, `Guideline`.
5. **Phase 4 — 화면 교체**: `camera.html` 스펙대로 카메라 화면 재구성 → `product-info.html`, `price-compare.html` 화면 신규 추가 및 내비게이션 연결(`MainActivity.kt`의 `NavHost`에 route 추가) → 온보딩/저장링크는 §5 열린 질문 정리 후 진행.
6. **Phase 5 — 글라스 디스플레이**: Meta Display SDK의 `flexBox`/`text`/`image`/`button` 제약 안에서 "Glasses" 변형(`CameraStatus`, `Guideline`)을 별도로 설계·구현. 폰 화면과 동일한 결과물을 기대하지 말 것 — SDK가 지원하는 표현 범위를 먼저 개발자와 함께 확인.
7. **Phase 6 — 전수 비교(QA)**: 완성된 각 Compose 화면을 같은 화면비로 `root/preview/*.html`과 나란히 띄워 색상/간격/문구가 1:1로 맞는지 확인.

---

## 5. 열린 질문 (개발팀·디자이너가 같이 정할 것)

- **온보딩**: `onboarding.html`은 있지만 다른 화면들과 달리 대응하는 `Resources/Components/*.json` 스펙이 없습니다. 아직 미완성 상태인지, 아니면 `camera.html`/`ConnectScreen.kt`와 통합될 예정인지 확정 필요.
- **저장한 링크(SavedLink) 화면**: 다른 화면들의 "notes"에 진입 경로가 언급되지 않습니다 (예: product-info → price-compare는 명시되어 있지만 saved-link로 가는 버튼은 어디에도 없음). 이 화면이 전체 플로우 어디에 들어가는지 정의 필요.
- **글라스 디스플레이 표현 한계**: `Display` SDK가 실제로 그라디언트/그림자/커스텀 폰트 등을 지원하는지 확인되지 않았습니다. `tokens.css`의 브랜드 그라디언트 버튼 같은 디테일은 글라스에서 재현이 아예 불가능할 수 있습니다 — 재현 안 되는 부분은 디자이너가 대체 시안을 준비하는 게 안전합니다.
- **더미 데이터**: `root/`의 모든 화면은 상품명·가격 등이 더미입니다. 실제 데이터(LLM 분석 결과, 쇼핑 API)가 들어갔을 때 텍스트 길이가 달라질 수 있는 영역(상품명 2줄 초과, 후기 리스트 길이 등)의 레이아웃 대응 여부를 디자인에서 한 번 더 점검 필요.

---

## 6. 이 문서 관리 방법

- Figma가 바뀌면 **디자이너가 `root/`를 먼저 갱신**합니다 (`tokens.css`/`*.json`에 이미 이런 변경 이력 메모가 있음, 예: `PriceCompareScreen.json`의 "2026-07-27 Figma 재확인" 노트). `root/`가 항상 최신 기준입니다.
- 개발자가 §3의 표 중 한 줄을 구현하면 상태를 ❌ → 🔄 → ✅로 갱신합니다.
- 새 화면/컴포넌트가 `root/`에 추가되면 §3, §4에 줄을 추가합니다.
