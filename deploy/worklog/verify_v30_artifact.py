"""Compare both Docker targets' application.jar with the certified WorkLog V30 build."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile

REVISION = "368f6a029ca96b6cb0e8a1796ba1c043a2a697f5"
CERTIFIED_JAR = "41a9c2e9bc2d93c0b749a6089609dab43de12fb325c9bed5ac97e277323f3088"
CERTIFIED_ENTRIES = "d12d90ac3939423585ce621cbd34ab0138a97d5be3ae90f8d6d6945385b5dda9"


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def jar_entries(path):
    with ZipFile(path) as archive:
        result = {}
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            if entry.filename in result:
                raise ValueError("duplicate JAR entry: " + entry.filename)
            result[entry.filename] = hashlib.sha256(archive.read(entry)).hexdigest()
        return result


def entry_digest(entries):
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("ascii")
    return hashlib.sha256(encoded).hexdigest()


def verify(label, path, expected_digest, expected_entries=None):
    entries = jar_entries(path)
    actual = entry_digest(entries)
    if actual != expected_digest:
        message = f"{label} entry digest differs: {actual}, expected {expected_digest}"
        if expected_entries is not None:
            differing = sorted(name for name in entries.keys() | expected_entries.keys()
                               if entries.get(name) != expected_entries.get(name))
            message += f"; {len(differing)} missing, changed or added: " + ", ".join(differing[:40])
        raise ValueError(message)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    record = modes.add_parser("manifest", help="write the certified per-file hashes")
    record.add_argument("certified_jar", type=Path)
    record.add_argument("output", type=Path)
    check = modes.add_parser("check", help="check two JARs extracted from the runtime and migrate images")
    check.add_argument("runtime", type=Path)
    check.add_argument("migrate", type=Path)
    check.add_argument("--manifest", type=Path, help="include differing file names in mismatch diagnostics")
    args = parser.parse_args()
    result = {"revision": REVISION, "entries_sha256": CERTIFIED_ENTRIES}
    if args.mode == "manifest":
        if file_digest(args.certified_jar) != CERTIFIED_JAR:
            raise ValueError("wrong certified reference JAR")
        entries = verify("certified", args.certified_jar, CERTIFIED_ENTRIES)
        result.update(entries=len(entries), certified_jar_sha256=CERTIFIED_JAR)
        payload = json.dumps(dict(result, jar_entries=entries), sort_keys=True, separators=(",", ":")) + "\n"
        args.output.write_bytes(payload.encode("ascii"))
    else:
        reference = None
        if args.manifest:
            reference = json.loads(args.manifest.read_bytes())["jar_entries"]
            if entry_digest(reference) != CERTIFIED_ENTRIES:
                raise ValueError("wrong reference entry manifest")
        for role, path in (("runtime", args.runtime), ("migrate", args.migrate)):
            entries = verify(role, path, CERTIFIED_ENTRIES, reference)
            result[role + "_sha256"] = file_digest(path)
            result["entries"] = len(entries)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (BadZipFile, KeyError, OSError, ValueError) as error:
        sys.exit(str(error))
