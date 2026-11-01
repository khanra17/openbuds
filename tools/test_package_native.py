# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import os
from pathlib import Path
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
                     "bindings/c/tests/smoke.c", "docs/cli.md"]:
            file = self.root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("test source")

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self, platform):
        release = self.root / "target/release"
        release.mkdir(parents=True, exist_ok=True)
        for filename in package_native.BINARIES[platform]:
            file = release / filename
            file.write_bytes(b"native release")
            file.chmod(0o755)
        source = self.root / "target/packages/staging" / f"OpenBuds-{platform}-bundled-runtime"
        source.mkdir(parents=True)
        for filename in package_native.NOTICES:
            (source / filename).write_text("matching notices")
        (source / "SOURCE.md").write_text(f"https://github.com/example/repo/tree/{self.commit}")
        return source

    def test_every_platform_has_correct_archive_name_contents_checksum_and_modes(self):
        for platform in package_native.BINARIES:
            with self.subTest(platform=platform):
                self.prepare(platform)
                with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
                    archive = package_native.package_native(platform, self.root)
                package = f"OpenBuds-{platform}-cli-sdk"
                self.assertTrue(archive.name.startswith(package + "."))
                self.assertEqual(len(list(archive.parent.glob(f"*-cli-sdk.*"))), 2 * (list(package_native.BINARIES).index(platform) + 1))
                if platform.startswith("windows"):
                    with zipfile.ZipFile(archive) as files:
                        self.assertIn(f"{package}/buds.exe", files.namelist())
                        self.assertIn(f"{package}/openbuds.dll.lib", files.namelist())
                        self.assertIn(f"{package}/include/openbuds.h", files.namelist())
                else:
                    with tarfile.open(archive) as files:
                        self.assertIn(f"{package}/include/openbuds.h", files.getnames())
                        if os.name != "nt":  # Mode preservation is meaningful on the native Unix runners.
                            self.assertNotEqual(files.getmember(f"{package}/buds").mode & 0o111, 0)
                checksum = archive.with_suffix(archive.suffix + ".sha256").read_text().split()[0]
                self.assertEqual(checksum, hashlib.sha256(archive.read_bytes()).hexdigest())
                with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
                    with self.assertRaises(FileExistsError):
                        package_native.package_native(platform, self.root)

    def test_dirty_source_is_rejected_before_staging(self):
        with patch.object(package_native.subprocess, "check_output", return_value=" M source.py"):
            with self.assertRaises(ValueError):
                package_native.package_native("windows-x64", self.root)
        self.assertFalse((self.root / "target/native-artifacts").exists())

    def test_stale_source_manifest_is_rejected(self):
        source = self.prepare("linux-x64")
        (source / "SOURCE.md").write_text("an old commit")
        with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
            with self.assertRaises(ValueError):
                package_native.package_native("linux-x64", self.root)
        self.assertFalse((self.root / "target/native-artifacts").exists())

    def test_missing_binary_is_rejected_before_staging(self):
        self.prepare("windows-x64")
        (self.root / "target/release/openbuds.dll.lib").unlink()
        with patch.object(package_native.subprocess, "check_output", side_effect=["", self.commit]):
            with self.assertRaises(FileNotFoundError):
                package_native.package_native("windows-x64", self.root)
        self.assertFalse((self.root / "target/native-artifacts").exists())


if __name__ == "__main__":
    unittest.main()
