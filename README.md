# 마이 드라이브 자동 (My Drive Auto)

현대·기아·제네시스 Gen5W 헤드유닛에서 **마이 드라이브(커스텀) 모드**의 항목을 주행 상태에 따라 자동으로 바꿉니다.

| 항목 | 바꾸는 단계 | 기준 |
|---|---|---|
| 서스펜션 | 노멀 ↔ 스포츠 | 속도, 코너(횡G) |
| 핸들 | 노멀 ↔ 스포츠 | 속도, 코너(횡G) |
| 엔진 | 에코 ↔ 스포츠 (가솔린 차는 노멀 ↔ 스포츠) | 엑셀 페달 % |
| 앰비언트 라이트 | 스포츠일 때 빨강 ↔ 원래 색 | 스포츠 모드 또는 마이 드라이브 스포츠 항목 |
| 클러스터 테마 | 스포츠일 때 고른 테마(A~D) ↔ 원래 테마 | 스포츠 모드 또는 마이 드라이브 스포츠 항목 |

순정 차량 설정 화면이 쓰는 호출(`setIndividualDriveMode`)만 사용하고, 마이 드라이브 모드에서만 동작합니다.
앰비언트 빨강(`setSMLRGB`)과 클러스터 테마(`setCluster_ThemeStatusNew`)는 기본값 꺼짐이며, 스포츠가 끝나면 바로 원래 색·테마로 돌아갑니다. 클러스터 테마는 순정 클러스터 설정의 '드라이브 모드 연동'을 꺼야 작동합니다.

## 대상

- Gen5W 헤드유닛에 **NEMESIS** 또는 **서개모(HMG-Seogaemo)** 가 설치된 차.
- 실차 확인은 **K8(GL3) 하이브리드**만 했습니다. 다른 차는 차가 돌려주는 값을 처음 바꿀 때 스스로 배우고,
  차에 없는 항목(예: 전자제어 서스펜션)은 응답이 없어 그 항목만 멈춥니다.
- 사용 책임은 운전자에게 있습니다. 설치와 시험은 주차 상태에서 하세요.

## 받기와 설치

[Releases](../../releases)에서 `마이드라이브자동-<버전>.zip`을 받습니다. 안에 세 폴더가 있습니다.

- `1_휴대폰앱` — 블루투스로 상태를 보고 설정을 바꾸는 휴대폰 앱(선택).
- `2_네메시스용` — NEMESIS USB 설치 파일. 헤드유닛 설정 앱이 같이 설치됩니다.
- `3_서개모용` — 서개모 모듈 두 개(엔진, 설정 카드).

자세한 설치·설정: [docs/INSTALL.md](docs/INSTALL.md)

## 내 차 추가하기

다른 차를 쓰신다면 [CONTRIBUTING.md](CONTRIBUTING.md)를 보고 차종 프로필
([`VehicleProfile.java`](core/src/io/github/k8ecs/core/VehicleProfile.java))을 추가하거나,
[새 차 보고](../../issues/new?template=new-car.md) 이슈로 값을 알려 주세요.

| 차종 프로필 | `ro.build.product` | 상태 |
|---|---|---|
| `k8-gl3` | `GL3*` | 기아 K8 하이브리드, 실차 확인 |
| `generic` | 그 외 | K8 번호로 시작, 응답 값은 차에서 배움 |

## 구조

- `core/` 전환 규칙(속도·코너·엑셀), 설정, 로그 해석. PC에서 단위 시험.
- `car/` 차량 모듈(API 19 DEX, `app_process`로 실행). 속도·엑셀 읽기, 횡G 계산, 항목 전환.
- `ui/` 헤드유닛·휴대폰 공용 설정 화면. `hu/` 헤드유닛 앱, `phone/` 휴대폰 앱.
- `nemesis/`, `seogaemo/` 설치 패키지 구성. `tools/` 빌드 스크립트.
- 설계 기록: [docs/DESIGN.md](docs/DESIGN.md), [docs/FEASIBILITY.md](docs/FEASIBILITY.md)

## 빌드

JDK 17과 Android SDK(build-tools 35.0.1, platform android-36), Android 4.4.2 `android.jar`가 필요합니다.

```bash
JAVA_HOME=<JDK17> python tools/check.py                      # 단위 시험
python tools/build_phone.py --sdk <SDK> --jdk <JDK17>          # 휴대폰 앱
python tools/build_phone.py --target hu --sdk <SDK> --jdk <JDK17>
python tools/build_car_dex.py --target car --jdk <JDK17> --android-jar <android-4.4.2.jar>
python tools/build_seogaemo.py && python tools/build_nemesis.py && python tools/build_release.py
```

APK 서명 키와 빌드 결과는 `private/`에 생기며 저장소에 올리지 않습니다.
