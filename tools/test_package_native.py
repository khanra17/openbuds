# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import package_native


class NativePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.commit = "a" * 40
        for name in ["bindings/c/include/openbuds.h", "bindings/c/README.md",
                     "bindings/c/tests/smoke.c", "docs/cli.md", "docs/sdk.md"]:
            file = self.root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("test source")

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self, platform):
        release = self.root / "target/release"
        release.mkdir(parents=True, exist_ok=True)
        for binaries in package_native.BINARIES[platform].values():
            for filename in binaries:
                file = release / filename
                file.write_bytes(b"native release")
                file.chmod(0o755)
        source = self.root / "target/packages/staging" / f"OpenBuds-{platform}-bundled-runtime"
        source.mkdir(parents=True)
        for filename in package_native.NOTICES:
            (source / filename).write_text("matching notices")
        (source / "SOURCE.md").write_text(f"https://github.com/example/repo/tree/{self.commit}")
        return source

    def test_each_platform_has_separate_cli_and_c_sdk_with_correct_contents(self):
        for platform, products in package_native.BINARIES.items():
            self.prepare(platform)
            cached = self.root / "target/native-artifacts" / f"OpenBuds-{platform}-cli-sdk"
            cached.mkdir(parents=True)
            (cached / "stale-marker").write_text("must not enter the new archive")
            for product in products:
                with self.subTest(platform=platform, product=product):
                    with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
                        archive = package_native.package_native(platform, product, self.root)
                    package = f"OpenBuds-{platform}-{product}"
                    self.assertTrue(archive.name.startswith(package + "."))
                    if platform.startswith("windows"):
                        with zipfile.ZipFile(archive) as files:
                            names = files.namelist()
                    else:
                        with tarfile.open(archive) as files:
                            names = files.getnames()
                            if product == "cli" and os.name != "nt":
                                self.assertNotEqual(files.getmember(f"{package}/buds").mode & 0o111, 0)
                    for name in products[product]:
                        self.assertIn(f"{package}/{name}", names)
                    for name in products["c-sdk" if product == "cli" else "cli"]:
                        self.assertNotIn(f"{package}/{name}", names)
                    self.assertEqual(f"{package}/include/openbuds.h" in names, product == "c-sdk")
                    self.assertEqual(f"{package}/smoke.c" in names, product == "c-sdk")
                    self.assertEqual(f"{package}/CLI.md" in names, product == "cli")
                    self.assertIn(f"{package}/workspace-source.tar.gz", names)
                    self.assertNotIn(f"{package}/stale-marker", names)
                    checksum = archive.with_suffix(archive.suffix + ".sha256").read_text().split()[0]
                    self.assertEqual(checksum, hashlib.sha256(archive.read_bytes()).hexdigest())
                    with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
                        self.assertEqual(archive, package_native.package_native(platform, product, self.root))
                    self.assertTrue((cached / "stale-marker").exists())
                    self.assertEqual(list((self.root / "target").glob("openbuds-native-*")), [])
        self.assertEqual(len(list((self.root / "target/packages").glob("*-cli.*"))), 6)
        self.assertEqual(len(list((self.root / "target/packages").glob("*-c-sdk.*"))), 6)
        self.assertEqual(list((self.root / "target/packages").glob("*-cli-sdk.*")), [])

    def test_dirty_source_is_rejected_before_staging(self):
        with patch.object(package_native.subprocess, "check_output", return_value=" M source.py"):
            with self.assertRaises(ValueError):
                package_native.package_native("windows-x64", "cli", self.root)
        self.assertFalse((self.root / "target/native-artifacts").exists())

    def test_stale_source_manifest_is_rejected(self):
        source = self.prepare("linux-x64")
        (source / "SOURCE.md").write_text("an old commit")
        with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
            with self.assertRaises(ValueError):
                package_native.package_native("linux-x64", "cli", self.root)
        self.assertFalse((self.root / "target/native-artifacts").exists())

    def test_missing_c_library_does_not_block_standalone_cli(self):
        self.prepare("windows-x64")
        (self.root / "target/release/openbuds.dll.lib").unlink()
        with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
            with self.assertRaises(FileNotFoundError):
                package_native.package_native("windows-x64", "c-sdk", self.root)
        with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
            self.assertTrue(package_native.package_native("windows-x64", "cli", self.root).exists())

    def test_rust_sdk_contains_committed_source_not_build_outputs(self):
        def git(*args):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        (self.root / ".gitignore").write_text("/target/\n*.keystore\n")
        (self.root / "Cargo.toml").write_text('[workspace]\nmembers = []\n')
        (self.root / "Cargo.lock").write_text("version = 4\n")
        git("init")
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "fixture")
        (self.root / "target/release").mkdir(parents=True)
        (self.root / "target/release/buds").write_bytes(b"not SDK source")
        (self.root / "ignored.keystore").write_bytes(b"must not ship")
        archive = package_native.package_rust_sdk(self.root)
        with tarfile.open(archive) as files:
            names = files.getnames()
            for name in ["Cargo.toml", "Cargo.lock", "docs/sdk.md", "bindings/c/include/openbuds.h"]:
                self.assertIn(f"OpenBuds-rust-sdk/{name}", names)
            self.assertFalse(any("/target/" in name or name.endswith(".keystore") for name in names))
        self.assertTrue(archive.with_suffix(".gz.sha256").is_file())
        (self.root / "docs/sdk.md").write_text("uncommitted source")
        with self.assertRaises(ValueError):
            package_native.package_rust_sdk(self.root)


if __name__ == "__main__":
    unittest.main()
