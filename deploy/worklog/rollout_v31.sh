#!/usr/bin/env bash
# One approved deployment can finish its guarded phases if the web console disconnects.
set -euo pipefail
umask 077
stage=/srv/worklog/.local/public-month-close-details-20261119
[[ "$(pwd -P)" == "$stage" && "$(stat --format=%a .)" == 700 ]]
exec 9>"$stage/rollout.lock"
flock --nonblock 9
job=$(mktemp -d "$stage/job-XXXXXXXX")
printf 'WORKLOG_JOB %s\n' "$(basename "$job")"
python3 -E -B build_v31.py | tee "$job/build.log"
python3 -E -B - "$job/build.log" <<'PY' > "$job/jar-hashes"
import json, pathlib, sys
items = [json.loads(v.removeprefix('WORKLOG_IMAGE_PROOF ')) for v in pathlib.Path(sys.argv[1]).read_text().splitlines()
         if v.startswith('WORKLOG_IMAGE_PROOF ')]
if len(items) != 1:
    sys.exit('A single successful image verification is required')
print(items[0]['revision'], items[0]['runtime_sha256'], items[0]['migrate_sha256'])
PY
read -r revision app_jar migrate_jar < "$job/jar-hashes"
python3 -E -B release_v31.py rehearse "$revision" "$app_jar" "$migrate_jar" | tee "$job/rehearse.log"
run=$(awk '$1 == "WORKLOG_RUN" {print $2}' "$job/rehearse.log")
[[ "$run" =~ ^run-[0-9]{14}-1d351fb$ && -f "$run/ready.json" ]]
python3 -E -B release_v31.py apply "$run" | tee "$job/apply.log"
python3 -E -B release_v31.py verify "$run" | tee "$job/verify.log"
if [[ "${1:-}" == --with-february-example ]]; then
    printf '%s  %s\n' f18e5bc925fdfccf77ff36b5f5fc373af9ef00dce753400c18fde14029eb67c4 2025-02-month-close.sql | sha256sum --check -
    docker exec -i -u postgres worklog-demo-db-1 psql -X -q -v ON_ERROR_STOP=1 -d worklog_demo < 2025-02-month-close.sql
fi
printf 'WORKLOG_ROLLOUT_COMPLETE %s\n' "$run"
