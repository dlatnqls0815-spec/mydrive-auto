#!/usr/bin/env python3
"""Assemble the release: phone app, NEMESIS USB install, HMG-Seogaemo USB modules, each with a Korean guide.

Inputs are the outputs of build_phone.py (phone), build_nemesis.py and build_seogaemo.py. Output is a folder
K8-마이드라이브-<ver>/ with three zips and a guide, plus the same folder as one zip. Offline only.
Every file going out is checked for addresses and identifiers that must not ship (IP, MAC, VIN).
"""
import argparse
import io
from pathlib import Path
import re
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_nemesis  # noqa: E402

PHONE_APK = ROOT / "private" / "phone" / "k8-ecs-phone.apk"
PHONE_MANIFEST = ROOT / "phone" / "AndroidManifest.xml"
HSM_DIR = ROOT / "private" / "seogaemo"
DEFAULT_OUT = ROOT.parents[1] / "deliverables"

FORBIDDEN = [
    re.compile(rb"\b10\.66\.\d{1,3}\.\d{1,3}\b"),
    re.compile(rb"\b192\.168\.\d{1,3}\.\d{1,3}\b"),
    re.compile(rb"\b(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\b", re.I),
    re.compile(rb"\bKN[A-HJ-NPR-Z0-9]{15}\b"),  # Kia VIN
]
# Device serials and other private strings, one per line, kept out of the repository.
_EXTRA = ROOT / "private" / "release-forbidden.txt"
if _EXTRA.is_file():
    FORBIDDEN += [re.compile(re.escape(l.strip().encode())) for l in _EXTRA.read_text(encoding="utf-8").splitlines() if l.strip()]

GUIDE_TOP = """K8 마이 드라이브 자동 전환 {ver}

마이 드라이브(커스텀) 모드로 달릴 때 서스펜션·핸들·엔진을 속도, 코너(횡G), 엑셀에 따라
에코와 스포츠 사이에서 자동으로 바꿉니다. 순정 설정 화면이 쓰는 호출만 사용합니다.

설치 방법은 두 가지 중 하나만 고릅니다. 둘 다 설치하면 서개모 쪽 엔진은 쉬고 NEMESIS 쪽이 동작합니다.

  1. 네메시스용   NEMESIS가 설치된 차.            → 2_네메시스용 폴더
  2. 서개모용     HMG-Seogaemo(서개모) 앱을 쓰는 차. → 3_서개모용 폴더

휴대폰 앱(선택): 1_휴대폰앱 폴더. 차와 블루투스로 연결해 상태를 보고 설정을 바꿉니다.
                두 방식 모두에서 쓸 수 있습니다.

전제: ECS(전자제어 서스펜션)가 달린 K8(GL3), Gen5W 헤드유닛.
주의: 차가 명령을 따르지 않으면 그 항목은 스스로 멈춥니다. 사용 책임은 운전자에게 있습니다.
     설치와 헤드유닛 재시작은 반드시 주차 상태에서 하세요.

버전: 엔진 {ver} / 서개모 설정 카드 {card} / 휴대폰 앱 {phone} / 헤드유닛 앱 포함(NEMESIS)
"""

GUIDE_PHONE = """K8 마이 드라이브 휴대폰 앱 {phone}

설치
1. {apk} 파일을 휴대폰으로 옮겨 엽니다. "출처를 알 수 없는 앱" 설치를 허용해야 할 수 있습니다.
2. 처음 열면 블루투스 권한을 허용합니다.
3. 휴대폰과 차(헤드유닛)가 블루투스로 페어링되어 있어야 합니다. 목록에서 차를 고릅니다.

쓰는 법
- 위쪽에서 속도, 드라이브 모드, 횡G, 엑셀과 세 항목(서스펜션·핸들·엔진)의 현재 상태를 봅니다.
- 아래 탭에서 값을 바꾸고 "변경 내용 저장"을 누르면 "저장되었습니다"가 뜹니다.
  - / + 버튼을 누르고 있으면 값이 빠르게 바뀝니다.
- 서개모로 설치한 경우 바꾼 값은 서개모 카드 화면에도 반영됩니다.
- 차를 바꾸려면 오른쪽 위 "차량 바꾸기".

이전 버전이 설치돼 있으면 그대로 덮어 설치됩니다(같은 서명).
"""

