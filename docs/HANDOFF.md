# 인수인계 (Codex·Claude 공용)

기준: **2026-11-20**. 저장소 `dlatnqls0815-spec/mydrive-auto`, 로컬 브랜치 `master` → 원격 `main`(강제 Push 금지).
이 문서를 먼저 읽고 `README.md` → `docs/DESIGN.md`(아래쪽이 최신) → `docs/INSTALL.md` 순서로 본다.

## 0.4.10 앰비언트 복귀 실패 재시도 (2026-11-20)

- 최초 복귀와 3초 뒤 재시도가 모두 실패하면, 두 번째 실패를 확인한 시점부터 5초 뒤 순정 상태를 요청한다. 새 응답이 아직 스포츠 빨강일 때 원래 색을 마지막으로 한 번 더 보낸다.
- 사용자가 다른 색을 선택했거나 상태를 확인할 수 없으면 추가 색 명령을 보내지 않는다. 최종 실패 시 원래 색을 보관하며 앱의 도구 → 정지 해제로 다시 시도할 수 있다. 실차 확인은 대기 중이다.

## 0.4.9 늦은 스포츠 표시 응답 복귀 (2026-11-20)

- 순정과 사제 모두 빨강에서 복귀하지 않는 경우가 보고됐다. 차량 ADB 오프라인으로 해당 실차 기록은 미확인이다. 오프라인 검사에서 스포츠가 먼저 끝났을 때 3초를 넘긴 전환 응답이 원래 색을 잃게 하는 경로를 재현했다.
- 원래 색과 테마를 보관해 뒤늦게 스포츠 표시가 확인돼도 설정된 0~5초 복귀 대기와 최소 3초 송신 간격에 맞춰 복귀한다. 첫 전환은 기존대로 최대 2회 송신한다. 응답 대기 중 사용자가 다른 값을 고르면 취소한다.
- 코어 검사 327개 통과. 모듈·서개모 엔진 0.4.9를 빌드했고, 헤드유닛 앱 0.4.9·마이 드라이브 휴대폰 앱 0.5.7과 서개모 카드 0.3.6을 함께 패키징했다. 0.4.9 차량 모듈 설치 및 실차 검증 대기.

## 0.4.8 순정·사제 색 동기화와 복귀 대기 (2026-11-20)

- 07:13 KST에 사용자 정차 확인과 차속 0을 확인한 뒤 차량에 설치했다. 모듈 0.4.8·헤드유닛 0.4.9, DEX 검증, 단일 CarMain 실행 및 순정 앰비언트 상태 수신을 확인했다. 앰비언트 중계 업데이트와 스포츠 전환·복귀 시험은 대기 중이다.
- 사용자가 순정 색은 그대로이고 사제만 빨강으로 바뀐 뒤 복귀하지 않는다고 보고했다. 모듈은 K8 순정 색상 선택과 상태 응답에 기록된 빨강 `00fa00040051`를 시도하고, 색 송신 600ms 뒤 상태를 재요청한다. 실제 순정 점등은 정차 시험 대기 중이다.
- 헤드유닛·휴대폰 설정의 스포츠 표시와 서개모 카드에서 클러스터·앰비언트 공통 복귀 대기를 1초 단위로 0~5초 설정한다. 기본 0초와 최소 송신 간격 3초를 적용한다. 스포츠에 다시 진입하면 대기 시간이 초기화된다.
- 사제 동기화에는 차량 중계 0.4.5와 Galaxy 중계 0.5.4를 사용한다. 이전 앱을 함께 쓸 경우 Galaxy 앱의 '마이 드라이브 스포츠 빨강'을 끈다. 두 중계 새 버전은 마이 드라이브 RGB를 순정 상태 응답에서 받는다.
- 첫 정차 시험에서는 순정 앰비언트의 드라이브 모드 연동을 끄고 순정 색을 확인한 뒤, 0초와 5초를 각각 시험한다. 순정·사제의 시작/복귀와 `ambRgb`, `ambLink`, 모듈의 `no feedback for ambient` 기록을 대조한다.

## 0.4.7 검수와 빌드 (2026-11-20)

- 앰비언트 스포츠 빨강 옵션과 클러스터·앰비언트 즉시 복귀를 검수했다. 마지막 송신과의 최소 간격 3초를 유지한다.
- 두 기능 모두 복귀 명령이 확인되기 전까지 원래 값을 보관한다. 첫 복귀에 응답이 없으면 1회 재시도하고, 두 번째에도 응답이 없으면 해당 기능을 멈춘다. 대기 중 수동 변경은 우선한다.
- 코어 검사 312개 통과. 차량 DEX·서개모 훅·두 APK를 빌드하고, APK 서명 및 배포 ZIP의 중첩 파일 무결성을 검증했다. 배포 버전은 차량 모듈·서개모 엔진 0.4.7, 헤드유닛 0.4.8, 휴대폰 0.5.6, 서개모 카드 0.3.5다.
- 실차 시험과 GitHub 반영은 대기 중이다. 먼저 차량 연결과 정차 상태를 확인하고 사용자 승인 후 설치한다. 정차 시험에서는 마이 드라이브에서 클러스터 연동을 끈 뒤 두 기능을 각각 켜서 스포츠 진입과 복귀를 관찰한다. 앰비언트는 `ambRgb`로 원래 색과 복귀 결과를 확인하고, 순정 스포츠 모드에서도 따로 확인한다.

