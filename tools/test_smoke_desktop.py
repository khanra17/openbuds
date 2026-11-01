# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import smoke_desktop


class SmokeDiagnosticsTest(unittest.TestCase):
    def test_timeout_retains_stdout_and_stderr(self):
        failure = subprocess.TimeoutExpired(["OpenBuds"], 60, output=b"render reached", stderr=b"AWT startup blocked")
        with patch.object(smoke_desktop.subprocess, "run", side_effect=failure):
            with self.assertRaisesRegex(RuntimeError, "AWT startup blocked") as error:
                smoke_desktop.check(Path("OpenBuds"), "--smoke-test")
        self.assertIn("render reached", str(error.exception))
        self.assertIn("timed out", str(error.exception))

    def test_failed_renderer_keeps_exit_code_and_output(self):
        failed = subprocess.CompletedProcess(["OpenBuds"], 2, "renderer output", "native dependency missing")
        with patch.object(smoke_desktop.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(RuntimeError, "native dependency missing") as error:
                smoke_desktop.check(Path("OpenBuds"), "--smoke-test")
        self.assertIn("failed (2)", str(error.exception))


if __name__ == "__main__":
    unittest.main()
