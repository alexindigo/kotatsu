"""The seeded sync verifier must run the full suite despite caller filters."""
import importlib.util
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("sync_isolation", root / "kotatsu-sync/verify-test-isolation.py")
isolation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(isolation)


class SyncTestIsolationTest(unittest.TestCase):
    def test_whole_suite_removes_class_and_shard_without_changing_parent_environment(self):
        filters = {"CLASS": "com.example.SelectedTest", "SHARD": "0/2", "ANDROID_SERIAL": "emulator-5554"}
        with patch.dict(os.environ, filters, clear=True), patch.object(isolation.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1)
            self.assertEqual(1, isolation.run_whole_suite(root, root / "results").returncode)
            child = run.call_args.kwargs.get("env", os.environ)
            self.assertNotIn("CLASS", child)
            self.assertNotIn("SHARD", child)
            self.assertEqual("emulator-5554", child["ANDROID_SERIAL"])
            self.assertEqual(filters, {key: os.environ[key] for key in filters})
            self.assertEqual(["bash", str(root / "tools/ci/run-connected.sh"), "sync", str(root / "results/suite")],
                             run.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
