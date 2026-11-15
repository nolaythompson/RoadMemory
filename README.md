# 로드메모리 (RoadMemory) — 길 위의 가족 일기

> 매일 다니는 길에서 가족이 나눈 한마디를 그 장소에 남겨 두었다가, 같은 길을 다시 지날 때 차가 먼저 꺼내 주는 Pleos Connect 차량용 앱입니다.

- 팀: 로드메이트
- 대상 플랫폼: Pleos Connect (Android Automotive 기반)
- APK: [`apk/로드메이트_RoadMemory_release.apk`](apk/)

---

## 1. 앱 소개

| 기능 | 설명 | 사용 SDK |
|---|---|---|
| 오늘의 한 질문 | 운전 부담이 낮은 순간(정차, P단, 한적한 직진)에 짧은 질문을 한 번 건네고, 답을 듣고 그 장소에 기록합니다. | TextToSpeech, SpeechToText, Vehicle |
| 운전 부담 점수 엔진 | 방향지시등, 안내지점 거리, 급가감속, 와이퍼, 정체, 야간을 합산해 0~100점을 계산합니다. 부담이 높으면 앱은 말하지 않습니다. | Vehicle, NaviHelper, FusedLocation |
| 장소 학습 / 마일스톤 | 도착 지점을 150m 단위로 묶어 가족의 장소를 학습하고, "처음 온 날"이나 "10번째 방문"을 기록합니다. | NaviHelper, FusedLocation, Vehicle(기어) |
| 같은 길 회상 | 7일 넘게 지난 기억의 반경 300m를 지나면, 한 번만 "작년 이맘때 여기서…"라고 들려줍니다. | FusedLocation, TextToSpeech |
| 여행 모드 | 질문 예산이 1회에서 6회로 늘어나고, 순간들이 챕터로 묶입니다. 도착하면 "오늘의 이야기"를 요약합니다. | 전체 |
| 반려견과 함께한 길 | "반려견 동승"을 켜면 반려견 질문이 우선 선택되고, 기억에 `#반려견` 태그가 붙습니다. 동물병원, 애견카페, 산책길이 장소와 마일스톤으로 쌓입니다. | TextToSpeech, SpeechToText, FusedLocation |
| 탑승자 인식 | 좌석 점유 정보로 뒷좌석 탑승을 감지해 아이용 질문을 고릅니다. | Vehicle(Seat) |
| 사생활 원칙 | 동의 전에는 아무것도 저장하지 않습니다. 마이크는 질문 직후에만 열리고, 음성 명령으로 기록을 멈추거나 지울 수 있습니다("기록하지 마", "방금 거 지워 줘"). 전화번호, 카드번호, 이메일은 마스킹되며, 원문은 7일 뒤 자동 삭제됩니다. | — |

### Pleos SDK 사용 내역

| SDK | 버전 | 사용 API |
|---|---|---|
| Vehicle | 2.3.2 | `Gear.registerGearSelection`, `TurnSignal.registerTurnSignalState`, `Wiper.registerWiperState`, `Brake.registerBrakePedalStatus`, `Seat.registerSeatOccupancy`, `Odometer.registerTripCurrentDrivingDistance` (읽기 전용, 차량 제어 없음) |
| NaviHelper | 3.0.5 | `onRouteStarted`, `onDestinationArrived`, `onTBTInfo`, `onNavigationStatus`, `getDestinationInfo`, `getTBTInfo` |
| TextToSpeech | 3.3.1 | `Mode.ON_DEVICE`, `initialize`, `speak`, `EventListener.onDone` |
| SpeechToText | 3.3.1 | `Mode.ON_DEVICE`, `initialize`, `request`, `ResultListener.onUpdated` |
| FusedLocation | 1.1.3 | `registerFusedLocationCallback`, `getLastKnownLocation` (Vehicle SDK에 차속 신호가 없어 연속 위치로 속도와 가감속을 계산) |

TTS와 STT는 ON_DEVICE 모드라서 별도의 Client ID나 Secret이 필요 없고, 음성이 차 밖으로 나가지 않습니다.

## 2. 실행 방법

### APK 바로 설치

```bash
adb install -r "apk/로드메이트_RoadMemory_release.apk"
```

에뮬레이터 런처에서 **로드메모리**를 실행한 뒤 권한 요청을 모두 허용합니다.

