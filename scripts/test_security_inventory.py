"""Offline contract tests; synthetic files only, no Maven/Docker/network execution."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("security_inventory", Path(__file__).with_name("security-inventory.py"))
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)


def dependency(group="org.example", artifact="fixture", version="1.2.3", scope="compile", children=None):
    return {"groupId": group, "artifactId": artifact, "version": version, "scope": scope,
            "type": "jar", "classifier": "", "children": children or []}


class SecurityInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "project"
        self.repo = self.base / "repository"
        self.root.mkdir(); self.repo.mkdir()
        self.output = self.root / "target" / "scan"

    def jar(self, group="org.example", artifact="fixture", version="1.2.3", body=b"synthetic jar bytes"):
        path = self.repo.joinpath(*group.split("."), artifact, version, f"{artifact}-{version}.jar")
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(body)
        return path

    def plugin_row(self, path, group="org.example", artifact="fixture", version="1.2.3"):
        return f"   {group}:{artifact}:jar:{version}:{path}\n"

    def write_sources(self):
        properties = self.root / ".mvn/wrapper/maven-wrapper.properties"
        properties.parent.mkdir(parents=True)
        properties.write_text("wrapperVersion=3.3.4\ndistributionType=only-script\ndistributionUrl=https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip\ndistributionSha256Sum=" + "b"*64 + "\n")
        self.root.joinpath("mvnw").write_text("synthetic shell wrapper\n")
        self.root.joinpath("mvnw.cmd").write_text("synthetic batch wrapper\n")
        asset = self.root / "src/main/resources/static/vendor/htmx.min.js"
        asset.parent.mkdir(parents=True); asset.write_bytes(b"synthetic htmx")
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()
        self.root.joinpath("THIRD_PARTY_NOTICES.md").write_text(f"# Notices\n\n## htmx\n\n- 버전: 2.0.11.\n- 배포 파일 SHA256: `{digest}`.\n", encoding="utf-8")
        return asset, digest

    def csv_file(self, rows):
        path = self.root / "inventory.csv"
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["groupId", "artifactId", "version", "scope"])
            writer.writeheader(); writer.writerows(rows)
        return path

    def test_all_scopes_are_unique_and_root_is_not_a_dependency(self):
        leaf = dependency(scope="test")
        tree = dependency("kr.tna", "app", "0.0.1", "", [dependency(children=[leaf]), leaf,
                dependency("org.example", "provided-api", "2", "provided")])
        result = inventory.collect_dependencies(tree)
        self.assertEqual(2, len(result))
        by_purl = {item["purl"]: item for item in result}
        self.assertEqual(["compile", "test"], by_purl["pkg:maven/org.example/fixture@1.2.3"]["scopes"])
        self.assertEqual(["provided"], by_purl["pkg:maven/org.example/provided-api@2"]["scopes"])

    def test_unresolved_or_invalid_artifact_is_not_silently_scanned(self):
        for malformed in [dependency(version="${unresolved}"), dependency(group="../outside"), dependency(group=".."),
                dependency(artifact=".."), dependency(version=".."), dependency(scope="mystery")]:
            with self.subTest(malformed=malformed):
                with self.assertRaises(ValueError):
                    inventory.collect_dependencies(dependency(children=[malformed]))

    def test_csv_scope_and_coordinates_are_checked(self):
        actual = inventory.collect_dependencies(dependency(children=[dependency(scope="test")]))
        path = self.csv_file([{"groupId":"org.example", "artifactId":"fixture", "version":"1.2.3", "scope":"compile"}])
        mismatch = inventory.compare_csv(actual, path)
        self.assertFalse(mismatch["matched"]); self.assertEqual(1, len(mismatch["scopeMismatches"]))
        path = self.csv_file([{"groupId":"org.example", "artifactId":"fixture", "version":"0.9", "scope":"test"}])
        mismatch = inventory.compare_csv(actual, path)
        self.assertEqual(1, len(mismatch["missing"])); self.assertEqual(1, len(mismatch["extra"]))

    def test_plugin_duplicates_are_deduplicated_and_missing_body_is_visible(self):
        artifact = self.jar()
        missing = self.repo / "org/example/missing/9/missing-9.jar"
        text = "The following plugins have been resolved:\n" + self.plugin_row(artifact)*2 + self.plugin_row(missing, artifact="missing", version="9")
        result = inventory.collect_plugin_jars(text, self.repo)
        self.assertEqual(2, len(result))
        by_purl = {item["purl"]: item for item in result}
        existing = by_purl["pkg:maven/org.example/fixture@1.2.3"]
        self.assertEqual("available", existing["status"])
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), existing["sha256"])
        self.assertEqual("missing-artifact", by_purl["pkg:maven/org.example/missing@9"]["status"])

    def test_other_repository_and_mismatched_layout_are_refused(self):
        outside = self.base / "other.jar"; outside.write_bytes(b"outside")
        for text in [self.plugin_row(outside), self.plugin_row(self.jar(), artifact="wrong"), "org.example:fixture:jar:1.2.3:relative.jar\n"]:
            with self.subTest(text=text):
                with self.assertRaises(ValueError): inventory.collect_plugin_jars(text, self.repo)

    def test_actual_resolver_classifier_field_keeps_distinct_artifact(self):
        path = self.repo / "org/sonatype/sisu/sisu-guice/3.2.3/sisu-guice-3.2.3-no_aop.jar"
        path.parent.mkdir(parents=True); path.write_bytes(b"synthetic classified artifact")
        row = f"      org.sonatype.sisu:sisu-guice:jar:no_aop:3.2.3:{path}\n"
        found = inventory.collect_plugin_jars(row,self.repo)
        self.assertEqual(1,len(found))
        self.assertEqual("pkg:maven/org.sonatype.sisu/sisu-guice@3.2.3?classifier=no_aop",found[0]["purl"])
        self.assertEqual("available",found[0]["status"])

    def test_curated_output_preserves_layout_and_never_overwrites_changed_content(self):
        artifact = self.jar()
        plugins = inventory.collect_plugin_jars(self.plugin_row(artifact), self.repo)
        copied = inventory.curate_plugins(plugins, self.output, self.root)
        expected = self.output / "plugin-jars/org/example/fixture/1.2.3/fixture-1.2.3.jar"
        self.assertEqual(artifact.read_bytes(), expected.read_bytes()); self.assertEqual(1, len(copied))
        expected.write_bytes(b"user content")
        with self.assertRaises(FileExistsError): inventory.curate_plugins(plugins, self.output, self.root)
        self.assertEqual(b"user content", expected.read_bytes())
        with self.assertRaises(ValueError): inventory.curate_plugins(plugins, self.root / "src", self.root)

    def test_project_bodies_include_test_and_classified_jars_and_mark_missing(self):
        self.jar()
        classified = self.repo / "org/example/fixture/1.2.3/fixture-1.2.3-tests.jar"
        classified.write_bytes(b"synthetic test archive with nested metadata")
        test_jar = dependency(scope="test"); test_jar.update(type="test-jar", classifier="tests")
        missing = dependency(artifact="missing", version="9", scope="provided")
        dependencies = inventory.collect_dependencies(dependency(children=[dependency(), test_jar, missing]))
        artifacts = inventory.collect_dependency_artifacts(dependencies, self.repo)
        self.assertEqual(3, len(artifacts))
        self.assertEqual(2, sum(item["status"] == "available" for item in artifacts))
        self.assertEqual(1, sum(item["status"] == "missing-artifact" for item in artifacts))
        copied = inventory.curate_artifacts(artifacts, self.output, self.root, "dependency-jars")
        self.assertEqual(2, len(copied))
        self.assertEqual(classified.read_bytes(), (self.output / "dependency-jars/org/example/fixture/1.2.3/fixture-1.2.3-tests.jar").read_bytes())

    def test_project_artifact_paths_cannot_escape_repository_or_guess_custom_type(self):
        dependencies = inventory.collect_dependencies(dependency(children=[dependency()]))
        path = self.repo / "org/example/fixture/1.2.3"
        path.parent.mkdir(parents=True); path.mkdir()
        outside = self.base / "outside.jar"; outside.write_bytes(b"outside")
        # A repository symlink must not turn a resolved project coordinate into an unrelated artifact.
        try:
            (path / "fixture-1.2.3.jar").symlink_to(outside)
        except OSError:
            pass  # Windows can forbid symlinks; custom type refusal below is platform independent.
        else:
            with self.assertRaises(ValueError): inventory.collect_dependency_artifacts(dependencies, self.repo)
        custom = dependency(); custom["type"] = "unregistered-custom-handler"
        artifacts = inventory.collect_dependency_artifacts(inventory.collect_dependencies(dependency(children=[custom])), self.repo)
        self.assertEqual("unsupported-artifact-type", artifacts[0]["status"])
        self.assertIsNone(artifacts[0]["path"])

    def test_maven_execution_jars_are_curated_with_boot_and_lib_layout(self):
        self.write_sources()
        home = self.base / "apache-maven-3.9.16"
        for relative in ["boot/plexus-classworlds-2.9.0.jar", "lib/maven-core-3.9.16.jar", "lib/ext/fixture.jar"]:
            path = home / relative; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(relative.encode())
        supplements = inventory.supplemental_inventory(self.root, home)
        copied = inventory.curate_maven(supplements, self.output, self.root)
        self.assertEqual(3, len(copied))
        self.assertEqual(b"boot/plexus-classworlds-2.9.0.jar", (self.output / "maven-jars/boot/plexus-classworlds-2.9.0.jar").read_bytes())
        self.assertEqual(b"lib/ext/fixture.jar", (self.output / "maven-jars/lib/ext/fixture.jar").read_bytes())
        with self.assertRaises(ValueError): inventory.curate_maven(supplements, self.root / "docs", self.root)

    def test_supplement_versions_and_hashes_come_from_existing_files(self):
        asset, digest = self.write_sources()
        result = {item["kind"]: item for item in inventory.supplemental_inventory(self.root)}
        self.assertEqual("3.9.16", result["maven"]["version"])
        self.assertEqual("artifact-body-not-provided", result["maven"]["status"])
        self.assertEqual("3.3.4", result["wrapper"]["version"])
        self.assertEqual("script-evidence", result["wrapper"]["status"])
        self.assertEqual("pkg:npm/htmx.org@2.0.11", result["htmx"]["purl"])
        self.assertEqual(digest, result["htmx"]["sha256"])
        asset.write_bytes(b"changed")
        with self.assertRaises(ValueError): inventory.supplemental_inventory(self.root)

    def test_inventory_writes_only_target_and_makes_coverage_explicit(self):
        self.write_sources()
        tree = self.root / "tree.json"; tree.write_text(json.dumps(dependency(children=[dependency(scope="test")])))
        plugins = self.root / "plugins.txt"; plugins.write_text("The following plugins have been resolved:\n" + self.plugin_row(self.jar()))
        csv_path = self.root / "docs/development/dependency-licenses.csv"; csv_path.parent.mkdir(parents=True)
        csv_path.write_text(self.csv_file([{"groupId":"org.example", "artifactId":"fixture", "version":"1.2.3", "scope":"test"}]).read_text())
        result = inventory.build_inventory(self.root, tree, plugins, self.repo, self.output)
        self.assertTrue(result["coverage"]["matched"])
        self.assertEqual(1, len(result["dependencies"])); self.assertEqual(1, len(result["plugins"]))
        self.assertEqual(1, len(result["curatedDependencies"]))
        self.assertEqual([], result["curatedMaven"])
        self.assertTrue(self.output.joinpath("dependency-jars/org/example/fixture/1.2.3/fixture-1.2.3.jar").is_file())
        self.assertTrue(self.output.joinpath("inventory.json").is_file())
        self.assertIn("pkg:maven/org.example/fixture@1.2.3", self.output.joinpath("dependencies.purls.txt").read_text())
        self.assertIn("pkg:npm/htmx.org@2.0.11", self.output.joinpath("supplemental.purls.txt").read_text())
        with self.assertRaises(ValueError): inventory.build_inventory(self.root, tree, plugins, self.repo, self.root / "docs")


if __name__ == "__main__":
    unittest.main()
