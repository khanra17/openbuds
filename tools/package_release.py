# SPDX-License-Identifier: GPL-3.0-or-later
"""Package Compose app images/APKs, CLI, notices and matching workspace source."""
import argparse
import hashlib
import html
import json
import os
from desktop_variants import stage_external
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LICENSE_NAME = re.compile(r"^(licen[cs]es?|copying|copyright|notice)([._-].*)?$", re.I)


def license_files(root):
    if not root.is_dir():
        return
    for entry in sorted(root.iterdir()):
        if entry.is_symlink() or entry.name in {".git", "target", "out", "build"}:
            continue
        if entry.is_dir() and re.fullmatch(r"licen[cs]es?", entry.name, re.I):
            yield from (file for file in entry.rglob("*") if file.is_file())
        elif entry.is_file() and LICENSE_NAME.match(entry.name):
            yield entry


def digest(file):
    with file.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True)
    binary = parser.add_mutually_exclusive_group(required=True)
    binary.add_argument("--image", type=Path)
    binary.add_argument("--apk", type=Path)
    parser.add_argument("--runtime", choices=["bundled", "external-java"], default="bundled")
    parser.add_argument("--launcher", type=Path, help="Native launcher binary for the external-Java variant")
    parser.add_argument("--rust-metadata", required=True, type=Path)
    parser.add_argument("--jvm-metadata", required=True, type=Path)
    args = parser.parse_args()
    if args.apk and args.runtime != "bundled":
        parser.error("--runtime applies only to desktop packages")
    if args.image and args.runtime == "external-java" and not args.launcher:
        parser.error("--launcher is required for an external-Java package")
    # An archive of HEAD must describe the binaries, not silently omit local source.
    # Validate before touching staging or existing release outputs.
    status = subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT, text=True
    )
    if status.strip():
        parser.error("Commit the matching source and use a clean checkout before packaging; HEAD would omit local changes")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    output = ROOT / "target/packages"
    variant = "" if args.apk else ("-bundled-runtime" if args.runtime == "bundled" else "-external-java")
    name = f"OpenBuds-{args.platform}{variant}"
    stage = output / "staging" / name
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    if args.apk:
        if "release" not in args.apk.parts or not args.apk.is_file():
            parser.error("An optimized release APK is required")
        shutil.copy2(args.apk, stage / "OpenBuds.apk")
    else:
        if "main-release" not in args.image.parts or not args.image.is_dir():
            parser.error("Use createReleaseDistributable's main-release app image")
        if args.runtime == "external-java":
            stage_external(args.image, args.platform, args.launcher, stage / "OpenBuds")
        else:
            shutil.copytree(args.image, stage / ("OpenBuds.app" if args.platform.startswith("macos") else "OpenBuds"), symlinks=True)
        (stage / "RUNTIME.json").write_text(json.dumps({
            "variant": args.runtime, "java_included": args.runtime == "bundled",
            "external_java_minimum": 21, "platform": args.platform,
        }, indent=2) + "\n", encoding="utf-8")
        windows = args.platform.startswith("windows")
        shutil.copy2(ROOT / "target/release" / ("buds.exe" if windows else "buds"), stage)
        if windows:
            shutil.copy2(ROOT / "target/release/openbuds-tray.exe", stage / "OpenBudsTray.exe")
    for file in ["LICENSE", "THIRD_PARTY_NOTICES.md", "Cargo.lock"]:
        shutil.copy2(ROOT / file, stage / file)
    source = subprocess.check_output(["git", "archive", "--format=tar.gz", "--prefix=OpenBuds-source/", "HEAD"], cwd=ROOT)
    (stage / "workspace-source.tar.gz").write_bytes(source)
    lock = {(p["name"], p["version"]): p for p in tomllib.loads((ROOT / "Cargo.lock").read_text())["package"]}
    dependencies = []
    notices = ["<!doctype html><meta charset='utf-8'><title>OpenBuds dependency notices</title>",
               "<style>body{max-width:1000px;margin:40px auto;font:16px system-ui}pre{white-space:pre-wrap}</style>",
               "<h1>Dependency notices</h1><p>Dependencies retain their own licenses. See LICENSE and SOURCE.md.</p>"]
    for package in sorted(json.loads(args.rust_metadata.read_text(encoding="utf-8"))["packages"], key=lambda p: (p["name"], p["version"])):
        root = Path(package["manifest_path"]).parent
        item = {k: package.get(k) for k in ["name", "version", "license", "repository"]}
        if package.get("source"):
            item["source_archive"] = f"https://static.crates.io/crates/{package['name']}/{package['name']}-{package['version']}.crate"
            item["sha256"] = lock.get((package["name"], package["version"]), {}).get("checksum")
        else:
            item["source"] = f"https://github.com/khanra17/openbuds-air7/tree/{commit}"
        dependencies.append(item)
        notices.append(f"<h2>{html.escape(package['name'])} {html.escape(package['version'])}</h2><p>{html.escape(package.get('license') or 'See source')}</p>")
        files = set(license_files(root))
        if package.get("license_file"):
            files.add(root / package["license_file"])
        for file in sorted(files):
            if file.is_file():
                notices.append(f"<h3>{html.escape(file.name)}</h3><pre>{html.escape(file.read_text(encoding='utf-8', errors='replace'))}</pre>")
    for line in args.jvm_metadata.read_text(encoding="utf-8").splitlines():
        group, artifact, version, file = line.split("\t", 3)
        coordinates = f"{group}:{artifact}:{version}"
        path = Path(file)
        dependencies.append({"maven": coordinates, "binary_sha256": digest(path),
                             "source_coordinates": f"{coordinates}:sources", "repositories": ["https://repo.maven.apache.org/maven2/", "https://dl.google.com/dl/android/maven2/"]})
        notices.append(f"<h2>{html.escape(coordinates)}</h2>")
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for entry in archive.infolist():
                    if entry.file_size <= 2 * 1024 * 1024 and LICENSE_NAME.match(Path(entry.filename).name):
                        text = archive.read(entry).decode("utf-8", errors="replace")
                        notices.append(f"<h3>{html.escape(entry.filename)}</h3><pre>{html.escape(text)}</pre>")
    (stage / "THIRD_PARTY_LICENSES.html").write_text("\n".join(notices), encoding="utf-8")
    (stage / "DEPENDENCIES.json").write_text(json.dumps(dependencies, indent=2) + "\n", encoding="utf-8")
    (stage / "SOURCE.md").write_text(
        f"# Source and build information\n\nBuilt from https://github.com/khanra17/openbuds-air7/tree/{commit}\n\n"
        "The workspace archive contains that revision, not an offline bundle of all third-party sources. "
        "DEPENDENCIES.json records pinned Cargo source archives/checksums and JVM source coordinates. "
        "Use the repository build instructions and preserve applicable dependency notices. Native Skiko/Skia "
        "source pins are in the corresponding Skiko source repository. Before redistributing elsewhere, "
        "provide access to all required matching GPL corresponding sources/build information, not only this workspace archive.\n",
        encoding="utf-8")
    runtime_note = ("External-Java variant: NO Java is included. Install a complete matching-architecture Java 21+ desktop JRE/JDK. JAVA_HOME takes precedence over PATH. The launcher checks compatibility and never downloads Java. See OpenBuds/RUNTIME.txt.\n\n"
                    if args.image and args.runtime == "external-java" else "Desktop bundled-runtime variant: Java is included; no Java installation is required.\n\n" if args.image else "")
    (stage / "README.txt").write_text(
        "OpenBuds 0.4\n\n" + runtime_note + "Windows: start OpenBudsTray.exe for a small native tray entry; "
        "click it to launch the full UI. Closing the UI exits its JVM. Or launch OpenBuds/OpenBuds.exe directly.\n"
        "Linux: OpenBuds/bin/OpenBuds. macOS bundled: OpenBuds.app; external-Java: OpenBuds/bin/OpenBuds (no Bluetooth transport; cannot connect to Air7).\n"
        "Desktop builds have no publisher signing or notarization; OS approval may be required.\n"
        "Android: install OpenBuds.apk. It is a non-debuggable, R8/resource-shrunk release using optimized Rust. "
        "Its fixed development key is deliberately PUBLIC: anyone can sign forged updates. It provides no publisher authentication.\n"
        "An older local debug-key installation must be uninstalled once if its signing certificate differs.\n\n"
        "Pair Air7 in system Bluetooth settings first. Android needs Nearby devices permission on Android 12+. "
        "The app does not scan or access the internet. Android retains its connection in a visible foreground service; "
        "removing OpenBuds from Recents closes the session and phone actions.\n"
        "Unknown device values remain unknown. Never use finding tones while worn. "
        "Sound-stop cleanup is best-effort, not a hardware watchdog.\n\n"
        "CLI: buds --help; buds --json status; buds eq --help; buds --json rpc. "
        "Full JSON protocol: docs/cli.md in the workspace source archive.\n",
        encoding="utf-8")
    if args.platform.startswith("linux"):
        destination = output / f"{name}.tar.gz"
        with tarfile.open(destination, "w:gz") as archive:
            archive.add(stage, arcname=name)
    else:
        destination = output / f"{name}.zip"
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(stage.rglob("*")):
                relative = str(Path(name) / file.relative_to(stage))
                if file.is_symlink():
                    entry = zipfile.ZipInfo(relative)
                    entry.create_system = 3
                    entry.external_attr = file.lstat().st_mode << 16
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, os.readlink(file))
                elif file.is_file() or file.is_dir():
                    archive.write(file, relative)
    destination.with_suffix(destination.suffix + ".sha256").write_text(f"{digest(destination)}  {destination.name}\n")
    print(destination)


if __name__ == "__main__":
    main()
