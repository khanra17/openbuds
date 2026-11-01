# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise both real desktop entry points, including the renderer, without Bluetooth."""
import argparse
import os
from pathlib import Path
import subprocess


def check(executable, argument, environment=None):
    result = subprocess.run([str(executable.resolve()), argument], capture_output=True, text=True,
                            timeout=60, check=False, env=environment)
    if result.returncode:
        raise RuntimeError(f"{executable.name} {argument} failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")
    print(f"PASS: {executable} {argument}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--external", required=True, type=Path)
    args = parser.parse_args()
    windows = args.platform.startswith("windows")
    bundled = args.image / ("OpenBuds.exe" if windows else "Contents/MacOS/OpenBuds" if args.platform.startswith("macos") else "bin/OpenBuds")
    external = args.external / ("OpenBuds.exe" if windows else "bin/OpenBuds")
    check(external, "--launcher-check")
    bundled_environment = os.environ.copy()
    bundled_environment["JAVA_HOME"] = str(args.image / "no-external-java-installed")
    bundled_environment["PATH"] = os.pathsep.join(
        entry for entry in bundled_environment.get("PATH", "").split(os.pathsep)
        if not any((Path(entry) / name).is_file() for name in ["java", "java.exe", "javaw.exe"]))
    check(bundled, "--smoke-test", bundled_environment)
    check(external, "--smoke-test")


if __name__ == "__main__":
    main()
