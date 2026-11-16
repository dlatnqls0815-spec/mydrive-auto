"""Offline check: compile the core for Java 7 and run its tests. Needs JAVA_HOME (JDK 17 works)."""
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main() -> int:
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        print("JAVA_HOME is not set", file=sys.stderr)
        return 2
    bin_dir = pathlib.Path(java_home) / "bin"
    sources = [str(p) for d in ("core/src", "tests/java") for p in sorted((ROOT / d).rglob("*.java"))]
    with tempfile.TemporaryDirectory() as out:
        subprocess.run(
            [str(bin_dir / "javac"), "--release", "7", "-Xlint:-options", "-encoding", "UTF-8", "-d", out, *sources],
            check=True,
        )
        subprocess.run([str(bin_dir / "java"), "-ea", "-cp", out, "io.github.k8ecs.core.TestMain"], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
