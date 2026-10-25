"""Truststore CLI safety checks using only synthetic certificates under target/."""
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PASSWORD = "changeit"  # Standard cacerts password; all test keys are synthetic.
TARGETS = [
    "emSign ECC Root CA - G3",
    "AAA Certificate Services",
    "emSign Root CA - G1",
    "emSign Root CA - G2",
]
SAFE = ["emSign Root CA - C1", "emSign ECC Root CA - C3", "prefix emSign Root CA - G1", "emSign Root CA - G1 suffix"]


def command(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
    if result.returncode:
        raise AssertionError(f"Synthetic fixture command failed ({result.returncode}): {result.stderr}")
    return result


class TruststoreRemediationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ["JAVA_HOME"])
        suffix = ".exe" if os.name == "nt" else ""
        cls.java, cls.javac, cls.keytool = [home / "bin" / (name + suffix) for name in ("java", "javac", "keytool")]
        evidence = ROOT / "target" / "phase6-residual"
        evidence.mkdir(parents=True, exist_ok=True)
        cls.directory = Path(tempfile.mkdtemp(prefix="truststore-tests-", dir=evidence))
        cls.classes = cls.directory / "classes"
        cls.classes.mkdir()
        command([cls.javac, "-d", cls.classes, ROOT / "scripts" / "TruststoreRemediation.java"])
        cls.private = cls.directory / "synthetic-private.jks"
        cls.certificates = []
        for index, cn in enumerate(TARGETS + SAFE):
            alias = f"opaque-{index}"
            subject = f"CN={cn},O=Synthetic tests"
            if index == 3:
                subject = f"CN={cn}+OU=Multi value,O=Synthetic tests"
            command([cls.keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", subject,
                     "-validity", "2", "-ext", "bc=ca:true", "-keystore", cls.private, "-storetype", "JKS",
                     "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"])
            certificate = cls.directory / f"synthetic-{index}.der"
            command([cls.keytool, "-exportcert", "-alias", alias, "-keystore", cls.private, "-storepass", PASSWORD, "-file", certificate])
            cls.certificates.append(certificate.read_bytes())
        cls.private_pkcs12 = cls.directory / "synthetic-private.p12"
        command([cls.keytool, "-importkeystore", "-srckeystore", cls.private, "-srcstoretype", "JKS", "-srcstorepass", PASSWORD,
                 "-destkeystore", cls.private_pkcs12, "-deststoretype", "PKCS12", "-deststorepass", PASSWORD, "-noprompt"])
        cls.stores = {}
        for kind in ("JKS", "PKCS12"):
            store = cls.directory / ("trusted-" + kind.lower())
            for index in range(len(cls.certificates)):
                command([cls.keytool, "-importcert", "-alias", f"opaque-{index}", "-file", cls.directory / f"synthetic-{index}.der",
                         "-keystore", store, "-storetype", kind, "-storepass", PASSWORD, "-noprompt"])
            cls.stores[kind] = store

    def copy_store(self, kind, label):
        path = self.directory / f"{self._testMethodName}-{label}-{kind}"
        shutil.copy2(self.stores[kind], path)
        return path

    def invoke(self, *arguments):
        return subprocess.run([str(self.java), "-cp", str(self.classes), "TruststoreRemediation", *map(str, arguments)],
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)

    def snapshot(self, path):
        info = path.stat()
        return hashlib.sha256(path.read_bytes()).hexdigest(), info.st_mtime_ns, stat.S_IMODE(info.st_mode)

    def test_check_is_read_only_and_matches_only_exact_subject_cn(self):
        for kind in self.stores:
            with self.subTest(kind=kind):
                path = self.copy_store(kind, "check")
                before = self.snapshot(path)
                result = self.invoke("--check", path)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("targets=4", result.stdout)
                self.assertEqual(4, result.stdout.count("sha256="))
                self.assertNotIn("opaque-", result.stdout)
                self.assertEqual(before, self.snapshot(path))

    def test_apply_preserves_other_certificates_permissions_and_original_backup(self):
        for kind in self.stores:
            with self.subTest(kind=kind):
                path = self.copy_store(kind, "apply")
                backup = path.with_suffix(".backup")
                before = path.read_bytes()
                mode = stat.S_IMODE(path.stat().st_mode)
                result = self.invoke("--apply", path, backup)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("targets=4", result.stdout)
                self.assertEqual(before, backup.read_bytes())
                self.assertEqual(mode, stat.S_IMODE(path.stat().st_mode))
                self.assertEqual(0, self.invoke("--check", path).returncode)
                for index in range(len(self.certificates)):
                    exported = subprocess.run([str(self.keytool), "-exportcert", "-alias", f"opaque-{index}", "-keystore", str(path),
                                               "-storepass", PASSWORD], capture_output=True, timeout=30)
                    if index < 4:
                        self.assertNotEqual(0, exported.returncode)
                    else:
                        self.assertEqual(0, exported.returncode, exported.stderr)
                        self.assertEqual(self.certificates[index], exported.stdout)

    def test_existing_backup_and_identical_path_are_rejected_before_writing(self):
        for kind in self.stores:
            with self.subTest(kind=kind):
                path = self.copy_store(kind, "reject")
                before = self.snapshot(path)
                backup = path.with_suffix(".existing")
                backup.write_bytes(b"existing backup must survive")
                self.assertEqual(2, self.invoke("--apply", path, backup).returncode)
                self.assertEqual(b"existing backup must survive", backup.read_bytes())
                self.assertEqual(2, self.invoke("--apply", path, path.parent / "." / path.name).returncode)
                self.assertEqual(before, self.snapshot(path))

    def test_private_key_store_is_rejected_before_backup_or_change(self):
        for source in (self.private, self.private_pkcs12):
            with self.subTest(kind=source.suffix):
                path = self.directory / ("private-rejection" + source.suffix)
                shutil.copy2(source, path)
                before = self.snapshot(path)
                backup = path.with_suffix(path.suffix + ".backup")
                self.assertEqual(2, self.invoke("--apply", path, backup).returncode)
                self.assertEqual(before, self.snapshot(path))
                self.assertFalse(backup.exists())

    def test_clean_apply_is_idempotent_without_rewrite_or_backup_overwrite(self):
        for kind in self.stores:
            with self.subTest(kind=kind):
                path = self.copy_store(kind, "idempotent")
                backup = path.with_suffix(".original")
                self.assertEqual(0, self.invoke("--apply", path, backup).returncode)
                self.assertTrue(backup.is_file(), "Apply must preserve an original backup before writing")
                before, original_backup = self.snapshot(path), backup.read_bytes()
                again = self.invoke("--apply", path, backup)
                self.assertEqual(0, again.returncode, again.stderr)
                self.assertIn("targets=0", again.stdout)
                unused = path.with_suffix(".unused")
                self.assertEqual(0, self.invoke("--apply", path, unused).returncode)
                self.assertEqual(before, self.snapshot(path))
                self.assertEqual(original_backup, backup.read_bytes())
                self.assertFalse(unused.exists())

    def test_invalid_store_is_rejected_without_creating_backup(self):
        path = self.directory / "invalid-store"
        path.write_bytes(b"not a keystore")
        before = self.snapshot(path)
        backup = path.with_suffix(".backup")
        self.assertEqual(2, self.invoke("--apply", path, backup).returncode)
        self.assertFalse(backup.exists())
        self.assertEqual(before, self.snapshot(path))


if __name__ == "__main__":
    unittest.main()