GUIDE_NEMESIS = """K8 마이 드라이브 {ver} - NEMESIS용 USB 설치

처음 설치
1. {zip} 압축을 풀고, 나온 NEMESIS.data 파일과 packages 폴더를 USB 최상위에 그대로 넣습니다.
   (USB에 원래 있던 NEMESIS.data는 덮어쓰게 되니 필요하면 먼저 따로 보관하세요.)
2. 주차한 뒤 시동을 켜고, 내비 화면이 뜨면 USB를 꽂습니다.
3. 설치 확인 창에서 "예"를 누릅니다. 끝나면 USB를 빼도 됩니다.
4. 헤드유닛 "K8 마이 드라이브" 앱(NEMESIS Manager > 내 패키지)에서 "자동 전환"을 켜고 저장합니다.

업데이트(이미 설치된 차)
- USB의 NEMESIS.data 파일을 메모장으로 열어 type=install 을 type=update 로 바꾼 뒤 같은 방법으로 꽂습니다.

로그 받기
- USB를 꽂은 채 앱의 도구 > "USB로 로그 저장"을 누르면 USB에 K8ECS-LOG-날짜-시각 폴더가 생깁니다.

모든 전환은 마이 드라이브 모드에서만 동작합니다. 드라이브 모드 버튼으로 마이 드라이브를 고르세요.
"""

GUIDE_SEOGAEMO = """K8 마이 드라이브 {ver} - 서개모(HMG-Seogaemo)용

NEMESIS 패키지는 필요 없습니다. 모듈 두 개를 설치하고 둘 다 켭니다.
  - K8 마이 드라이브 엔진 (k8-mydrive-engine-{ver}.hsm) : 실제로 전환하는 엔진
  - K8 마이 드라이브      (k8-mydrive-{card}.hsm)       : 설정 카드

설치
1. {zip} 압축을 풀고, 나온 HSM 폴더를 USB 최상위에 그대로 넣습니다.
2. 주차한 뒤 USB를 꽂고, 서개모 앱 > 모듈 추가 > USB에서 가져오기로 두 모듈을 설치합니다.
3. 두 모듈을 모두 켭니다.
4. 엔진 모듈을 처음 켰을 때만, 주차 상태에서 헤드유닛을 한 번 재시작합니다.
   서개모 화면에 "재시작 필요"가 사라지고 두 모듈이 "Active"가 되면 끝입니다.
5. 설정 카드에서 값을 바꾸면 바로 적용됩니다(재시작 필요 없음).

업데이트
- 같은 방법으로 새 hsm을 가져와 설치합니다. 엔진 모듈을 바꾼 경우 헤드유닛을 한 번 재시작합니다.

로그 받기
- USB를 꽂은 채 설정 카드의 "USB로 로그 저장"을 누릅니다(카드가 켜져 있을 때).

모든 전환은 마이 드라이브 모드에서만 동작합니다. 헤드유닛을 재시작한 뒤에는 드라이브 모드 버튼으로
마이 드라이브를 다시 골라 주세요.
"""


def text(s):
    return s.replace("\n", "\r\n").encode("utf-8-sig")  # Windows Notepad friendly


