#!/usr/bin/env python3
"""Compile an app_process tool for the K8 head unit (API 19) and convert it to DEX 035.

Targets: probe (GPS tap), car (damping module). Uses the API 19 android.jar from
k8-ambient-sync/.tools (read-only) as bootclasspath and D8 --min-api 19. Outputs go to private/.
"""
import argparse
import hashlib
import io
import zipfile
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT.parent / "k8-ambient-sync" / ".tools" / "android19" / "android-4.4.2" / "android.jar"
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / ".local/share/android-sdk")
DEFAULT_D8 = SDK / "build-tools" / "35.0.1" / "lib" / "d8.jar"
TARGETS = {
    "probe": ([ROOT / "probe" / "src", ROOT / "core" / "src"], ROOT / "private" / "probe" / "k8-ecs-probe.dex",
              b"Lio/github/k8ecs/probe/GpsTap;"),
    "car": ([ROOT / "car" / "src", ROOT / "core" / "src"], ROOT / "private" / "car" / "k8-ecs-car.dex",
            b"Lio/github/k8ecs/car/CarMain;"),
    # Engine for the HMG-Seogaemo card (hook_apps): car + core + the hook entry. The hmgapi stubs are
    # compiled against but left out of the DEX (the real classes come from the Seogaemo hook host).
    "hook": ([ROOT / "car" / "src", ROOT / "core" / "src", ROOT / "hook" / "src", ROOT / "hookstub" / "src"],
             ROOT / "private" / "hook" / "k8ecs-hook.jar", b"Lio/github/k8ecs/hook/K8Entry;"),
}


def build(jdk, d8_jar, android_jar, target):
    roots, output, main_class = TARGETS[target]
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(Path(jdk) / "bin" / ("javac" + suffix))
    java = str(Path(jdk) / "bin" / ("java" + suffix))
    sources = sorted(str(p) for r in roots for p in r.rglob("*.java"))
    if not sources:
        raise SystemExit("no sources for " + target)
    with tempfile.TemporaryDirectory(prefix="k8ecs-dex-") as stage:
        stage = Path(stage)
        classes, dex_dir = stage / "classes", stage / "dex"
        classes.mkdir()
        dex_dir.mkdir()
        jar = stage / "android.jar"
        shutil.copyfile(android_jar, jar)
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-Xlint:-options",
                        "-bootclasspath", str(jar), "-d", str(classes)] + sources, check=True)
        class_files = sorted(str(p) for p in classes.rglob("*.class") if "hmgapi" not in p.parts)
        subprocess.run([java, "-cp", str(d8_jar), "com.android.tools.r8.D8", "--min-api", "19",
                        "--lib", str(jar), "--output", str(dex_dir)] + class_files, check=True)
        data = (dex_dir / "classes.dex").read_bytes()
    if not data.startswith(b"dex\n035\x00") or main_class not in data:
        raise SystemExit("unexpected DEX output")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.suffix == ".jar":
        if b"Lhmgapi/hook/ModEntry;" not in data:
            raise SystemExit("hook entry does not reference ModEntry")
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            info = zipfile.ZipInfo("classes.dex", (2026, 11, 16, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        data = buf.getvalue()
    output.write_bytes(data)
    return output, hashlib.sha256(data).hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--jdk", default=os.environ.get("JAVA_HOME"))
    p.add_argument("--d8", default=str(DEFAULT_D8))
    p.add_argument("--android-jar", default=str(DEFAULT_JAR))
    p.add_argument("--target", choices=sorted(TARGETS), default="probe")
    a = p.parse_args(argv)
    if not a.jdk:
        raise SystemExit("Set JAVA_HOME or pass --jdk")
    out, digest = build(a.jdk, a.d8, a.android_jar, a.target)
    print("dex=%s\ndex_sha256=%s" % (out, digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
