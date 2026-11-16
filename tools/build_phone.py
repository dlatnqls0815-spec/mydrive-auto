#!/usr/bin/env python3
"""Build the K8 ECS APKs without Gradle: phone (Android 13+) or hu (head unit settings, API 19).

javac (Java 8 bytecode against the SDK android.jar) -> D8 -> aapt2 link (manifest + launcher icon
from tools/app_icon.py) ->
zipalign -> apksigner with a local debug key under private/. Offline only; installs nothing.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "private" / "phone"
KEYSTORE = PRIVATE / "debug.p12"
API19_JAR = ROOT.parent / "k8-ambient-sync" / ".tools" / "android19" / "android-4.4.2" / "android.jar"
TARGETS = {
    # name: (sources, manifest, output, min sdk, target sdk, java level, platform jar override)
    "phone": ([ROOT / "phone" / "src", ROOT / "ui" / "src", ROOT / "core" / "src"], ROOT / "phone" / "AndroidManifest.xml",
              PRIVATE / "k8-ecs-phone.apk", "33", "35", "8", None),
    "hu": ([ROOT / "hu" / "src", ROOT / "ui" / "src"], ROOT / "hu" / "AndroidManifest.xml",
           ROOT / "private" / "hu" / "k8-ecs-hu.apk", "19", "19", "7", API19_JAR),
}


def run(command, **kwargs):
    subprocess.run([str(c) for c in command], check=True, **kwargs)


def build(sdk, platform, build_tools, jdk, target="phone"):
    SOURCES, MANIFEST, OUTPUT, MIN_SDK, TARGET_SDK, JAVA_LEVEL, jar_override = TARGETS[target]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    exe = ".exe" if os.name == "nt" else ""
    tools = Path(sdk) / "build-tools" / build_tools
    android_jar = jar_override or Path(sdk) / "platforms" / platform / "android.jar"
    javac, java, keytool = (Path(jdk) / "bin" / (n + exe) for n in ("javac", "java", "keytool"))
    aapt2, zipalign = tools / ("aapt2" + exe), tools / ("zipalign" + exe)
    d8, apksigner = tools / "lib" / "d8.jar", tools / "lib" / "apksigner.jar"
    for required in (android_jar, aapt2, zipalign, d8, apksigner, javac):
        if not required.is_file():
            raise FileNotFoundError(required)
    PRIVATE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="k8-ecs-") as stage:
        stage = Path(stage)
        classes, dex = stage / "classes", stage / "dex"
        classes.mkdir()
        dex.mkdir()
        jar = stage / "android.jar"
        shutil.copyfile(android_jar, jar)
        manifest = stage / "AndroidManifest.xml"
        shutil.copyfile(MANIFEST, manifest)
        sources = sorted(str(p) for root in SOURCES for p in root.rglob("*.java"))
        run([javac, "-encoding", "UTF-8", "-source", JAVA_LEVEL, "-target", JAVA_LEVEL, "-Xlint:-options",
             "-bootclasspath", jar, "-d", classes] + sources)
        run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", MIN_SDK, "--lib", jar,
             "--output", dex] + sorted(str(p) for p in classes.rglob("*.class")))
        sys.path.insert(0, str(ROOT / "tools"))
        import app_icon
        res, flat = stage / "res", stage / "res.zip"
        app_icon.write_res(res, adaptive=target == "phone")  # API 19 has no adaptive icons
        run([aapt2, "compile", "--dir", res, "-o", flat])
        unsigned = stage / "unsigned.apk"
        run([aapt2, "link", "-o", unsigned, "--manifest", manifest, "-I", jar, "-R", flat, "--auto-add-overlay",
             "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK])
        with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
            apk.write(dex / "classes.dex", "classes.dex")
        aligned = stage / "aligned.apk"
        run([zipalign, "-f", "-p", "4", unsigned, aligned])
        if not KEYSTORE.is_file():
            new_key = stage / "debug.p12"
            run([keytool, "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", new_key,
                 "-storepass", "android", "-keypass", "android", "-alias", "k8ecs",
                 "-dname", "CN=K8 ECS Phone Debug", "-keyalg", "RSA", "-keysize", "2048",
                 "-validity", "10000"])
            shutil.copyfile(new_key, KEYSTORE)
        key = stage / "key.p12"
        shutil.copyfile(KEYSTORE, key)
        signed = stage / "signed.apk"
        run([java, "-jar", apksigner, "sign", "--ks", key, "--ks-key-alias", "k8ecs",
             "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned])
        run([java, "-jar", apksigner, "verify", "--min-sdk-version", MIN_SDK, signed])
        shutil.copyfile(signed, OUTPUT)
    return OUTPUT, hashlib.sha256(OUTPUT.read_bytes()).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sdk", required=True, help="Android SDK root")
    parser.add_argument("--platform", default="android-36")
    parser.add_argument("--build-tools", default="35.0.1")
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--target", choices=sorted(TARGETS), default="phone")
    args = parser.parse_args(argv)
    if not args.jdk:
        raise SystemExit("Set JAVA_HOME or pass --jdk")
    output, digest = build(args.sdk, args.platform, args.build_tools, args.jdk, args.target)
    print("apk=%s\napk_sha256=%s" % (output, digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
