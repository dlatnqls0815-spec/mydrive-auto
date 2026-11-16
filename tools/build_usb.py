#!/usr/bin/env python3
"""Build the ready-to-copy USB install bundle for k8ecs.damping.

Input: private/nemesis/<id>-<ver>.zip from tools/build_nemesis.py. Output: a zip whose contents are copied to
the USB root as-is (NEMESIS.data type=install + packages/<zip>). Logs are saved to USB from the head unit app,
so no separate log stick is needed. Offline only.
"""
import argparse
import hashlib
import io
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_nemesis  # noqa: E402

DEFAULT_OUT = ROOT / "private" / "usb"
PREFIX = "k8ecs-USB설치-"

README = """K8 서스펜션 자동 전환 {ver} - USB 설치

1. 이 압축을 풀어 나온 파일과 폴더(NEMESIS.data, packages)를 USB 최상위에 그대로 넣습니다.
   (USB에 원래 있던 NEMESIS.data는 덮어쓰게 되니 필요하면 먼저 따로 보관하세요.)
2. 시동을 켜고 내비 화면이 뜬 뒤 USB를 꽂습니다.
3. 설치 확인 창이 뜨면 "예"를 누릅니다. 끝나면 USB를 빼도 됩니다.
4. 헤드유닛 "K8 서스펜션" 앱(NEMESIS Manager > 내 패키지)에서 "자동 전환 켜기"를 체크하고 저장합니다.

로그 받기: USB를 꽂은 채 앱에서 "USB로 로그 저장"을 누르면 USB에 K8ECS-LOG-날짜-시각 폴더가 생깁니다.

HMG-Seogaemo(서개모)만 쓰는 경우 (NEMESIS 패키지 설치 불필요):
- HSM 폴더의 두 모듈을 서개모 앱 > 모듈 추가 > USB에서 가져오기로 설치하고 둘 다 켭니다.
  "K8 마이 드라이브 엔진"(k8-mydrive-engine): 엔진. 켠 뒤 주차 상태에서 헤드유닛을 재시작합니다(처음만).
  "K8 마이 드라이브"(k8-mydrive): 설정 카드. 설정을 바꿔도 재시작이 필요 없습니다.
- 카드에서 "USB로 로그 저장"을 할 수 있습니다(카드가 켜져 있을 때).
- 모두 마이 드라이브(커스텀) 모드에서만 동작합니다.

전제: ECS(전자제어 서스펜션) 장착 K8(GL3), NEMESIS 설치된 Gen5W.
이미 설치된 차를 새 버전으로 바꿀 때는 NEMESIS.data의 type=install을 type=update로 바꿉니다.
"""


def _entry(name, mode):
    info = zipfile.ZipInfo(name, build_nemesis.ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    return info


def install_bundle(pkg_bytes, pkg_name, ver, hsms=()):
    desc = "[nemesis]\nversion=1.0\ntype=install\npath=packages/" + pkg_name + "\n"
    readme = README.format(ver=ver).replace("\n", "\r\n").encode("utf-8-sig")  # Windows Notepad friendly
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        z.writestr(_entry("NEMESIS.data", 0o644), desc.encode())
        z.writestr(_entry("packages/" + pkg_name, 0o644), pkg_bytes)
        for hsm_name, hsm_bytes in hsms:
            # HMG-Seogaemo imports modules from <USB>/HSM/*.hsm (its "add from USB").
            z.writestr(_entry("HSM/" + hsm_name, 0o644), hsm_bytes)
        z.writestr(_entry("읽어보세요-설치.txt", 0o644), readme)
    return buf.getvalue()


def build(out_dir):
    ver = build_nemesis.PACKAGE_VERSION
    pkg_name = build_nemesis.PACKAGE_ID + "-" + ver + ".zip"
    hsm = sorted((ROOT / "private" / "seogaemo").glob("k8-*.hsm"))
    if not hsm:
        raise SystemExit("run tools/build_seogaemo.py first")
    data = install_bundle((build_nemesis.OUT / pkg_name).read_bytes(), pkg_name, ver,
                          [(h.name, h.read_bytes()) for h in hsm])
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.glob(PREFIX + "*.zip"):
        stale.unlink()
    path = out / (PREFIX + ver + ".zip")
    path.write_bytes(data)
    return path, hashlib.sha256(data).hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", default=str(DEFAULT_OUT))
    a = p.parse_args(argv)
    path, sha = build(a.out)
    print("%s  sha256=%s" % (path, sha))
    return 0


if __name__ == "__main__":
    sys.exit(main())
