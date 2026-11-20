import copy
import importlib.util
import json
import re
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "deploy/worklog/verify_v30_data.py"


class V30ReleaseDataTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "V18 복원·V30 이관 보존 검사기가 필요합니다")
        spec = importlib.util.spec_from_file_location("verify_v30_data", SCRIPT)
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)

    def snapshots(self):
        tool = self.tool
        signature = "1" * 64
        before = dict(format=1, version=18, schema=signature, database=signature, roles=signature,
                      grants=[], legacy_policies=signature, codes=signature, expected_codes="2" * 64,
                      tables={name: [3, signature, signature] for name in tool.LEGACY}, checks={})
        before["history"] = [dict(installed_rank=n, version=str(n), script=f"V{n}__x.sql",
                                  checksum=n, type="SQL", description="x", success=True,
                                  installed_by="tna_migration", installed_on="old", execution_time=1)
                             for n in range(1, 19)]
        after = copy.deepcopy(before)
        after.update(version=30, schema="3" * 64, codes=before["expected_codes"],
                     checks={name: True for name in tool.CHECKS})
        after["history"].extend(dict(before["history"][0], installed_rank=n, version=str(n),
                                     script=f"V{n}__x.sql", checksum=n) for n in range(19, 31))
        after["grants"] = [list(row) for row in tool.ADDED_GRANTS | tool.NEW_GRANTS]
        for name in tool.NEW:
            count = 15 if name in ("tna.request_form", "tna.request_form_version") else 0
            after["tables"][name] = [count, signature, signature]
        return before, after

    def test_table_contract_matches_all_versioned_flyway_create_statements(self):
        migrations = ROOT / "src/main/resources/db/migration"
        expected = {18: set(), 30: set()}
        for sql in migrations.glob("V*__*.sql"):
            version = int(sql.name[1:].split("__")[0])
            names = re.findall(r"CREATE TABLE ((?:tna|platform)\.\w+)", sql.read_text("utf-8"), re.I)
            expected[18 if version <= 18 else 30].update(names)
        self.assertEqual(expected, {18: self.tool.LEGACY, 30: self.tool.NEW})

    def test_new_and_changed_app_grants_match_flyway(self):
        parsed = set()
        for sql in (ROOT / "src/main/resources/db/migration").glob("V*__*.sql"):
            if int(sql.name[1:].split("__")[0]) < 19:
                continue
            text = sql.read_text("utf-8")
            for permissions, names in re.findall(r"GRANT (.*?)\s+ON (tna\.[\w.,\s]+?) TO tna_app;", text, re.S):
                for table in names.replace(" ", "").split(","):
                    if table not in self.tool.LEGACY | self.tool.NEW:
                        continue
                    for perm, cols in re.findall(r"(INSERT|SELECT|UPDATE)\s*(?:\(([^)]*)\))?", permissions):
                        for col in cols.replace("\n", "").replace(" ", "").split(","):
                            parsed.add((table,col,"tna_migration","tna_app",perm,"false"))
        self.assertEqual(self.tool.ADDED_GRANTS | self.tool.NEW_GRANTS, parsed)

    def test_only_intended_upgrade_passes(self):
        before, after = self.snapshots()
        self.tool.compare("compare-upgrade", before, after)
        mutations = [
            lambda a: a["tables"]["tna.leave_ledger"].__setitem__(2, "4" * 64),
            lambda a: a["tables"]["tna.checkout_follow_up"].__setitem__(0, 1),
            lambda a: a["tables"]["tna.request_form"].__setitem__(0, 14),
            lambda a: a.update(codes=before["codes"]),
            lambda a: a["history"][10].update(checksum=0),
            lambda a: a["grants"].append(["tna.approval_request", "", "tna_migration", "tna_app", "DELETE", "false"]),
            lambda a: a["checks"].update(seeds=False),
            lambda a: a.update(roles="f" * 64),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                broken = copy.deepcopy(after)
                mutate(broken)
                with self.assertRaises(ValueError):
                    self.tool.compare("compare-upgrade", before, broken)

    def test_restore_is_exact_and_separate_migrations_have_independent_ids_and_timing(self):
        before, after = self.snapshots()
        self.tool.compare("compare-restore", before, copy.deepcopy(before))
        clone = copy.deepcopy(before)
        clone["tables"]["tna.employee"][1] = "4" * 64
        with self.assertRaises(ValueError):
            self.tool.compare("compare-restore", before, clone)
        clone = copy.deepcopy(after)
        clone["history"][-1].update(installed_on="later", execution_time=100)
        clone["tables"]["tna.request_form"][1] = "b" * 64
        self.tool.compare("compare-schema", after, clone)
        clone["schema"] = "c" * 64
        with self.assertRaises(ValueError):
            self.tool.compare("compare-schema", after, clone)

    def test_display_code_collision_uses_shared_counter_and_preserves_customer_rows(self):
        rows = [["a","1","ANNUAL_LEAVE","ANNUAL_LEAVE"], ["a","2","ATTENDANCE_CORRECTION","ATTENDANCE_CORRECTION"],
                ["a","3","a01",None], ["a","4","A02",None], ["a","5","A03",None],
                ["b","6","KEEP","ANNUAL_LEAVE"], ["b","7","ATTENDANCE_CORRECTION","ATTENDANCE_CORRECTION"]]
        self.assertEqual([r[2] for r in self.tool.compact_codes(rows)],
                         ["A04","A05","a01","A02","A03","KEEP","A02"])

    def test_reviewed_restore_cast_preserves_grants_literals_and_function_body(self):
        head = b'    ADD CONSTRAINT "status_check" CHECK '
        left = b"""ANY ((ARRAY['ACTIVE'::character varying])::"text"[])"""
        right = b"""ANY (ARRAY[('ACTIVE'::character varying)::"text"])"""
        self.assertEqual(self.tool.schema_fingerprint(head + left), self.tool.schema_fingerprint(head + right))
        self.assertNotEqual(self.tool.schema_fingerprint(head + left),
                            self.tool.schema_fingerprint(head + right.replace(b"ACTIVE", b"UNKNOWN")))
        body = b'CREATE FUNCTION "f"() RETURNS text\n    LANGUAGE sql AS $$\n'
        self.assertNotEqual(self.tool.schema_fingerprint(body + head + left + b'\n$$;'),
                            self.tool.schema_fingerprint(body + head + right + b'\n$$;'))

    def test_db_scope_rejected_before_subprocess_and_queries_are_read_only(self):
        with patch("subprocess.run") as run:
            for value in ("postgres", "worklog_demo;", "worklog_demo_old", "pricesys"):
                with self.assertRaises(ValueError):
                    self.tool.snapshot(value)
            run.assert_not_called()
        for name in ("worklog_demo", "worklog_demo_v30_20261119121212_123abcd_test",
                     "worklog_demo_v30_20261119121212_123abcd_final"):
            self.assertTrue(self.tool.DATABASE.fullmatch(name))
        query = self.tool.snapshot_sql()
        self.assertIn("REPEATABLE READ READ ONLY", query)
        self.assertNotRegex(query, r"(?im)^\s*(?:INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|GRANT|REVOKE)\s")

    def test_snapshot_json_and_sql_errors_do_not_expose_stored_values(self):
        before, _ = self.snapshots()
        secret = "raw-value-should-never-appear-in-evidence"
        capture = [["table",name,*sig] for name,sig in before["tables"].items()]
        capture.extend([["history",before["history"]], ["inventory",sorted(before["tables"])],
                        ["codes",[["tenant","id",secret,None]]], ["grants",[]], ["roles",[secret]], ["database",["owner"]]])
        out = "\n".join(json.dumps(row) for row in capture).encode()
        with patch("subprocess.run", side_effect=[subprocess.CompletedProcess([],0,stdout=out),
                                                 subprocess.CompletedProcess([],0,stdout=b"schema")]) as run:
            data = self.tool.snapshot("worklog_demo")
        self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(set(data["tables"]), self.tool.LEGACY)
        self.assertIn("VERBOSITY=sqlstate", run.call_args_list[0].args[0])
        self.assertIn("--schema-only", run.call_args_list[1].args[0])
        self.assertGreaterEqual(self.tool.v30_checks_sql().count("IS TRUE"), 3,
                                "NULL policy, function path or view option must fail the SQL aggregate")


if __name__ == "__main__":
    unittest.main()
