#!/usr/bin/env python3
"""Build the NEMESIS package k8ecs.damping (car module + head unit settings APK) and a USB install layout.

Inputs: private/car/k8-ecs-car.dex (tools/build_car_dex.py --target car) and
private/hu/k8-ecs-hu.apk (tools/build_phone.py --target hu). Offline only; installs nothing.
"""
import argparse
import hashlib
import io
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NEM = ROOT / "nemesis"
DEX = ROOT / "private" / "car" / "k8-ecs-car.dex"
APK = ROOT / "private" / "hu" / "k8-ecs-hu.apk"
OUT = ROOT / "private" / "nemesis"

PACKAGE_ID = "k8ecs.damping"
PACKAGE_VERSION = "0.4.6"
SHORT_DESC = "My Drive Auto: Eco/Sport for suspension, steering, engine"
DETAILED_DESC = ("Switches the My Drive suspension, steering and engine items between Eco and Sport by speed, "
                 "lateral G and accelerator. Works only in My Drive mode, off until enabled in its app. "
                 "Uses only the stock settings call. Use at your own risk.")
ZIP_TIME = (2026, 11, 15, 0, 0, 0)


def descriptor():
    if len(SHORT_DESC) > 64 or len(DETAILED_DESC) > 256:
        raise ValueError("NEMESIS description limit exceeded")
    return "\n".join([
        "[nemesis]",
        "version=1.1",
        "id=" + PACKAGE_ID,
        "name=My Drive Auto",
        "type=application,service",
        "package-version=" + PACKAGE_VERSION,
        'short-desc="' + SHORT_DESC + '"',
        'detailed-desc="' + DETAILED_DESC + '"',
        "os=any",
        "models=any",
        "postinstall=scripts/postinstall.sh",
        "",
        "[app.0]",
        "kind=apk",
        "path=app/k8-ecs-hu.apk",
        "package=io.github.k8ecs.hu",
        "component=io.github.k8ecs.hu/.HuActivity",
        "",
        "[app.1]",
        "kind=sh",
        "path=bin/status.sh",
        "uid=root",
        "",
        "[service.0]",
        "name=damping",
        "kind=sh",
        "path=svc/damping.sh",
        "uid=system",
        "mode=restart",
        "",
    ])


def entry(name, mode):
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    return info


def script(path):
    text = path.read_bytes().decode("utf-8")
    if not text.startswith("#!/system/bin/sh\n") or "\r" in text:
        raise ValueError(str(path) + ": must be an LF-only /system/bin/sh script")
    return text


def package_bytes(dex, apk):
    if not dex.startswith(b"dex\n035\x00") or b"Lio/github/k8ecs/car/CarMain;" not in dex:
        raise ValueError("not the API 19 car module DEX")
    if not apk.startswith(b"PK"):
        raise ValueError("not an APK")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        z.writestr(entry("NEMESIS.data", 0o644), descriptor())
        z.writestr(entry("svc/damping.sh", 0o755), script(NEM / "svc" / "damping.sh"))
        z.writestr(entry("bin/status.sh", 0o755), script(NEM / "bin" / "status.sh"))
        z.writestr(entry("bin/export_usb.sh", 0o755), script(NEM / "bin" / "export_usb.sh"))
        z.writestr(entry("scripts/postinstall.sh", 0o755), script(NEM / "scripts" / "postinstall.sh"))
        z.writestr(entry("bin/k8-ecs-car.dex", 0o644), dex)
        z.writestr(entry("app/k8-ecs-hu.apk", 0o644), apk)
    return buf.getvalue()


def build(out_dir):
    data = package_bytes(DEX.read_bytes(), APK.read_bytes())
    name = PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip"
    out = Path(out_dir)
    packages = out / "usb" / "packages"
    packages.mkdir(parents=True, exist_ok=True)
    for folder in (out, packages):
        for stale in folder.glob(PACKAGE_ID + "-*.zip"):
            stale.unlink()
    (out / name).write_bytes(data)
    (packages / name).write_bytes(data)
    (out / "usb" / "NEMESIS.data").write_text(
        "[nemesis]\nversion=1.0\ntype=install\npath=packages/" + name + "\n", encoding="utf-8", newline="\n")
    return out / name, hashlib.sha256(data).hexdigest(), hashlib.sha256(DEX.read_bytes()).hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", default=str(OUT))
    a = p.parse_args(argv)
    path, zsha, dsha = build(a.out)
    print("zip=%s\nzip_sha256=%s\ndex_sha256=%s" % (path, zsha, dsha))
    return 0


if __name__ == "__main__":
    sys.exit(main())
