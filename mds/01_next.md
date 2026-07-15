# 작업 정리 & 다음 단계 (가이드 UI)

Smartglass_shopping / RayBanVision — 지금까지 작업 정리와 다음 채팅에서 만들 **가이드 UI** 준비 문서.

---

## 📦 지금까지 작업 정리

### 1. 환경 셋업
- **Claude Code 플러그인**: `facebook/meta-wearables-dat-android` 마켓플레이스 추가 → `mwdat-android` 플러그인 설치
- **테스트 기기**: Galaxy S23(SM-S911N) 무선 디버깅 (포트가 자주 바뀌어 재연결 필요)

### 2. Mock Device Kit(목 디바이스) 배선 — 물리 글라스 없이 폰으로 테스트
- `app/src/debug/.../mock/MockGlassesInitializer.kt` **(신규)** — MDK 활성화 + 목 글라스 페어링 + 전원/착용 + 폰 **후면 카메라**를 라이브 피드로 연결
- `app/src/release/.../mock/MockGlassesInitializer.kt` **(신규)** — release용 no-op 스텁
- `MainActivity.kt` — 권한 허용 후 `enable()` → `Wearables.initialize()` → `pairMockGlasses()`
- `ConnectScreen.kt` — debug에서 목 기기가 목록에 뜨고 선택되도록 필터/버튼 완화
- `build.gradle.kts` — `buildConfig = true`
- **기존 컴파일 버그 수정**: 존재하지 않던 `isDisplayCapable` 심볼 → `device.deviceType.isDisplayCapable`로 정정 (원래 프로젝트가 빌드 자체가 안 됐음)

### 3. 실시간 카메라 프리뷰 (임시, 목 테스트용)
- `SessionViewModel` — `compressVideo = false`로 raw 프레임 수신, `stream.videoStream` collect → Bitmap 변환 → `previewFrame: StateFlow<Bitmap?>`
- **프레임 포맷**: YUV420 planar(**I420**, 480x640). Android `YuvImage`는 NV21만 지원 → I420→NV21 재구성 후 색 정상화
- `MainScreen.CameraArea` — 촬영본 있으면 사진, 없으면 "LIVE" 프리뷰 표시 (3:4 박스)

### 4. 촬영 후 흐름: 재촬영 / 검색
- **촬영** = 미리보기만 (LLM 전송 안 함)
- **재촬영** (`retakePhoto`) → 캐시 파일 삭제 + 라이브 복귀
- **검색** (`submitForSearch`) → `capturedPhotoFile` StateFlow 방출(LLM 전송) + 글라스 로딩 표시
- LLM 인터페이스 `capturedPhotoFile`는 **[검색] 시에만** 방출 (기존엔 촬영 즉시 방출)

### 5. 프레임 찢김 버그 수정
- **원인**: 목에서 `videoStream` 수집과 `capturePhoto()`가 버퍼 경합 → 찢김/부분 프레임
- **해결**: debug(목)에서는 `capturePhoto()` 대신 **현재 프리뷰 프레임을 저장** (`BuildConfig.DEBUG` 분기). 실제 글라스는 기존 `capturePhoto()` 유지. 촬영 시 `stopPreview()`, 재촬영 시 `startPreview()`

---

## ⚠️ 다음 작업(가이드 UI) 전에 알아둘 것

1. **목 디바이스는 디스플레이 미지원** — 글라스 화면(반투명 가이드라인/결과 출력)은 목으로 테스트 불가, **실제 글라스에서만** 확인 가능. 목에선 촬영 파이프라인까지만.
2. 가이드라인은 원래 **글라스 디스플레이**에 반투명하게 띄우는 게 목표 — `SessionViewModel.displayResult()`처럼 `Display.sendContent { flexBox {...} }` API를 쓰는 영역. 다만 목에선 안 보이니, 폰 프리뷰 위에 오버레이로 먼저 프로토타이핑할지 정해야 함.
3. 현재 `Display` 뷰 API로 쓸 수 있는 것들: `flexBox`, `text`, `image`, `button` 등 (`DisplayTestScreen.kt`/`displayResult()` 참고)

---

## 🖥 현재 상태
- 폰에 최신 debug 빌드 설치됨, 목 디바이스로 **프리뷰 + 촬영 + 재촬영/검색** 전부 동작 확인
- 관련 기술 제약은 프로젝트 메모리(`mdk-mock-device-constraints`)에 저장됨

---

## ▶️ 다음 채팅에서 결정할 것 (가이드 UI)
- **위치**: 가이드라인을 폰 프리뷰 위 오버레이로 띄울것.
  - 원모양 안에 물건이 들어갈 수 있게 원모양 선이 가이드로 그리기.
  - 가운데는 + 십자가 모양을 넣어 중심이 어디인지 표시한다.
- **텍스트** : 촬영 유도 안내 문구를 추가한다
  - 촬영 전 : 물건을 가운데에 두고 촬영버튼을 눌러주세요
  - 촬영 후 이미지가 떴을 때 : 물건 정보가 궁금하면 '검색'을 누르고, 다시 촬영을 해야하면 '재촬영'을 눌러주세요
  - 검색을 누른 뒤 : (촬영한 이미지 위에) AI 검색 중...