## 한 줄 요약

K8 GL3 하이브리드(Gen5W, Android 4.4.2) 헤드유닛에서 **마이 드라이브** 항목(서스펜션·핸들·엔진)을 속도·횡G·엑셀에 따라
자동으로 노멀↔스포츠로 바꾸는 NEMESIS 모듈과 설정 앱이다. 순정 설정 화면과 같은 `setIndividualDriveMode`만 쓴다.
0.4.5부터 **클러스터 스포츠 테마**(스포츠 상태일 때 고른 테마 A~D로, 끝나면 원래 테마로) 옵션이 있다. 기본은 꺼짐.

## 이전 차량 설치 상태 (2026-11-20 05:16 설치, 05:20 헤드유닛 재부팅 후 정상)

| 위치 | 버전 | 산출물 |
|---|---|---|
| NEMESIS `k8ecs.damping` 차량 모듈 | **0.4.6** (dex md5 `78561e12…10606`) | `private/nemesis/k8ecs.damping-0.4.6.zip` |
| 헤드유닛 설정 앱 `io.github.k8ecs.hu` | **0.4.7** (code 23, 모듈 zip에 같이 들어 있음) | `private/hu/k8-ecs-hu.apk` |
| 휴대폰 앱 | 0.5.5 빌드만, 미설치 | `private/phone/k8-ecs-phone.apk` |
| 배포 묶음 | 0.4.6 | 작업 공간 `deliverables/마이드라이브자동-0.4.6.zip` |

사용자 설정은 그대로다(자동 전환 켜짐, 서스펜션 60 km/h, 엔진 엑셀 30% 등). **클러스터 옵션은 꺼져 있다.**
GitHub 릴리스는 v0.4.3이 최신이다. 0.4.5/0.4.6 릴리스는 실차 확인 뒤에 만든다(0.4.4는 만들었다가 지웠다).

## 클러스터 스포츠 테마 — 실차에서 알아낸 것 (2026-11-20)

- 호출: `IUSMService`(`com.hkmc.usm.IUSMService`, 등록 이름 **`com.hkmc.micom.usm`**), get `0x22b`(=555), set `0xc4`(=196, 값 1..4), 연동 get `0x1ac`(=428).
  확인용 adb 명령: `service call com.hkmc.micom.usm 555`(현재 테마), `service call com.hkmc.micom.usm 428`(연동).
- 값: **1=A, 2=B, 3=C** 실차 확인(D=4는 추정). 순정 메뉴와 모듈 모두 MICOM `USM_CLU_SET_ETC_C_REQ`(0x1124) `01 <값>`을 보낸다.
- 순정 '드라이브 모드 연동': `1` 꺼짐, `2` 켜짐. 켜면 테마 값이 `15`로 보이고 차가 모드별 화면을 정한다.
  사용자 결정: **연동을 꺼야 이 기능이 작동한다**로 한다(0.4.6에서 연동 켜짐이면 전환하지 않고 상태 `cluster=link_on`, 화면에 안내).
  순정 메뉴에서 테마를 직접 고르면 연동이 꺼진다. 사용자 참고: 순정 스포츠 모드의 테마는 B.
- 시험 결과:
  - 0.4.5, 연동 꺼짐, **순정 스포츠 모드**: 모듈이 C(3)를 두 번 보냈지만 바뀌지 않아 `HALT cluster: no feedback twice`.
  - 같은 명령을 **에코**에서 `service call`로 보냈을 때는 바로 바뀌었다(3 → 다시 1로 되돌림).
  - 따라서 순정 스포츠 모드 중에는 클러스터가 테마 변경을 받지 않는 것으로 보인다. **마이 드라이브(0x06)에서는 아직 시험 전.**

## 남은 일 (우선순위 순)

1. **마이 드라이브에서 클러스터 전환 시험** (정차, 연동 꺼짐, 드라이브 모드 마이 드라이브):
   먼저 `service call com.hkmc.micom.usm 196 i32 2`로 한 번 보내 바뀌는지 보고 원래 값으로 되돌린다. 바뀌면 앱에서 옵션을 켜고
   마이 드라이브 자동이 항목을 스포츠로 올릴 때(엑셀 30% 이상 등) 바뀌었다가 바로 돌아오는지 본다. 주행 중 확인은 사용자에게 맡긴다.
