# SPDX-License-Identifier: GPL-3.0-or-later
"""Package standalone CLI applications and separate C-compatible SDKs."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BINARIES = {
    "windows-x64": {"cli": ("buds.exe",), "c-sdk": ("openbuds.dll", "openbuds.dll.lib")},
    "linux-x64": {"cli": ("buds",), "c-sdk": ("libopenbuds.so",)},
    "macos-arm64": {"cli": ("buds",), "c-sdk": ("libopenbuds.dylib",)},
}
NOTICES = ("LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.html",
           "DEPENDENCIES.json", "SOURCE.md")


def clean_commit(root):
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root, text=True).strip():
        raise ValueError("Commit the matching source and use a clean checkout before packaging")
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()


def checksum(archive):
    with archive.open("rb") as stream:
        value = hashlib.file_digest(stream, "sha256").hexdigest()
    archive.with_suffix(archive.suffix + ".sha256").write_text(f"{value}  {archive.name}\n", encoding="utf-8")


def package_native(platform, product, root=ROOT):
    if platform not in BINARIES or product not in BINARIES[platform]:
        raise ValueError(f"Unsupported platform/product: {platform}/{product}")
    commit = clean_commit(root)
    source = root / "target/packages/staging" / f"OpenBuds-{platform}-bundled-runtime"
    if f"/tree/{commit}" not in (source / "SOURCE.md").read_text(encoding="utf-8"):
        raise ValueError("Desktop source/notices do not match the current commit")
    release = root / "target/release"
    for filename in BINARIES[platform][product]:
        if not (release / filename).is_file():
            raise FileNotFoundError(release / filename)
    for filename in NOTICES:
        if not (source / filename).is_file():
            raise FileNotFoundError(source / filename)
    package_name = f"OpenBuds-{platform}-{product}"
    # Never reuse a staging directory restored by Cargo's CI cache.
    with tempfile.TemporaryDirectory(prefix="openbuds-native-", dir=root / "target") as temporary:
        output = Path(temporary) / package_name
        output.mkdir()
        for filename in BINARIES[platform][product]:
            shutil.copy2(release / filename, output / filename)
        if product == "c-sdk":
            shutil.copytree(root / "bindings/c/include", output / "include")
            shutil.copy2(root / "bindings/c/README.md", output / "C-SDK.md")
            shutil.copy2(root / "bindings/c/tests/smoke.c", output / "smoke.c")
            shutil.copy2(root / "docs/sdk.md", output / "SDK.md")
        else:
            shutil.copy2(root / "docs/cli.md", output / "CLI.md")
        for filename in NOTICES:
            shutil.copy2(source / filename, output / filename)
        (output / "BUILD.txt").write_text(
            f"Product: {product}\nPlatform: {platform}\nCommit: {commit}\n"
            "Optimized Cargo release build. No publisher signing. See SOURCE.md.\n"
            "macOS has no built-in Bluetooth transport. Only Air7 is implemented.\n", encoding="utf-8")
        archive = Path(shutil.make_archive(str(root / "target/packages" / package_name),
            "zip" if platform.startswith("windows") else "gztar", root_dir=output.parent, base_dir=output.name))
        checksum(archive)
        return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=BINARIES, required=True)
    parser.add_argument("--product", choices=["cli", "c-sdk"], required=True)
    args = parser.parse_args()
    print(package_native(args.platform, args.product))


if __name__ == "__main__":
    main()
