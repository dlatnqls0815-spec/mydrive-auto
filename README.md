# K8 ECS 감쇠력 조절

2021 K8 GL3 HEV의 전자제어 서스펜션 감쇠력을 휴대폰으로 조절하는 방법을 찾는 프로젝트.

현재 단계: **0.2.3 차량 설치됨(2026-11-16): 서스펜션·핸들·엔진(엑셀) 3탭 + 연동 옵션, 마이 드라이브 전용, 서개모 카드 1개(k8-mydrive 0.2.2, 탭 선택).** 설치·사용: [docs/INSTALL.md](docs/INSTALL.md).

- 조사 결과와 경로 비교: [docs/FEASIBILITY.md](docs/FEASIBILITY.md)
- A안 설계와 현장 확인 계획: [docs/DESIGN.md](docs/DESIGN.md)
- 현장 1회차 로그 수집 절차: [docs/FIELD_CAPTURE.md](docs/FIELD_CAPTURE.md)
- 전환 규칙 엔진: `core/src/io/github/k8ecs/core/` (차량 송신 코드는 아직 없음)

- 휴대폰 앱 `phone/` 0.1.0: 규칙 편집 + **미리보기**(휴대폰 GPS 속도와 자이로로 규칙이 고를 단계를 화면에 표시).
  차량 연결 기능이 없고 아무것도 보내지 않는다. 산출물 `../../deliverables/k8-ecs-phone-0.1.0.apk`. 실기기 실행은 아직 미확인.
  빌드: `python tools/build_phone.py --sdk C:/Users/dlatn/.local/share/android-sdk --jdk <JDK17>` (람다 금지 — android.jar만으로는 링크 실패).
- 코너 감지는 가속도계가 아니라 **속도 × 요레이트**(자이로를 중력 방향에 투영)로 한다. 거치 방향 보정이 필요 없고 제동·가속이 섞이지 않는다.

검사:

```bash
JAVA_HOME="/c/Users/dlatn/OneDrive/문서/ChatGPT/kia 자동잠금/analysis/tools/.toolchain/jdk17" python tools/check.py
```

지킬 것: 차량 명령·CAN/Binder 송신·모듈 설치는 사용자 승인 전 금지. VIN·MAC·IP·원본 로그는 커밋하지 않는다.

## 구성 (0.2.3)

- `car/` 차량 모듈(API 19 DEX, NEMESIS 서비스 `uid=system`): 속도(BCM getter), GPS 모듈 자이로(`/dev/ttyGPS2` 읽기 전용),
  드라이브 모드·서스펜션 응답(logcat) → `DampingController` → `setIndividualDriveMode(1, 0/1)`. 설정 포트 127.0.0.1:47231, RFCOMM.
- `hu/` 헤드유닛 설정 앱(API 19, NEMESIS `kind=apk`), `phone/` 휴대폰 설정 앱 0.2.1(블루투스), 공용 화면 `ui/`.
- `probe/` 읽기 전용 시험 도구(GPS 스트림 덤프, 주행 기록기).
- 빌드: `tools/build_car_dex.py --target car` → `tools/build_phone.py --target hu` → `tools/build_nemesis.py`.
  휴대폰: `tools/build_phone.py --target phone`. 산출물은 `private/`, 배포본은 작업 공간 `deliverables/`.
- USB 묶음: `tools/build_usb.py --out ../../deliverables` → `k8ecs-USB설치-<ver>.zip`(type=install). 로그는 앱의 "USB로 로그 저장" 버튼(EXPORTUSB → `nemesis/bin/export_usb.sh`를 NEMESIS root로 실행).
- `seogaemo/k8-suspension/` HMG-Seogaemo 외부 모듈(설정·상태·로그 버튼). 서개모 외부 모듈은 셸·데몬을 못 쓰므로
  `persist.sys.k8ecs.*`(설정, set_prop)와 바인더 서비스 `k8ecs`(code 1 = USB 로그 저장, call_service)로만 엔진과 연결한다.
  엔진은 `sys.k8ecs.*`로 상태를 게시한다(`car/.../SeogaemoBridge.java`). 빌드: `tools/build_seogaemo.py`(설치기 제한 검사 포함)
  → `tools/build_usb.py`가 USB 묶음의 `HSM/`에 넣는다.
