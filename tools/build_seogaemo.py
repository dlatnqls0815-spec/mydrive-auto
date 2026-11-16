#!/usr/bin/env python3
"""Build the HMG-Seogaemo modules in seogaemo/<id>/ into private/seogaemo/<id>-<ver>.hsm.

The module is the Seogaemo front end (settings, status, log button) of the k8ecs.damping NEMESIS package,
which does the switching. Third-party Seogaemo modules may not run shell code, so it only sets
persist.sys.k8ecs.* and calls the binder service "k8ecs". The checks below mirror the limits the
Seogaemo installer enforces (member names, text sizes, ui blocks, string references). Offline only.
"""
import argparse
import hashlib
import io
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SRCS = ROOT / "seogaemo"  # one folder per module (tab): k8-suspension, k8-steering, k8-engine
OUT = ROOT / "private" / "seogaemo"
# Built engine for hook_apps (tools/build_car_dex.py --target hook); packed as files/k8ecs-hook.jar.
HOOK_JAR = ROOT / "private" / "hook" / "k8ecs-hook.jar"
ZIP_TIME = (2026, 11, 15, 0, 0, 0)

MEMBERS = ["module.properties", "apply.sh", "check.sh", "status.sh", "ui.txt", "strings/en.txt", "strings/ko.txt"]
UI_BLOCKS = {"status", "toggle", "choice", "number", "info", "warning", "value", "button", "enable", "open", "pick_app"}
NAME = re.compile(r"[a-z0-9_]+$")


def fail(msg):
    raise ValueError(msg)


def text(path):
    data = path.read_bytes()
    if b"\r" in data or b"\t" in data or any(b < 0x20 and b != 0x0A for b in data):
        fail(f"{path.name}: control characters (CR, tab) are not allowed")
    return data.decode("utf-8")


def lines(t):
    return [l for l in t.split("\n") if l.strip() and not l.lstrip().startswith("#")]


def check(files):
    props = dict(l.split("=", 1) for l in lines(files["module.properties"]))
    for k in ("id", "version", "api", "cars"):
        if k not in props:
            fail("module.properties: missing " + k)
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", props["id"]) or len(props["id"]) > 48:
        fail("bad id")
    settings = {}
    for k, v in props.items():
        if k.startswith("setting."):
            name, rest = v.split(":", 1)
            typ, default = rest.rsplit(":", 1)
            if typ == "toggle":
                if not NAME.match(name) or default not in ("on", "off"):
                    fail("bad setting " + v)
                settings[name] = None
                continue
            c = re.fullmatch(r"choice\(([a-z0-9_|]+)\)", typ)
            if c:
                opts = c[1].split("|")
                if not NAME.match(name) or default not in opts:
                    fail("bad setting " + v)
                settings[name] = opts
                continue
            m = re.fullmatch(r"number\((\d+)\.\.(\d+)\)", typ)
            if not NAME.match(name) or not m or not int(m[1]) <= int(default) <= int(m[2]):
                fail("bad setting " + v)
            settings[name] = (int(m[1]), int(m[2]))
        elif k not in ("id", "version", "api", "cars", "restart", "icon"):
            fail("module.properties: key not allowed for third-party modules: " + k)
    en = {}
    for name in ("strings/en.txt", "strings/ko.txt"):
        t = files[name]
        if len(t.encode("utf-8")) > 2048:
            fail(name + ": over 2048 bytes")
        entries = lines(t)
        if len(entries) > 64:
            fail(name + ": over 64 entries")
        for l in entries:
            k, _, v = l.partition("=")
            # The installer measures with the head unit shell, which counts bytes: Korean takes 3 per letter.
            if not NAME.match(k) or len(k) > 40 or len(v.encode("utf-8")) > 200:
                fail(name + ": bad entry " + l)
        if name.endswith("en.txt"):
            en = dict(l.split("=", 1) for l in entries)
    for k in ("name", "summary"):
        if k not in en:
            fail("strings/en.txt: missing " + k)
    ko = dict(l.split("=", 1) for l in lines(files["strings/ko.txt"]))
    if set(ko) != set(en):
        fail("strings/ko.txt and en.txt keys differ: " + str(set(ko) ^ set(en)))

    def ref(s):
        if not s.startswith("@") or s[1:] not in en:
            fail("unknown string " + s)

    ui = files["ui.txt"]
    if len(ui.encode("utf-8")) > 1024 or len(lines(ui)) > 32:
        fail("ui.txt: over 1024 bytes or 32 lines")
    for l in lines(ui):
        block, *args = l.split()
        if block not in UI_BLOCKS or len(l.encode("utf-8")) > 200:
            fail("ui.txt: bad line " + l)
        for a in args:
            if a.startswith("setting="):
                if a[8:] not in settings:
                    fail("ui.txt: unknown setting " + a)
            elif a.startswith(("label=", "confirm=")):
                ref(a.split("=", 1)[1])
            elif a.startswith("@"):
                ref(a)
            elif a.split("=", 1)[0] in ("key", "name", "when"):
                if not NAME.match(a.split("=", 1)[1]):
                    fail("ui.txt: bad " + a)
            else:
                fail("ui.txt: argument not allowed " + a)
    verbs = {"apply.sh": {"set_prop", "call_service", "hook_apps", "put_file", "log"}, "check.sh": {"block", "warn", "log"},
             "status.sh": {"info", "fail", "log"}}
    for name, allowed in verbs.items():
        for l in lines(files[name]):
            word = l.split()[0]
            if word in ("if", "elif", "else", "fi", "then"):
                continue
            if word not in allowed:
                fail(f"{name}: verb not allowed: {word}")
            for tok in l.split()[1:]:
                if tok.startswith("@"):
                    ref(tok)
                m = re.fullmatch(r"\{setting:([a-z0-9_]+)\}", tok)
                if m and m[1] not in settings:
                    fail(f"{name}: unknown setting {tok}")
                if tok.startswith("files/") and tok not in files:
                    fail(f"{name}: missing payload {tok}")
                if word == "set_prop" and tok.startswith("persist") and len(tok) > 31:
                    fail(f"{name}: property name over 31 characters: {tok}")
    return props