def entry(name):
    info = zipfile.ZipInfo(name, build_nemesis.ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    return info


def make_zip(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        for name, data in files:
            z.writestr(entry(name), data)
    return buf.getvalue()


def scan(name, data):
    blobs = [data]
    if data[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for n in z.namelist():
                inner = z.read(n)
                blobs.append(inner)
                if inner[:2] == b"PK":
                    with zipfile.ZipFile(io.BytesIO(inner)) as z2:
                        blobs.extend(z2.read(m) for m in z2.namelist())
    for b in blobs:
        for pat in FORBIDDEN:
            m = pat.search(b)
            if m:
                raise SystemExit("%s: contains %r, not shipping" % (name, m.group(0)[:20]))


def version_of(pattern):
    found = sorted(HSM_DIR.glob(pattern))
    if len(found) != 1:
        raise SystemExit("expected one %s in %s, found %d" % (pattern, HSM_DIR, len(found)))
    return found[0], found[0].stem.rsplit("-", 1)[1]


def build(out_dir, engine_from=None):
    """engine_from: a NEMESIS package zip and the matching engine .hsm of an earlier, car-tested engine.
    Its car module DEX is repackaged with the current head unit app; otherwise the current build is used."""
    phone = re.search(r'android:versionName="([^"]+)"', PHONE_MANIFEST.read_text(encoding="utf-8")).group(1)
    card_hsm, card_ver = version_of("k8-mydrive-[0-9]*.hsm")
    if engine_from:
        old_pkg, engine_hsm = Path(engine_from[0]), Path(engine_from[1])
        ver = old_pkg.stem.rsplit("-", 1)[1]
        engine_ver = engine_hsm.stem.rsplit("-", 1)[1]
        with zipfile.ZipFile(old_pkg) as z:
            dex = z.read("bin/k8-ecs-car.dex")
        build_nemesis.PACKAGE_VERSION = ver
        pkg = build_nemesis.package_bytes(dex, build_nemesis.APK.read_bytes())
    else:
        ver = build_nemesis.PACKAGE_VERSION
        engine_hsm, engine_ver = version_of("k8-mydrive-engine-*.hsm")
        pkg = (build_nemesis.OUT / (build_nemesis.PACKAGE_ID + "-" + ver + ".zip")).read_bytes()
    if engine_ver != ver:
        raise SystemExit("engine module %s differs from NEMESIS package %s" % (engine_ver, ver))
    fmt = dict(ver=ver, card=card_ver, phone=phone)
    pkg_name = build_nemesis.PACKAGE_ID + "-" + ver + ".zip"
    apk_name = "K8-마이드라이브-폰-" + phone + ".apk"
    nem_zip = "K8-마이드라이브-NEMESIS-USB-" + ver + ".zip"
    sg_zip = "K8-마이드라이브-서개모-USB-" + ver + ".zip"

    nemesis_usb = make_zip([
        ("NEMESIS.data", ("[nemesis]\nversion=1.0\ntype=install\npath=packages/" + pkg_name + "\n").encode()),
        ("packages/" + pkg_name, pkg),
    ])
    seogaemo_usb = make_zip([
        ("HSM/" + engine_hsm.name, engine_hsm.read_bytes()),
        ("HSM/" + card_hsm.name, card_hsm.read_bytes()),
    ])

    files = {
        "읽어보세요.txt": text(GUIDE_TOP.format(**fmt)),
        "1_휴대폰앱/" + apk_name: PHONE_APK.read_bytes(),
        "1_휴대폰앱/설치안내.txt": text(GUIDE_PHONE.format(apk=apk_name, **fmt)),
        "2_네메시스용/" + nem_zip: nemesis_usb,
        "2_네메시스용/설치안내.txt": text(GUIDE_NEMESIS.format(zip=nem_zip, **fmt)),
        "3_서개모용/" + sg_zip: seogaemo_usb,
        "3_서개모용/설치안내.txt": text(GUIDE_SEOGAEMO.format(zip=sg_zip, **fmt)),
    }
    for name, data in files.items():
        scan(name, data)

    folder_name = "K8-마이드라이브-" + ver
    out = Path(out_dir)
    folder = out / folder_name
    if folder.exists():
        shutil.rmtree(folder)
    for name, data in files.items():
        p = folder / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    bundle = out / (folder_name + ".zip")
    bundle.write_bytes(make_zip([(folder_name + "/" + n, d) for n, d in files.items()]))
    return folder, bundle, files


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", default=str(DEFAULT_OUT))
    p.add_argument("--engine-from", nargs=2, metavar=("NEMESIS_ZIP", "ENGINE_HSM"),
                   help="ship this car-tested engine instead of the current build")
    a = p.parse_args(argv)
    folder, bundle, files = build(a.out, a.engine_from)
    for n, d in files.items():
        print("%8d  %s" % (len(d), n))
    print("folder=%s\nzip=%s" % (folder, bundle))
    return 0


if __name__ == "__main__":
    sys.exit(main())
