# 현장 확인 1회차 — 서스펜션 설정 값 읽기

목적: 순정 화면에서 서스펜션 단계를 바꿀 때 헤드유닛이 보내는 `device`/`mode` 번호와 응답을 로그로 얻는다.
**차량에 보내는 명령은 없다.** 로그를 받기만 하고, 조작은 사용자가 순정 화면에서 한다. 소요 5~10분.

## 준비

- 정차, P단, 주차 브레이크, 시동 ON(READY).
- 노트북과 헤드유닛이 같은 네트워크(무선 ADB 5555, 주소는 사용자가 알려 준다 — 기록·커밋 금지).
- `persist.service.adb.tcp.enable`은 건드리지 않는다.

## 로그 받기 (읽기 전용)

```bash
ADB=<Android SDK>/platform-tools/adb.exe
"$ADB" connect <주소>:5555
"$ADB" -s <주소>:5555 logcat -v time > private/captures/ecs-capture.txt
```

필터 없이 전부 받는다(어느 태그에 찍힐지 아직 모른다). 끝나면 Ctrl+C.

## 사용자가 할 조작 (각 단계 사이 5초 쉬기, 시각을 대략 메모)

1. 드라이브 모드 버튼: 컴포트 → 에코 → 스포츠 → 스마트 → **커스텀** 순으로 한 바퀴.
2. 커스텀 설정 화면 열기(사진 한 장).
3. **서스펜션** 항목만 단계별로 바꾸기: 첫 번째 → 두 번째 → (있으면 세 번째) → 다시 첫 번째.
4. 비교용으로 스티어링 항목을 한 번 바꿨다가 되돌리기(서스펜션 번호와 구분하기 위함).
5. 커스텀 모드 상태에서 설정 화면을 닫고 10초 대기 → 컴포트로 복귀.

## 받은 뒤 PC에서 볼 것

- `setIndividualDriveMode] device: X, mode: Y` 줄 → 서스펜션의 X와 단계별 Y.
- 직후의 `SYS_DRIVE_MODE_DATA_M_RESP` 바이트 변화 → 상태 확인에 쓸 위치.
- 커스텀 모드의 byte 2 값(에코 0x02, 스포츠 0x03, 스마트 0x06은 이미 앎).
- ECS·damping 관련 줄이 있는지(`EcsStatus`, `dampingMode`).
- 요청에서 응답까지 걸린 시간.

`private/`는 커밋하지 않는다(`.gitignore`).

## 그다음 (별도 승인)

값이 확인되면 같은 값으로 `setIndividualDriveMode`를 **한 번** 호출하는 송신 시험을 제안한다.
가능하면 같은 방문에서 이어서 하도록, 로그를 받은 자리에서 바로 분석한다.

### N 모드 데이터 요청 시험 (사용자가 2026-11-15 포함하기로 함, 실행 직전 다시 확인)

1. 주차 브레이크·속도 0을 로그로 확인.
2. `requestNModeData(1)` 한 번 → 10초간 `SYS_G_FORCE_DATA_M_REQ` / `SYS_POWER_TORQUE_TURBOBOOST_M_REQ` 줄이 오는지 본다.
3. `requestNModeData(0)` → 멈추는지 본다. 안 멈추면 그대로 두고 시동 OFF 후 사라지는지 확인한다.
4. 안 오면 거기서 끝(다른 값은 시도하지 않는다).

시작/중지 값(1/0)은 추정이다. 근거와 한계는 [DESIGN.md](DESIGN.md)의 "추가 분석".

### 로그에서 볼 줄 (갱신)

서스펜션 조작 때는 아래 둘 중 하나가 찍힌다. 항목 번호 후보는 1(서스펜션), 2(스티어링).
- `[SystemService.setIndividualDriveMode] device: X, mode: Y`
- `[SystemService.requestDriveCustomMode2] data: XX YY`