### 소스 빌드

```bash
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
# 결과물: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # 엔진 단위 테스트
```

- 필요 환경: JDK 17, Android SDK Platform 35, Build-Tools 35.0.0
- Pleos SDK는 `settings.gradle.kts`에 등록된 Pleos Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 내려받습니다.
- release 빌드는 에뮬레이터에 바로 설치할 수 있도록 로컬 debug 키로 서명됩니다. 실제 배포에서는 별도 keystore로 교체합니다.

### 데모 주행 (에뮬레이터용)

에뮬레이터에서는 차량 신호가 거의 변하지 않으므로, 하단의 **데모 주행 시작** 버튼으로 약 40초짜리 등원길 시나리오를 재생할 수 있습니다. 데모는 실제 SDK 콜백이 들어가는 어댑터에 값을 넣는 방식이라, 모든 엔진이 실제와 같은 코드 경로로 동작합니다.

1. 0초: D단 출발, 뒷좌석 탑승. 작년의 기억 하나를 경로 위에 심어 둡니다.
2. 2초: 좌회전 방향지시등과 안내지점 250m로 부담 점수가 85점이 되고, 앱은 말하지 않습니다.
3. 8초: 신호 대기로 급감속(보통 단계)을 거쳐 여유 단계가 되면 **오늘의 한 질문**을 건넵니다.
4. 음성 인식을 쓸 수 없는 환경이면 4초 뒤 예시 답변이 자동으로 입력되고, 그 장소에 저장됩니다.
5. 약 30초: 작년 기억이 있는 지점을 지나면 **같은 길 회상**을 들려줍니다.
6. 34초: P단으로 도착하면 장소를 학습하고 "초등학교에 처음 온 날" 마일스톤을 남깁니다.

## 3. 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 |
| AVD | Android Automotive, API 34, RAM 4GB, Cold boot |
| 화면 규격 | 가로형 차량 디스플레이 (landscape 고정, 3단 레이아웃) |
| minSdk / targetSdk | 28 / 35 |
| 빌드 도구 | Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, JDK 17 |
| 필요 권한 | `RECORD_AUDIO`, `pleos.car.permission.FUSED_LOCATION`, `TTS_SERVICE`, `STT_SERVICE`, `NAVI_ROUTE`, `CAR_DRIVE`, `CAR_INFO`, `CAR_LIGHTS`, `CAR_WINDOWS`, `CAR_SEATS`, `CAR_SAFETY` |
| 외부 키 | 없음 (TTS·STT ON_DEVICE) |

## 4. 프로젝트 구조

```
app/src/main/java/com/roadmemory/app/
├── sdk/                 Pleos SDK 어댑터
│   ├── VehicleSignals.kt    기어·방향지시등·와이퍼·브레이크·좌석·주행거리
│   ├── NaviTracker.kt       경로 시작/도착·TBT 안내 거리
│   ├── LocationTracker.kt   위치 → 속도·가감속 계산
│   └── VoiceIO.kt           TTS/STT (ON_DEVICE)
├── core/                도메인 엔진 (SDK 비의존, 단위 테스트 대상)
│   ├── DrivingLoadEngine.kt 운전 부담 점수 0~100
│   ├── TimingEngine.kt      말해도 되는 순간 판단 (안정 시간·쿨다운·질문 예산)
│   ├── PromptEngine.kt      상황별 오늘의 한 질문
│   ├── PlaceLearner.kt      장소 학습·마일스톤
│   ├── RecallEngine.kt      같은 길 회상
│   ├── PrivacyGuard.kt      음성 명령·마스킹·요약
│   └── TripSession.kt       전체 흐름 오케스트레이션
├── data/                모델과 기기 내 저장소 (원문 7일 보관)
└── ui/                  차량 화면, 데모 시나리오
```

## 5. APK 위치

- 저장소 내 경로: [`apk/로드메이트_RoadMemory_release.apk`](apk/)
- 파일이 커서 저장소에 올라가지 않으면 GitHub Releases에 같은 이름으로 첨부합니다.

## 6. 로드맵

- 서버 연동: LLM 요약, 주간 가족 피드, 모바일 웹 열람 및 공유
- 연말 드라이브 랩드, 가족 지도, 성장 앨범
- 차량 감각 기록(날씨, 창문, 시간대)을 기억 태그로 확장
