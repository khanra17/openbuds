# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from desktop_variants import stage_external
import package_release


class DesktopVariantsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.image = self.root / "main-release/OpenBuds"
        app = self.image / "app"
        app.mkdir(parents=True)
        (app / "first.jar").write_bytes(b"first release jar")
        (app / "second.jar").write_bytes(b"second release jar")
        (app / "skiko-test.dll").write_bytes(b"native rendering library")
        (app / "skiko-test.dll.sha256").write_bytes(b"checksum")
        (app / "OpenBuds.cfg").write_text(
            "app.mainclass=org.openbuds.desktop.MainKt\n"
            "app.classpath=$APPDIR\\second.jar\napp.classpath=$APPDIR\\first.jar\n", encoding="utf-8")
        (self.image / "runtime").mkdir()
        (self.image / "runtime/vm.bin").write_bytes(b"bundled Java")
        self.launcher = self.root / "launcher.exe"
        self.launcher.write_bytes(b"native launcher")

    def tearDown(self):
        self.temp.cleanup()

    def test_external_contains_exact_jars_and_order_but_no_runtime(self):
        output = stage_external(self.image, "windows-x64", self.launcher, self.root / "external")
        self.assertFalse((output / "runtime").exists())
        self.assertEqual((output / "app/first.jar").read_bytes(), (self.image / "app/first.jar").read_bytes())
        self.assertEqual((output / "app/classpath.txt").read_text(), "second.jar\nfirst.jar\n")
        self.assertEqual((output / "OpenBuds.exe").read_bytes(), self.launcher.read_bytes())
        self.assertTrue((self.image / "runtime/vm.bin").is_file())
        self.assertEqual((output / "app/skiko-test.dll").read_bytes(), b"native rendering library")
        self.assertEqual((output / "app/skiko-test.dll.sha256").read_bytes(), b"checksum")

    def test_missing_entrypoint_or_jar_is_rejected_before_creating_output(self):
        cfg = self.image / "app/OpenBuds.cfg"
        for content in ["app.mainclass=other.Main", "app.mainclass=org.openbuds.desktop.MainKt\napp.classpath=$APPDIR/missing.jar"]:
            cfg.write_text(content)
            with self.assertRaises(ValueError):
                stage_external(self.image, "windows-x64", self.launcher, self.root / "bad")
            self.assertFalse((self.root / "bad").exists())

    def test_output_cannot_be_nested_inside_bundled_image(self):
        output = self.image / "app/external"
        with self.assertRaises(ValueError):
            stage_external(self.image, "windows-x64", self.launcher, output)
        self.assertFalse(output.exists())

    def test_existing_output_is_never_overwritten(self):
        output = self.root / "external"
        output.mkdir()
        marker = output / "keep"
        marker.write_text("keep")
        with self.assertRaises(FileExistsError):
            stage_external(self.image, "windows-x64", self.launcher, output)
        self.assertEqual(marker.read_text(), "keep")

    def test_linux_app_image_uses_lib_app(self):
        linux = self.root / "main-release/linux/OpenBuds"
        import shutil
        shutil.copytree(self.image / "app", linux / "lib/app")
        output = stage_external(linux, "linux-x64", self.launcher, self.root / "linux-external")
        self.assertEqual((output / "app/first.jar").read_bytes(), b"first release jar")
        self.assertTrue((output / "bin/OpenBuds").is_file())
        if os.name != "nt":  # NTFS does not expose POSIX executable bits.
            self.assertNotEqual((output / "bin/OpenBuds").stat().st_mode & 0o111, 0)
        self.assertFalse((output / "lib/runtime").exists())

    def test_macos_app_image_uses_contents_app(self):
        mac = self.root / "main-release/OpenBuds.app"
        (mac / "Contents").mkdir(parents=True)
        import shutil
        shutil.copytree(self.image / "app", mac / "Contents/app")
        output = stage_external(mac, "macos-arm64", self.launcher, self.root / "mac-external")
        self.assertTrue((output / "bin/OpenBuds").is_file())
        self.assertFalse((output / "Contents/runtime").exists())

    def test_package_archives_distinguish_both_variants(self):
        (self.root / "LICENSE").write_text("project license")
        (self.root / "THIRD_PARTY_NOTICES.md").write_text("notices")
        (self.root / "Cargo.lock").write_text("version = 4\npackage = []\n")
        (self.root / "metadata.json").write_text('{"packages": []}')
        (self.root / "metadata.tsv").write_text("")
        release = self.root / "target/release"
        release.mkdir(parents=True)
        for name in ["buds.exe", "openbuds-tray.exe"]:
            (release / name).write_bytes(b"native executable")
        for runtime, suffix in [("bundled", "bundled-runtime"), ("external-java", "external-java")]:
            argv = ["package_release.py", "--platform", "windows-x64", "--image", str(self.image),
                    "--runtime", runtime, "--launcher", str(self.launcher),
                    "--rust-metadata", str(self.root / "metadata.json"), "--jvm-metadata", str(self.root / "metadata.tsv")]
            with patch.object(package_release, "ROOT", self.root), patch("sys.argv", argv), \
                    patch.object(package_release.subprocess, "check_output", side_effect=["", "a" * 40, b"matching source"]), \
                    contextlib.redirect_stdout(io.StringIO()):
                package_release.main()
            name = f"OpenBuds-windows-x64-{suffix}"
            archive_path = self.root / f"target/packages/{name}.zip"
            with zipfile.ZipFile(archive_path) as archive:
                files = archive.namelist()
                self.assertEqual(f"{name}/OpenBuds/runtime/vm.bin" in files, runtime == "bundled")
                info = json.loads(archive.read(f"{name}/RUNTIME.json"))
                self.assertEqual(info["java_included"], runtime == "bundled")
                self.assertIn(f"{name}/OpenBudsTray.exe", files)
            self.assertTrue(archive_path.with_suffix(".zip.sha256").is_file())

    def test_dirty_source_is_rejected_before_staging(self):
        argv = ["package_release.py", "--platform", "windows-x64", "--image", str(self.image),
                "--rust-metadata", "unused", "--jvm-metadata", "unused"]
        with patch.object(package_release, "ROOT", self.root), patch("sys.argv", argv), \
                patch.object(package_release.subprocess, "check_output", return_value=" M source.rs\n"), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            package_release.main()
        self.assertFalse((self.root / "target/packages").exists())


if __name__ == "__main__":
    unittest.main()
