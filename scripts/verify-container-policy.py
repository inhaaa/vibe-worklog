"""Check a fresh, sanitized Docker inspect snapshot; never connect to Docker or a DB."""
import argparse
from copy import deepcopy
import json
from pathlib import Path


def verify(items, project):
    services = {}
    for item in items:
        labels = item["Config"]["Labels"]
        assert labels.get("com.docker.compose.project") == project, "Unexpected Compose project"
        name = labels.get("com.docker.compose.service")
        assert name in {"app", "migrate", "db"} and name not in services, "Unexpected/duplicate service"
        services[name] = item
    assert set(services) == {"app", "migrate", "db"}, "Exactly three service snapshots are required"
    for name, item in services.items():
        host, state = item["HostConfig"], item["State"]
        assert item["Config"]["User"] == ("postgres" if name == "db" else "10001:10001"), name + ": non-root user required"
        assert "ALL" in (host.get("CapDrop") or []), name + ": drop ALL capabilities"
        assert "no-new-privileges:true" in (host.get("SecurityOpt") or []), name + ": no-new-privileges required"
        if name == "migrate":
            assert not state["Running"] and state["Status"] == "exited" and state["ExitCode"] == 0, "Migration must finish successfully"
        else:
            assert state["Running"], name + ": service must be running"
        if name != "db":
            assert host["ReadonlyRootfs"] and "/tmp" in (host.get("Tmpfs") or {}), name + ": read-only root and /tmp tmpfs required"
        else:
            assert not host["ReadonlyRootfs"], "DB requires its existing writable data/runtime paths"
        ports = [binding for values in item["NetworkSettings"]["Ports"].values() if values for binding in values]
        assert all(binding["HostIp"] == "127.0.0.1" for binding in ports), name + ": loopback ports required"
        assert bool(ports) == (name != "migrate"), name + ": unexpected port publication"


def self_test():
    project = "vibe-attendance-policy-self-test"
    valid = [{"Config": {"User": "postgres" if name == "db" else "10001:10001", "Labels": {
                "com.docker.compose.project": project, "com.docker.compose.service": name}},
              "HostConfig": {"CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"],
                  "ReadonlyRootfs": name != "db", "Tmpfs": {} if name == "db" else {"/tmp": ""}},
              "State": {"Running": name != "migrate", "Status": "exited" if name == "migrate" else "running", "ExitCode": 0},
              "NetworkSettings": {"Ports": {} if name == "migrate" else {"local/tcp": [{"HostIp": "127.0.0.1"}]}}}
             for name in ["app", "migrate", "db"]]
    verify(valid, project)
    cases = [(index, "Config", "User", "0") for index in range(3)]
    cases += [(index, "HostConfig", field, []) for index in range(3) for field in ["CapDrop", "SecurityOpt"]]
    cases += [(1, "HostConfig", "ReadonlyRootfs", False), (1, "HostConfig", "Tmpfs", {}),
              (1, "State", "ExitCode", 1), (2, "HostConfig", "ReadonlyRootfs", True),
              (0, "NetworkSettings", "Ports", {"http": [{"HostIp": "0.0.0.0"}]})]
    for index, section, field, value in cases:
        bad = deepcopy(valid)
        bad[index][section][field] = value
        try:
            verify(bad, project)
        except AssertionError:
            continue
        raise AssertionError("Policy accepted an unsafe or failed container")
    print(f"Container policy self-check PASS: valid snapshot and {len(cases)} rejected boundaries; no connection opened")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path, nargs="?")
    parser.add_argument("--project", default="vibe-attendance-local")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.snapshot is None:
        parser.error("A fresh sanitized inspect JSON snapshot is required")
    verify(json.loads(args.snapshot.read_text(encoding="utf-8-sig")), args.project)
    print("Container policy PASS: 3 services, non-root/capabilities/NNP, migration exit0 and local boundaries")


if __name__ == "__main__":
    main()
