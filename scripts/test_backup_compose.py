"""仅使用临时目录和命令替身验证维护停机控制，不连接 Docker 或读取真实数据库。"""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location("backup", Path(__file__).with_name("backup-compose.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupMaintenanceTest(unittest.TestCase):
    def execute(self, leave=False, fail=False):
        calls = []

        def run(*args, **kwargs):
            calls.append(args)
            if args[0] == "exec" and fail:
                raise subprocess.CalledProcessError(1, ["isolated-dump-test"])
            return Mock(stdout="web\nadmin-server\nmysql\n")

        with tempfile.TemporaryDirectory() as folder, patch.object(backup, "ROOT", Path(folder)), patch.object(backup, "run", run), patch.object(sys, "argv", ["backup"] + (["--leave-stopped"] if leave else [])), patch("builtins.print"):
            if fail:
                with self.assertRaises(subprocess.CalledProcessError):
                    backup.main()
                self.assertFalse(list(Path(folder).rglob("manifest.json")))
            else:
                backup.main()
                self.assertEqual(1, len(list(Path(folder).rglob("manifest.json"))))
        return calls

    def test_default_resumes_only_original_writers(self):
        calls = self.execute()
        self.assertIn(("stop", "--timeout", "60", "web", "admin-server"), calls)
        self.assertEqual(("start", "web", "admin-server"), calls[-1])

    def test_successful_maintenance_backup_stays_stopped(self):
        self.assertFalse(any(c[0] == "start" for c in self.execute(leave=True)))

    def test_failed_maintenance_backup_always_resumes(self):
        self.assertEqual(("start", "web", "admin-server"), self.execute(leave=True, fail=True)[-1])


if __name__ == "__main__":
    unittest.main()
