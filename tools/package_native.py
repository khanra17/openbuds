# SPDX-License-Identifier: GPL-3.0-or-later
"""Package standalone CLI/C SDK downloads from the same release source/notices."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BINARIES = {
    "windows-x64": ("buds.exe", "openbuds.dll", "openbuds.dll.lib"),
    "linux-x64": ("buds", "libopenbuds.so"),
    "macos-arm64": ("buds", "libopenbuds.dylib"),
}
NOTICES = ("LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.html",
           "DEPENDENCIES.json", "SOURCE.md", "workspace-source.tar.gz")


def package_native(platform, root=ROOT):
    if platform not in BINARIES:
        raise ValueError(f"Unsupported platform: {platform}")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root, text=True).strip():
        raise ValueError("Commit the matching source and use a clean checkout before packaging")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    source = root / "target/packages/staging" / f"OpenBuds-{platform}-bundled-runtime"
    if f"/tree/{commit}" not in (source / "SOURCE.md").read_text(encoding="utf-8"):
        raise ValueError("Desktop source/notices do not match the current commit")
    release = root / "target/release"
    for filename in BINARIES[platform]:
        if not (release / filename).is_file():
            raise FileNotFoundError(release / filename)
    for filename in NOTICES:
        if not (source / filename).is_file():
            raise FileNotFoundError(source / filename)
    package_name = f"OpenBuds-{platform}-cli-sdk"
    # Cargo caches can restore old packaging directories. Build in an isolated
    # temporary tree and never reuse those files in a new source-matched archive.
    with tempfile.TemporaryDirectory(prefix="openbuds-native-", dir=root / "target") as temporary:
        output = Path(temporary) / package_name
        output.mkdir(parents=True, exist_ok=False)
        for filename in BINARIES[platform]:
            shutil.copy2(release / filename, output / filename)
        shutil.copytree(root / "bindings/c/include", output / "include")
        shutil.copy2(root / "bindings/c/README.md", output / "C-SDK.md")
        shutil.copy2(root / "bindings/c/tests/smoke.c", output / "smoke.c")
        shutil.copy2(root / "docs/cli.md", output / "CLI.md")
        for filename in NOTICES:
            shutil.copy2(source / filename, output / filename)
        (output / "BUILD.txt").write_text(
            f"Platform: {platform}\nCommit: {commit}\nNo publisher signing. See SOURCE.md and CLI.md.\n", encoding="utf-8")
        archive = Path(shutil.make_archive(str(root / "target/packages" / package_name),
            "zip" if platform.startswith("windows") else "gztar", root_dir=output.parent, base_dir=output.name))
        with archive.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        archive.with_suffix(archive.suffix + ".sha256").write_text(f"{checksum}  {archive.name}\n", encoding="utf-8")
        return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=BINARIES, required=True)
    args = parser.parse_args()
    print(package_native(args.platform))


if __name__ == "__main__":
    main()
