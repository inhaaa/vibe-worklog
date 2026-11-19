import hashlib
import io
import unittest
import warnings
import zipfile

import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "deploy/worklog/verify_v30_artifact.py"
SPEC = importlib.util.spec_from_file_location("worklog_v30_artifact", SCRIPT)
artifact = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(artifact)


def jar(files, year=2026):
    binary = io.BytesIO()
    with zipfile.ZipFile(binary, "w") as archive:
        for name, content in files:
            archive.writestr(zipfile.ZipInfo(name, date_time=(year, 1, 1, 0, 0, 0)), content)
    binary.seek(0)
    return binary


class ArtifactComparisonTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(artifact, "엔트리 대조 도구가 필요합니다.")

    def test_directory_order_and_zip_times_leave_file_content_hashes_unchanged(self):
        first = jar([("dir/", b""), ("dir/A", b"first"), ("B", b"second")])
        second = jar([("B", b"second"), ("dir/A", b"first")], year=2025)
        self.assertNotEqual(first.getvalue(), second.getvalue())
        self.assertEqual(artifact.jar_entries(first), {
            "dir/A": hashlib.sha256(b"first").hexdigest(),
            "B": hashlib.sha256(b"second").hexdigest(),
        })
        root = artifact.entry_digest(artifact.jar_entries(first))
        artifact.verify("runtime", second, root)

    def test_duplicate_file_names_are_refused_even_when_the_bytes_match(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            duplicate = jar([("A", b"same"), ("A", b"same")])
        with self.assertRaisesRegex(ValueError, "duplicate.*A"):
            artifact.jar_entries(duplicate)

    def test_changed_missing_and_added_files_fail_and_identify_the_image_and_names(self):
        baseline = artifact.jar_entries(jar([("keep", b"0"), ("gone", b"1"), ("diff", b"2")]))
        build = jar([("keep", b"0"), ("new", b"1"), ("diff", b"3")])
        with self.assertRaises(ValueError) as failed:
            artifact.verify("migrate", build, artifact.entry_digest(baseline), baseline)
        for message in ("migrate", "diff", "gone", "new"):
            self.assertIn(message, str(failed.exception))

    def test_linux_text_files_match_the_exact_certified_windows_bytes(self):
        name = "BOOT-INF/classes/static/app.css"
        certified = artifact.jar_entries(jar([(name, b"a\r\nb\r\n"), ("code.class", b"code\n")]))
        actual = jar([(name, b"a\nb\n"), ("code.class", b"code\n")])
        artifact.verify("linux", actual, artifact.entry_digest(certified), certified, linux_lf=True)
        for data in ([(name, b"a\nchanged\n"), ("code.class", b"code\n")],
                     [(name, b"a\nb\n"), ("code.class", b"code\r\n")]):
            with self.assertRaises(ValueError):
                artifact.verify("linux", jar(data), artifact.entry_digest(certified), certified, linux_lf=True)

    def test_digest_alone_rejects_a_changed_image_without_a_manifest_file(self):
        with self.assertRaisesRegex(ValueError, "runtime"):
            artifact.verify("runtime", jar([("A", b"wrong")]), "a" * 64)


if __name__ == "__main__":
    unittest.main()
