"""Create local scan inputs from resolved Maven inventories; never run a scanner or Maven."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote


SCOPES = {"compile", "runtime", "test", "provided", "system", "import"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def coordinates(group, artifact, version):
    if not all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]+", value) for value in (group, artifact)):
        raise ValueError("Invalid Maven group or artifact identity")
    if any(not part for part in group.split(".")) or artifact in {".",".."}:
        raise ValueError("Maven coordinates must not contain directory traversal segments")
    if not isinstance(version, str) or not version or version in {".",".."} or any(char in version for char in "${}[](),/\\:\r\n \t"):
        raise ValueError("Maven version must be resolved")
    return f"pkg:maven/{quote(group, safe='._-')}/{quote(artifact, safe='._-')}@{quote(version, safe='._~-')}"


def target_output(root, output):
    root = Path(root).resolve(strict=True)
    target = (root / "target").resolve()
    output = Path(output).resolve()
    if not target.is_relative_to(root) or not output.is_relative_to(target):
        raise ValueError("Output must stay inside this workspace's target directory")
    return output


def write_new_or_same(path, data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != data:
            raise FileExistsError(f"Existing content differs; use a new output directory: {path}")
        return
    with path.open("xb") as stream:
        stream.write(data)


def collect_dependencies(tree):
    found = {}

    def visit(node):
        group, artifact, version = (node.get(key) for key in ("groupId", "artifactId", "version"))
        purl = coordinates(group, artifact, version)
        scope = node.get("scope")
        if scope not in SCOPES:
            raise ValueError("Unknown resolved Maven scope")
        kind, classifier = node.get("type", "jar"), node.get("classifier", "")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", kind) or not re.fullmatch(r"[A-Za-z0-9_.-]*", classifier):
            raise ValueError("Invalid artifact type or classifier")
        qualifiers = []
        if classifier:
            qualifiers.append("classifier=" + quote(classifier, safe="._-"))
        if kind != "jar":
            qualifiers.append("type=" + quote(kind, safe="._-"))
        if qualifiers:
            purl += "?" + "&".join(qualifiers)
        item = found.setdefault(purl, {"groupId":group, "artifactId":artifact, "version":version,
                "type":kind, "classifier":classifier, "purl":purl, "scopes":set()})
        item["scopes"].add(scope)
        for child in node.get("children", []):
            visit(child)

    if not isinstance(tree, dict) or not tree.get("children"):
        raise ValueError("Dependency tree must contain resolved dependencies")
    for child in tree["children"]:
        visit(child)
    return [{**found[key], "scopes":sorted(found[key]["scopes"])} for key in sorted(found)]


def compare_csv(dependencies, csv_path):
    actual = {(item["groupId"],item["artifactId"],item["version"]):set(item["scopes"]) for item in dependencies}
    expected = {}
    with Path(csv_path).open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            key = (row["groupId"],row["artifactId"],row["version"])
            coordinates(*key)
            if key in expected:
                raise ValueError("Duplicate coordinate in license inventory")
            expected[key] = row["scope"]
    if not expected:
        raise ValueError("License inventory is empty")
    missing = [":".join(key) for key in sorted(expected.keys()-actual.keys())]
    extra = [":".join(key) for key in sorted(actual.keys()-expected.keys())]
    mismatches = [{"coordinate":":".join(key),"expected":expected[key],"actual":sorted(actual[key])}
            for key in sorted(actual.keys() & expected.keys()) if expected[key] not in actual[key]]
    return {"matched":not(missing or extra or mismatches), "actualCount":len(actual), "expectedCount":len(expected),
            "missing":missing, "extra":extra, "scopeMismatches":mismatches}


def collect_dependency_artifacts(dependencies, repository):
    repository = Path(repository).resolve(strict=True)
    # These standard Maven handlers produce JARs; custom handlers must not be guessed.
    jar_types = {"jar":"", "maven-plugin":"", "ejb":"", "ejb-client":"client",
            "test-jar":"tests", "java-source":"sources", "javadoc":"javadoc"}
    artifacts = []
    for item in dependencies:
        if item["type"] not in jar_types:
            artifacts.append({**item,"path":None,"relativePath":None,"sha256":None,
                    "status":"unsupported-artifact-type"})
            continue
        classifier = item["classifier"] or jar_types[item["type"]]
        filename = f"{item['artifactId']}-{item['version']}" + ("-"+classifier if classifier else "") + ".jar"
        relative = Path(*item["groupId"].split("."),item["artifactId"],item["version"],filename)
        source = (repository / relative).resolve()
        if not source.is_relative_to(repository):
            raise ValueError("Project artifact escapes the selected Maven repository")
        artifacts.append({**item,"path":str(source),"relativePath":relative.as_posix(),
                "status":"available" if source.is_file() else "missing-artifact",
                "sha256":sha256(source) if source.is_file() else None})
    return artifacts


def collect_plugin_jars(text, repository):
    repository = Path(repository).resolve(strict=True)
    found = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line == "The following plugins have been resolved:":
            continue
        parts = line.split(":", 4)
        if len(parts) != 5 or parts[2] != "jar":
            raise ValueError("Unexpected plugin inventory format; use absolute artifact filenames")
        group, artifact, _, version, raw_path = parts
        classifier = ""
        if not Path(raw_path).is_absolute():
            classified = line.split(":",5)
            if len(classified) != 6 or not re.fullmatch(r"[A-Za-z0-9_.-]+",classified[3]):
                raise ValueError("Plugin artifact path must be absolute")
            group,artifact,_,classifier,version,raw_path = classified
        purl = coordinates(group, artifact, version)
        if classifier:
            purl += "?classifier=" + quote(classifier,safe="._-")
        source = Path(raw_path)
        if not source.is_absolute():
            raise ValueError("Plugin artifact path must be absolute")
        source = source.resolve()
        filename = f"{artifact}-{version}" + ("-"+classifier if classifier else "") + ".jar"
        relative = Path(*group.split("."), artifact, version, filename)
        if not source.is_relative_to(repository) or source != (repository / relative).resolve():
            raise ValueError("Plugin artifact does not match the selected repository and Maven layout")
        item = {"purl":purl,"groupId":group,"artifactId":artifact,"version":version,"classifier":classifier,
                "path":str(source),"relativePath":relative.as_posix(),"status":"available" if source.is_file() else "missing-artifact",
                "sha256":sha256(source) if source.is_file() else None}
        if purl in found and found[purl] != item:
            raise ValueError("One plugin coordinate resolves to conflicting artifact content")
        found[purl] = item
    if not found:
        raise ValueError("Plugin inventory contains no resolved JARs")
    return [found[key] for key in sorted(found)]


def curate_artifacts(artifacts, output, root, directory):
    output = target_output(root, output)
    if not re.fullmatch(r"[A-Za-z0-9_-]+", directory):
        raise ValueError("Invalid curated artifact directory")
    copied = []
    for item in artifacts:
        if item["status"] != "available":
            continue
        source = Path(item["path"])
        relative = Path(item["relativePath"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Invalid curated artifact path")
        destination = (output / directory / relative).resolve()
        if not destination.is_relative_to(output):
            raise ValueError("Curated path escapes output")
        if sha256(source) != item["sha256"]:
            raise ValueError("Artifact changed after inventory collection")
        write_new_or_same(destination, source.read_bytes())
        copied.append({"purl":item["purl"],"path":str(destination),"sha256":sha256(destination)})
    return copied


def curate_plugins(plugins, output, root):
    return curate_artifacts(plugins,output,root,"plugin-jars")


def curate_maven(supplements, output, root):
    artifacts = [{**artifact,"purl":item["purl"],"status":"available"}
            for item in supplements if item["kind"] == "maven" for artifact in item["files"]]
    return curate_artifacts(artifacts,output,root,"maven-jars")


def supplemental_inventory(root, maven_home=None):
    root = Path(root).resolve(strict=True)
    properties_path = root / ".mvn/wrapper/maven-wrapper.properties"
    properties = dict(line.split("=",1) for line in properties_path.read_text(encoding="utf-8-sig").splitlines()
            if line and not line.startswith("#") and "=" in line)
    match = re.search(r"/apache-maven-([^/]+)-bin\.zip$",properties["distributionUrl"])
    if not match or properties.get("distributionType") != "only-script" or not re.fullmatch(r"[a-fA-F0-9]{64}",properties.get("distributionSha256Sum","")):
        raise ValueError("Expected a pinned only-script Maven distribution")
    maven_version, wrapper_version = match.group(1), properties["wrapperVersion"]
    wrapper = {"kind":"wrapper","version":wrapper_version,"purl":coordinates("org.apache.maven.wrapper","maven-wrapper-distribution",wrapper_version),
            "status":"script-evidence","distributionType":"only-script",
            "files":{name:sha256(root/name) for name in ["mvnw","mvnw.cmd",".mvn/wrapper/maven-wrapper.properties"]}}
    maven = {"kind":"maven","version":maven_version,"purl":coordinates("org.apache.maven","apache-maven",maven_version),
            "distributionSha256":properties["distributionSha256Sum"],"status":"artifact-body-not-provided","files":[]}
    if maven_home is not None:
        home = Path(maven_home).resolve(strict=True)
        if not (home / "lib" / f"maven-core-{maven_version}.jar").is_file():
            raise ValueError("Maven home does not contain the pinned Maven core")
        for directory in (home/"boot",home/"lib"):
            for artifact in sorted(directory.rglob("*.jar")):
                source = artifact.resolve()
                if not source.is_relative_to(home):
                    raise ValueError("Maven distribution artifact escapes its home")
                maven["files"].append({"path":str(source),"relativePath":source.relative_to(home).as_posix(),"sha256":sha256(source)})
        maven["status"] = "artifact-evidence"
    notices = root.joinpath("THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8-sig")
    section = re.search(r"^## htmx\s*\n(.*?)(?=^## |\Z)",notices,re.M|re.S)
    version = re.search(r"버전:\s*([0-9]+\.[0-9]+\.[0-9]+)",section.group(1) if section else "")
    digest = re.search(r"SHA256:\s*`([a-f0-9]{64})`",section.group(1) if section else "")
    if not version or not digest:
        raise ValueError("Missing pinned htmx identity and source digest")
    asset = root / "src/main/resources/static/vendor/htmx.min.js"
    actual = sha256(asset)
    if actual != digest.group(1):
        raise ValueError("Vendored htmx differs from the documented pinned bytes")
    htmx = {"kind":"htmx","version":version.group(1),"purl":"pkg:npm/htmx.org@"+version.group(1),
            "path":str(asset),"sha256":actual,"status":"artifact-evidence"}
    return [maven,wrapper,htmx]


def build_inventory(root, tree_path, plugins_path, repository, output, maven_home=None):
    root = Path(root).resolve(strict=True)
    output = target_output(root, output)
    dependencies = collect_dependency_artifacts(collect_dependencies(json.loads(Path(tree_path).read_text(encoding="utf-8-sig"))),repository)
    plugins = collect_plugin_jars(Path(plugins_path).read_text(encoding="utf-8-sig"),repository)
    supplements = supplemental_inventory(root,maven_home)
    coverage = compare_csv(dependencies,root/"docs/development/dependency-licenses.csv")
    curated_dependencies = curate_artifacts(dependencies,output,root,"dependency-jars")
    curated = curate_plugins(plugins,output,root)
    curated_maven = curate_maven(supplements,output,root)
    report = {"dependencies":dependencies,"coverage":coverage,"plugins":plugins,"curatedPlugins":curated,"supplements":supplements,
            "curatedDependencies":curated_dependencies,"curatedMaven":curated_maven,
            "inputs":{"treeSha256":sha256(Path(tree_path)),"pluginsSha256":sha256(Path(plugins_path))},
            "coverageLimits":["PURL scans compare identities; they do not prove artifact contents safe",
                "Resolved plugins may include declared lifecycle plugins not executed by this build",
                "Missing or unsupported artifact bodies remain visible; only available JARs are copied",
                "dependency-jars, plugin-jars and maven-jars require archive-content scanning in addition to PURL matching",
                "Wrapper only-script evidence is not Java archive scanning"]}
    for name,items in [("dependencies",dependencies),("plugins",plugins),("supplemental",supplements)]:
        write_new_or_same(output/(name+".purls.txt"),"\n".join(sorted({item["purl"] for item in items}))+"\n")
    write_new_or_same(output/"inventory.json",json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--tree",type=Path,required=True)
    parser.add_argument("--plugins",type=Path,required=True)
    parser.add_argument("--repository",type=Path,default=Path.home()/".m2/repository")
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--maven-home",type=Path)
    args = parser.parse_args()
    try:
        report = build_inventory(args.root,args.tree,args.plugins,args.repository,args.output,args.maven_home)
    except (OSError,ValueError,KeyError) as error:
        parser.exit(2,f"Inventory rejected: {error}\n")
    missing = sum(item["status"] != "available" for item in report["dependencies"]+report["plugins"])
    missing += sum(item["status"] == "artifact-body-not-provided" for item in report["supplements"])
    print(f"Inventory: {len(report['dependencies'])} project dependencies; {len(report['plugins'])} unique plugin artifacts; {len(report['curatedMaven'])} Maven JARs; {missing} missing or unsupported bodies; CSV matched={report['coverage']['matched']}")
    if not report["coverage"]["matched"] or missing:
        parser.exit(1,"Coverage incomplete; inspect inventory.json before claiming full scanning\n")


if __name__ == "__main__":
    main()