def check_bridge_lists():
    """The engine snaps phone/app values to the card's lists (SeogaemoBridge.CARD): keep them equal."""
    java = (ROOT / "car" / "src" / "io" / "github" / "k8ecs" / "car" / "SeogaemoBridge.java").read_text(encoding="utf-8")
    props = (SRCS / "k8-mydrive" / "module.properties").read_text(encoding="utf-8")
    decl = dict(re.findall(r"setting\.\d+=(\w+):(\S+):", props))
    for name, prop, div, lst in re.findall(r'\{"(\w+)", "(\w+)", "(\d+)", "([0-9|]*)"\}', java):
        want = "toggle" if div == "0" else "choice(" + lst + ")"
        if decl.get(name) != want:
            fail("SeogaemoBridge.CARD %s does not match module.properties (%s vs %s)" % (name, want, decl.get(name)))


def build_all(out_dir):
    check_bridge_lists()
    return [build(src, out_dir) for src in sorted(p for p in SRCS.iterdir() if (p / "module.properties").is_file())]


def build(src, out_dir):
    members = MEMBERS + (["README.md"] if (src / "README.md").is_file() else [])  # shown as the card's Help
    files = {m: text(src / m) for m in members}
    payload = {}
    for p in sorted((src / "files").glob("*")) if (src / "files").is_dir() else []:
        payload["files/" + p.name] = p.read_bytes()
    if "hook_apps files/k8ecs-hook.jar" in files["apply.sh"]:
        payload["files/k8ecs-hook.jar"] = HOOK_JAR.read_bytes()
    props = check(dict(files, **{k: "" for k in payload}))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        for m in members:
            info = zipfile.ZipInfo(m, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, files[m].encode("utf-8"))
        for m, data in payload.items():
            info = zipfile.ZipInfo(m, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    data = buf.getvalue()
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.glob(props["id"] + "-*.hsm"):
        stale.unlink()
    path = out / (props["id"] + "-" + props["version"] + ".hsm")
    path.write_bytes(data)
    return path, hashlib.sha256(data).hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", default=str(OUT))
    a = p.parse_args(argv)
    for path, sha in build_all(a.out):
        print("hsm=%s sha256=%s" % (path.name, sha))
    return 0


if __name__ == "__main__":
    sys.exit(main())
