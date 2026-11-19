"""Check the version-specific WorkLog inventory and backup boundaries without contacting a host."""
import importlib.util
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "deploy/worklog"
MIGRATIONS = ROOT / "src/main/resources/db/migration"


def load(name):
    spec = importlib.util.spec_from_file_location(name, DEPLOY / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V31ReleaseTest(unittest.TestCase):
    def test_schema_inventory_and_checked_migration(self):
        data = load("verify_v31_data")
        originals = set()
        for source in MIGRATIONS.glob("V*__*.sql"):
            if int(source.name[1:].split("__")[0]) <= 30:
                originals.update(re.findall(r"CREATE TABLE ((?:tna|platform)\.\w+)", source.read_text("utf-8"), re.I))
        self.assertEqual(data.ORIGINAL_TABLES, originals)
        source = MIGRATIONS / "V31__attendance_month_close_export.sql"
        with patch.object(data, "SQL_FILE", source):
            self.assertTrue(data.expected())
        self.assertEqual([data.EXPORT], re.findall(r"CREATE TABLE ((?:tna|platform)\.\w+)", source.read_text("utf-8"), re.I))

    def test_restore_never_targets_live_or_previous_rehearsal_database(self):
        release = load("release_v31")
        for name in ("postgres", "worklog_demo", "worklog_demo_v30_20261119000000_1d351fb_test"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.validate_restore_target(name)
        run = "run-20261119000000-1d351fb"
        self.assertNotEqual(release.clone_name(run, "test"), release.clone_name(run, "final"))

    def test_failed_cutover_recovers_only_before_schema_change(self):
        release = load("release_v31")
        def fail():
            raise RuntimeError("rehearsal failed")
        for migrated in (True, False):
            calls = []
            with self.subTest(migrated=migrated), self.assertRaises(RuntimeError):
                release.cutover(lambda: calls.append("stop"), fail, lambda: migrated, lambda: calls.append("recover"))
            self.assertEqual(calls, ["stop"] if migrated else ["stop", "recover"])


if __name__ == "__main__":
    unittest.main()