2. **순정 스포츠 모드 처리 결정**: 지금은 스포츠 모드(0x03)도 스포츠 상태로 보아 명령을 보내고, 받지 않으면 두 번 만에 멈춘다(halt는 '정지 해제'나 재시작 전까지 유지).
   1번 결과를 보고 사용자에게 "순정 스포츠 모드는 제외(마이 드라이브만)"로 바꿀지 묻는다. 바꾸면 `CarMain.isSportActiveLocked()`에서 `MODE_SPORT` 줄을 빼고 문서·화면 문구를 고친다.
3. 실차 확인이 끝나면 GitHub 릴리스(`python tools/build_release.py` 결과)를 만든다. 사용자 확인 후.
4. 휴대폰 앱 0.5.5 설치(USB, 사용자 휴대폰).
5. 앰비언트 스포츠 빨강 실차 시험

## 하지 말 것 / 주의

- **`nemesis service stop/start`로 이 모듈을 다시 켜지 않는다.** 모듈은 uid system으로 돌고 NEMESIS 루트는 그 프로세스를 끌 수 없어서(`Operation not permitted`)
  stop이 실제로 끄지 못하고, start할 때마다 모듈이 하나씩 늘어난다(2026-11-20에 3개까지 늘어 헤드유닛 재부팅으로 정리).
  설치는 `nemesis install --update`가 옛 프로세스를 바꿔 준다. 그래도 중복이 보이면(아래 확인 방법) 사용자 승인 후 `adb reboot`.
  중복 확인: `adb shell 'nemesis root -c "ps"' | grep app_process` 후 각 pid의 `/proc/<pid>/cmdline`에서 `io.github.k8ecs.car.CarMain`이 하나인지 본다.
- 차량 명령(Binder 송신)·설치·새 송신 시험은 사용자 승인 후, 먼저 logcat에서 속도 0(`getSpeed_KMHUnit() = 0.0`)을 확인한다.
- `private/`, `captures/`, `.tools/`, APK·서명키·VIN·MAC·IP·휴대폰 serial은 커밋·외부 전송 금지. 이 저장소는 **공개**다.
- 사용자는 쉬운 한국어로 소통하고, 차에 반복해서 가는 것을 부담스러워한다. PC 분석 먼저, 현장 확인은 묶어서.

## 검사·빌드·설치 (Git Bash)

이 PC의 JDK 17·Android SDK·adb 실제 경로는 저장소 밖(작업 공간 `docs/AI_HANDOFF.md` 맨 위)에 적어 둔다. 공개 저장소라 여기엔 쓰지 않는다.
API 19 jar는 `<SDK>/platforms/android-19/android.jar`.

```bash
export JAVA_HOME="<JDK17>"
SDK=<SDK>
python tools/check.py                                   # 0.4.6 기준 core tests passed: 227
python tools/build_phone.py --sdk $SDK --jdk "$JAVA_HOME"
python tools/build_phone.py --target hu --sdk $SDK --jdk "$JAVA_HOME"
python tools/build_car_dex.py --target car  --jdk "$JAVA_HOME" --android-jar $SDK/platforms/android-19/android.jar
python tools/build_car_dex.py --target hook --jdk "$JAVA_HOME" --android-jar $SDK/platforms/android-19/android.jar
python tools/build_seogaemo.py && python tools/build_nemesis.py && python tools/build_release.py
```

버전: 모듈 `car/.../CarMain.java` `VERSION`, `tools/build_nemesis.py` `PACKAGE_VERSION`, `seogaemo/k8-mydrive-engine/module.properties`는 같은 값으로 맞춘다.
헤드유닛 앱 `hu/AndroidManifest.xml`, 휴대폰 앱 `phone/AndroidManifest.xml`은 따로 올린다.

차량 설치(사용자 승인 후, 차량 무선 ADB 주소는 사용자에게 받는다 — 기록 금지):
`MSYS_NO_PATHCONV=1 adb -s <car> push private/nemesis/k8ecs.damping-<ver>.zip /data/local/tmp/` →
`adb -s <car> shell 'nemesis install --update /data/local/tmp/k8ecs.damping-<ver>.zip; rm /data/local/tmp/k8ecs.damping-<ver>.zip'` →
`adb -s <car> shell 'nemesis root -c "md5 /data/nemesis/apps/k8ecs.damping/bin/k8-ecs-car.dex"'`를 빌드한 dex와 비교 →
`adb -s <car> shell 'nemesis root -c "cat /data/nemesis/logs/k8ecs.damping/events.log"'`에서 `started <ver>` 확인.
헤드유닛 셸에는 `tail`·`sed`·`find`·`wc`가 없다. 파일은 PC로 가져와서 본다.
