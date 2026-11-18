# 내 차 추가하기 (기여 안내)

실차 확인은 **기아 K8(GL3) 하이브리드**만 했습니다. 다른 Gen5W 차를 쓰신다면 아래 순서로 값을 확인해 차종 프로필을 추가해 주세요.
이슈로 값만 알려 주셔도 됩니다([새 차 보고](../../issues/new?template=new-car.md)).

## 1. 무엇이 차마다 다른가

엔진 모듈은 순정 차량 설정 앱과 같은 호출로 마이 드라이브 항목을 바꿉니다.

```
setIndividualDriveMode(항목 번호, 값)          → 차가 바뀜
mIndividualModeFeedback: [0] a [1] b [2] c     ← logcat에 찍히는 현재 상태
```

| 항목 | K8 번호 | K8 보내는 값 | K8 응답 값 |
|---|---|---|---|
| 파워트레인(엔진) | 0 | 에코 2 / 노멀 0 / 스포츠 1 | 에코 4 / 노멀 2(추정) / 스포츠 5 |
| 서스펜션 | 1 | 노멀 0 / 스포츠 1 | 2 / 5 |
| 스티어링(핸들) | 2 | 노멀 0 / 스포츠 1 | 2 / 5 |

- **응답 값**은 모듈이 처음 바꿀 때 스스로 배웁니다(`learned ... = feedback N` 로그). 그래서 프로필은 **항목 번호와 보내는 값**이 맞는 게 중요합니다.
- 차에 없는 항목(예: 전자제어 서스펜션이 없는 차)은 프로필에서 `Item.none(...)`으로 두면 아예 보내지 않습니다.
- 차종은 헤드유닛의 `ro.build.product`로 고릅니다(서개모 `cars=`와 같은 값). 맞는 프로필이 없으면 `generic`(K8 번호)을 씁니다.

## 2. 내 차 값 확인하기

**방법 A — 그냥 써 보기(쉬움)**

1. 최신 릴리스를 설치하고, 앱에서 자동 전환을 켭니다. 처음에는 핸들 하나만 켜 두는 걸 권합니다.
2. 마이 드라이브 모드로 잠깐 달려 항목이 바뀌게 합니다.
3. 앱의 도구 > **USB로 로그 저장**. `events.log`에서 아래 줄을 찾습니다.
   - `vehicle profile generic (ro.build.product=…)`
   - `learned steering sport = feedback 5` 같은 줄
   - `HALT …`, `no feedback for …` (그 항목이 차에 없거나 번호가 다름)

**방법 B — 순정 화면으로 확인(정확함, 주차 상태에서)**

```bash
adb shell getprop ro.build.product
adb logcat -v time -s SystemService:V | grep -E "setIndividualDriveMode|mIndividualModeFeedback"
```

순정 차량 설정 > 드라이브 모드 > 마이 드라이브(커스텀)에서 항목을 하나씩 바꾸면 `setIndividualDriveMode(항목, 값)`과 응답이 찍힙니다.

> 로그를 올릴 때 **차대번호(VIN), 블루투스 MAC, IP 주소, 전화번호**는 지워 주세요.

## 3. 프로필 추가하기

[`core/src/io/github/k8ecs/core/VehicleProfile.java`](core/src/io/github/k8ecs/core/VehicleProfile.java)에 한 개를 만들고 `ALL`에서 `GENERIC` **앞**에 넣습니다.

```java
/** Hyundai Grandeur GN7 hybrid. Confirmed by @you, 2026-12-01. */
public static final VehicleProfile GN7 = new VehicleProfile("gn7", "GN7*",
        "Hyundai Grandeur GN7",
        Item.none(CarLog.SUSPENSION_ITEM),                                       // 전자제어 서스펜션 없음
        new Item(true, CarLog.STEERING_ITEM, 0, 2, "normal", 1, 5, "sport"),
        new Item(true, CarLog.POWERTRAIN_ITEM, 2, 4, "eco", 1, 5, "sport"),
        new Item(true, CarLog.POWERTRAIN_ITEM, 0, 2, "normal", 1, 5, "sport"));

public static final VehicleProfile[] ALL = {K8_GL3, GN7, GENERIC};
```

(위 GN7 값은 **예시**입니다. 실제 값은 확인한 대로 넣어 주세요.)

`Item(지원 여부, 항목 번호, 낮은 단계 보낼 값, 낮은 단계 응답, 이름, 스포츠 보낼 값, 스포츠 응답, 이름)` 순서입니다.

## 4. 검사와 PR

```bash
JAVA_HOME=<JDK17> python tools/check.py
```

모든 프로필에 대해 "낮은 단계와 스포츠의 값이 다르다", "generic이 마지막이다"를 자동으로 검사합니다.
PR에는 차종·연식·파워트레인(가솔린/하이브리드)·헤드유닛(NEMESIS/서개모)과 확인한 로그 줄을 적어 주세요.

## 지켜 주세요

- 시험은 **주차 상태**에서. 주행 중 시험은 하지 마세요.
- 순정 설정 화면이 쓰는 호출 외에 CAN 직접 송신 같은 다른 경로는 받지 않습니다.
- 사용 책임은 운전자에게 있습니다.
