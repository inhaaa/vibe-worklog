"""Read-only evidence for the WorkLog public V18 -> V30 upgrade (Python standard library)."""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


DATABASE = re.compile(r"worklog_demo(?:_v30_[0-9]{14}_[0-9a-f]{7}_(?:test|final))?")
DB_CONTAINER = "worklog-demo-db-1"
LEGACY = {"platform." + t for t in ("operator_account", "operator_audit_event", "tenant_registry")}
LEGACY.update("tna." + t for t in """
account_token allowed_network approval_event approval_request approval_route approval_route_assignment
approval_route_snapshot approval_route_version approver_assignment attendance_event attendance_type
attendance_type_version calculation_run calendar_day calendar_version common_code common_code_group
correction_detail day_input_revision day_result employee employee_assignment employee_employment_status
employee_onboarding employee_personal_details employee_personal_entry employee_personnel employment_rule_assessment
leave_bucket leave_bucket_attribution leave_ledger leave_request_day org_audit_event organization
organization_head_assignment report_snapshot request_effect role_grant schedule_assignment schedule_break
schedule_day schedule_template schedule_version tenant_account work_confirmation work_policy_version
""".split())
NEW = {"tna." + t for t in """
request_form request_form_version request_approval_step work_time_code work_time_code_version work_time_code_break
work_group work_group_day overtime_request_detail checkout_follow_up attendance_month_closure
""".split()}
DEFAULTS = {
    "tna.organization": {"approval_unit": None},
    "tna.approval_route_version": {"terminal_unit": None},
    "tna.approval_request": {"form_version_id": None, "current_step": 1, "total_steps": 1},
    "tna.approval_route_snapshot": {"form_version_id": None, "organization_path": None},
    "tna.approval_event": {"step_number": None},
    "tna.schedule_assignment": {"work_group_id": None},
    "tna.leave_request_day": {"leave_part": "FULL"},
}
CHECKS = {"seeds", "rls", "views", "bounds", "provisioning"}
MODES = ("compare-restore", "compare-upgrade", "compare-schema")


def grant(table, column, privilege):
    return ("tna." + table, column, "tna_migration", "tna_app", privilege, "false")


ADDED_GRANTS = {grant("organization", "approval_unit", "UPDATE"), grant("approval_request", "current_step", "UPDATE")}
ADDED_GRANTS.update(grant("schedule_assignment", col, "UPDATE") for col in ("work_group_id", "schedule_version_id", "reason"))
NEW_GRANTS = {grant(t.removeprefix("tna."), "", "SELECT") for t in NEW}
for table in ("request_approval_step", "work_group", "work_group_day", "overtime_request_detail",
              "checkout_follow_up", "attendance_month_closure"):
    NEW_GRANTS.add(grant(table, "", "INSERT"))
for table, columns in {
    "request_form": "tenant_id id code kind",
    "request_form_version": "tenant_id id form_id revision effective_from enabled name route_version_id reason created_by",
    "work_time_code": "tenant_id id code",
    "work_time_code_version": """tenant_id id code_id revision effective_from enabled name start_time end_time
                                  before_start before_end after_start after_end reason created_by""",
    "work_time_code_break": "tenant_id version_id kind start_time end_time",
}.items():
    NEW_GRANTS.update(grant(table, column, "INSERT") for column in columns.split())
for table, columns in {
    "request_form": "version", "work_time_code": "version",
    "request_approval_step": "approver_id status decided_by decided_at reason",
    "checkout_follow_up": "last_request_id resolved_result_id resolved_at",
}.items():
    NEW_GRANTS.update(grant(table, column, "UPDATE") for column in columns.split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_digest(value):
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def compact_codes(rows):
    """Replay V26 only for built-in display codes, carrying the suffix across both per tenant."""
    rows = [list(row) for row in rows]
    tenants = {}
    for row in rows:
        tenants.setdefault(row[0], []).append(row)
    kinds = {"ANNUAL_LEAVE": "A01", "ATTENDANCE_CORRECTION": "A02"}
    for entries in tenants.values():
        occupied = {row[2].upper() for row in entries}
        suffix = 2
        for row in sorted(entries, key=lambda entry: entry[3] or ""):
            if row[3] not in kinds or row[2] != row[3]:
                continue
            candidate = kinds[row[3]]
            while candidate in occupied:
                suffix += 1
                candidate = f"A{suffix:02d}"
            occupied.add(candidate)
            row[2] = candidate
    return rows


def schema_fingerprints(raw):
    # The public V18 restore rehearsal verified these PostgreSQL 18 deparser equivalences.
    # An operator, literal, policy principal, or function body change still changes the digest.
    literal = rb"'[A-Za-z0-9_ -]+'::character varying"
    cast = re.compile(rb'(ANY|<> ALL) \(\(ARRAY\[((?:' + literal + rb')(?:, ' + literal + rb')*)\]\)::"text"\[\]\)')
    photo = re.compile(rb"""(ANY) \(\(ARRAY\[(('[^']+'::character varying), ('[^']+'::character varying))\]\)::"text"\[\]\)""")
    policy = re.compile(rb'^CREATE POLICY "(?:authentication_token_(?:update|read)|authentication_reset_audit)" ')
    check = re.compile(rb'^\s*(?:ADD )?CONSTRAINT "[^"]+" CHECK ')
    sort_check = re.compile(rb'^\s*(?:ADD )?CONSTRAINT "common_code(?:_group)?_check1" CHECK ')
    source = b'CHECK (((("sort_order" >= 0) AND ("sort_order" <= 1000000)) AND ("version" >= 0)))'
    target = b'CHECK ((("sort_order" >= 0) AND ("sort_order" <= 1000000) AND ("version" >= 0)))'
    legacy = {tuple(item.encode() for item in table.split(".")) for table in LEGACY}
    delimiter = None
    lines = []
    policies = []

    def rewrite(match):
        elements = match.group(2).split(b", ")
        return match.group(1) + b" (ARRAY[" + b", ".join(b"(" + v + b')::"text"' for v in elements) + b"])"

    for line in raw.splitlines():
        if delimiter is not None:
            if delimiter in line:
                delimiter = None
        elif line.startswith((b"\\restrict ", b"\\unrestrict ")):
            continue
        else:
            body = re.search(rb"\bAS (\$(?:[A-Za-z_][A-Za-z_0-9]*)?\$)\s*$", line)
            if body:
                delimiter = body.group(1)
            elif check.match(line) or policy.match(line):
                if sort_check.match(line):
                    line = line.replace(source, target)
                if b'CONSTRAINT "employee_personnel_photo_content_type_check" CHECK ' in line:
                    line = photo.sub(lambda m: rewrite(m) if {m.group(3), m.group(4)} == {
                        b"'image/png'::character varying", b"'image/jpeg'::character varying"} else m.group(0), line)
                line = cast.sub(rewrite, line)
            if line.startswith((b"CREATE POLICY ", b"ALTER TABLE ")):
                rel = re.search(rb'(?: ON |^ALTER TABLE (?:ONLY )?)"([^"]+)"\."([^"]+)"', line)
                if rel and rel.groups() in legacy and (line.startswith(b"CREATE POLICY ") or b"ROW LEVEL SECURITY" in line):
                    policies.append(line)
        lines.append(line)
    return digest(b"\n".join(lines)), digest(b"\n".join(sorted(policies)))


def schema_fingerprint(raw):
    return schema_fingerprints(raw)[0]


def table_sql(table):
    row = "to_jsonb(t)"
    projection = row
    if table in DEFAULTS:
        projection = "'" + json.dumps(DEFAULTS[table]) + "'::jsonb || " + row
    if table == "tna.attendance_type":
        projection = row + " - 'code'"
    def row_hash(value):
        return f"encode(sha256(convert_to(({value})::text,'UTF8')),'hex')"
    def aggregate(column):
        return (f"""encode(sha256(convert_to(coalesce(string_agg({column}, '' """
                f"""ORDER BY {column} COLLATE "C"), ''), 'UTF8')),'hex')""")
    return f"""SELECT jsonb_build_array('table','{table}',count(*),{aggregate('actual')},{aggregate('projected')})
        FROM (SELECT {row_hash(row)} actual,{row_hash(projection)} projected FROM {table} t) x;"""


METADATA = """
SELECT jsonb_build_array('history',jsonb_agg(to_jsonb(h) ORDER BY installed_rank)) FROM public.flyway_schema_history h;
SELECT jsonb_build_array('inventory',jsonb_agg(schemaname||'.'||tablename ORDER BY schemaname,tablename))
  FROM pg_tables WHERE schemaname IN ('platform','tna');
SELECT jsonb_build_array('database',jsonb_build_array(pg_get_userbyid(datdba),encoding,datlocprovider,
  datcollate,datctype,datlocale,datallowconn,
  (SELECT jsonb_agg(jsonb_build_array(grantor::regrole::text,CASE WHEN grantee=0 THEN 'PUBLIC' ELSE grantee::regrole::text END,
      privilege_type,is_grantable) ORDER BY grantor,grantee,privilege_type,is_grantable)
   FROM aclexplode(coalesce(datacl,acldefault('d',datdba))))))
  FROM pg_database WHERE datname=current_database();
SELECT jsonb_build_array('roles',jsonb_build_array(
  (SELECT jsonb_agg(to_jsonb(r)-'rolpassword'-'oid' ORDER BY rolname) FROM pg_roles r
   WHERE rolname IN ('postgres','tna_app','tna_migration','tna_operator','tna_auth')),
  (SELECT coalesce(jsonb_agg(jsonb_build_array(roleid::regrole::text,member::regrole::text,grantor::regrole::text,
      admin_option,inherit_option,set_option) ORDER BY roleid,member,grantor),'[]')
   FROM pg_auth_members WHERE roleid::regrole::text IN ('postgres','tna_app','tna_migration','tna_auth','tna_operator')
      OR member::regrole::text IN ('postgres','tna_app','tna_migration','tna_auth','tna_operator'))));
SELECT jsonb_build_array('codes',coalesce(jsonb_agg(jsonb_build_array(tenant_id,id,code,system_kind)
  ORDER BY tenant_id,id),'[]')) FROM tna.attendance_type;
WITH relations AS (
  SELECT c.*,n.nspname||'.'||c.relname AS qualified FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname IN ('tna','platform') AND c.relkind='r'
), privileges AS (
  SELECT qualified,''::text AS col,relowner,acl.* FROM relations
  CROSS JOIN LATERAL aclexplode(coalesce(relacl,acldefault('r',relowner))) acl
  UNION ALL
  SELECT qualified,a.attname,relowner,acl.* FROM relations r JOIN pg_attribute a ON a.attrelid=r.oid
  CROSS JOIN LATERAL aclexplode(a.attacl) acl WHERE a.attnum>0 AND NOT a.attisdropped
)
SELECT jsonb_build_array('grants',coalesce(jsonb_agg(jsonb_build_array(qualified,col,grantor::regrole::text,
  CASE WHEN grantee=0 THEN 'PUBLIC' ELSE grantee::regrole::text END,privilege_type,is_grantable::text)
  ORDER BY qualified,col,grantor,grantee,privilege_type,is_grantable),'[]')) FROM privileges WHERE grantee<>relowner;
"""


def snapshot_sql():
    old = "\n".join(table_sql(table) for table in sorted(LEGACY))
    new = "\n".join(table_sql(table) for table in sorted(NEW))
    return (r"""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SELECT bool_or(version='30') AS is_v30 FROM public.flyway_schema_history
\gset
""" + METADATA + old + "\n\\if :is_v30\n" + new + "\n" + v30_checks_sql() + "\n\\endif\nCOMMIT;\n")


def v30_checks_sql():
    new_tables = ",".join("'" + name + "'::regclass" for name in sorted(NEW))
    return f"""
WITH forms(code,kind,name) AS (VALUES ('L01','LEAVE_DAY','휴가 신청서'),('L02','CANCEL_LEAVE','휴가 취소 신청서'),
  ('A01','ATTENDANCE_CORRECTION','출퇴근 정정 신청서'),('O01','OVERTIME','연장근무 신청서'),
  ('O02','CANCEL_OVERTIME','연장근무 취소 신청서')),
missing AS (
  SELECT t.id,forms.* FROM platform.tenant_registry t CROSS JOIN forms
  EXCEPT
  SELECT f.tenant_id,f.code,f.kind,v.name FROM tna.request_form f JOIN tna.request_form_version v
    ON v.tenant_id=f.tenant_id AND v.form_id=f.id
  WHERE f.version=0 AND v.revision=1 AND v.effective_from=DATE '0001-01-01' AND v.enabled
    AND v.created_by IS NULL AND v.route_version_id IS NULL AND v.reason='기존 결재선 적용'
)
SELECT jsonb_build_array('seeds',NOT EXISTS (SELECT FROM missing));
WITH baseline AS (SELECT pg_get_expr(polqual,polrelid) USING_EXPR,pg_get_expr(polwithcheck,polrelid) CHECK_EXPR,
  polroles FROM pg_policy WHERE polrelid='tna.employee_onboarding'::regclass AND polname='tenant_isolation')
SELECT jsonb_build_array('rls',count(*)={len(NEW)} AND bool_and(
  c.relrowsecurity AND c.relforcerowsecurity AND c.relowner='tna_migration'::regrole
  AND (SELECT count(*)=1 AND bool_and(p.polname='tenant_isolation' AND p.polpermissive AND p.polcmd='*'
    AND p.polroles @> b.polroles AND p.polroles <@ b.polroles
    AND (pg_get_expr(p.polqual,c.oid)=b.USING_EXPR AND pg_get_expr(p.polwithcheck,c.oid)=b.CHECK_EXPR) IS TRUE)
    FROM pg_policy p WHERE p.polrelid=c.oid))) FROM pg_class c CROSS JOIN baseline b WHERE c.oid IN ({new_tables});
SELECT jsonb_build_array('views',count(*)=2 AND bool_and(c.relkind='v' AND c.relowner='tna_migration'::regrole
  AND (c.reloptions @> ARRAY['security_invoker=true']) IS TRUE AND has_table_privilege('tna_app',c.oid,'SELECT')
  AND NOT EXISTS (SELECT FROM aclexplode(c.relacl) a WHERE a.grantee<>c.relowner
    AND (a.grantee<>'tna_app'::regrole OR a.privilege_type<>'SELECT' OR a.is_grantable))))
  FROM pg_class c WHERE c.oid IN ('tna.effective_schedule_assignment'::regclass,'tna.work_group_revision_period'::regclass);
SELECT jsonb_build_array('bounds',
  (SELECT count(*)=3 FROM pg_attribute WHERE atttypid='numeric'::regtype AND atttypmod=-1 AND attnotnull AND
   (attrelid,attname) IN (('tna.leave_request_day'::regclass,'quantity_days'),('tna.leave_ledger'::regclass,'signed_quantity_days'),
                          ('tna.day_result'::regclass,'leave_days')))
  AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='tna.leave_ledger'::regclass
       AND conname='leave_ledger_admin_entry_bounds' AND contype='c' AND NOT convalidated));
SELECT jsonb_build_array('provisioning',
  (SELECT count(*)=5 AND bool_and(p.prosecdef AND p.proowner='tna_migration'::regrole
   AND (replace(array_to_string(p.proconfig,','),' ','')='search_path=pg_catalog,pg_temp') IS TRUE
   AND NOT EXISTS (SELECT FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                  WHERE a.grantee<>p.proowner AND a.privilege_type='EXECUTE'))
   FROM pg_proc p WHERE p.oid IN ('tna.seed_request_forms(uuid)'::regprocedure,'tna.provision_request_forms()'::regprocedure,
                 'tna.provision_overtime_forms(uuid)'::regprocedure,'tna.compact_attendance_codes(uuid)'::regprocedure,
                 'tna.provision_tenant_codes()'::regprocedure))
  AND (SELECT count(*)=2 FROM pg_trigger WHERE tgrelid='platform.tenant_registry'::regclass AND tgenabled='O'
       AND (tgname,tgfoid) IN (('tenant_codes_provision','tna.provision_tenant_codes()'::regprocedure),
                              ('request_forms_provision','tna.provision_request_forms()'::regprocedure))));
"""


def snapshot(database):
    require(DATABASE.fullmatch(database), "WorkLog 데이터베이스 범위를 벗어났습니다")
    base = ["docker", "exec", "-i", "-u", "postgres", DB_CONTAINER]
    psql = ["psql", "--dbname=" + database, "-XAtq", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate"]
    output = subprocess.run(base + psql, input=snapshot_sql().encode("utf-8"), capture_output=True, check=True)
    raw = {}
    tables = {}
    for line in output.stdout.splitlines():
        key, *values = json.loads(line)
        if key == "table":
            require(values[0] not in tables, "중복 테이블 증거")
            tables[values[0]] = values[1:]
        else:
            require(key not in raw and len(values) == 1, "중복 스냅샷 증거")
            raw[key] = values[0]
    require(set(raw["inventory"]) == tables.keys(), "예상과 다른 업무 테이블")
    dump = subprocess.run(base + ["pg_dump", "--dbname=" + database, "--schema-only", "--quote-all-identifiers"],
                          capture_output=True, check=True)
    schema, policies = schema_fingerprints(dump.stdout)
    history = raw["history"]
    result = dict(format=1, version=len(history), history=history, schema=schema, legacy_policies=policies,
                  tables=tables, grants=sorted(raw["grants"]), database=json_digest(raw["database"]),
                  roles=json_digest(raw["roles"]), codes=json_digest(raw["codes"]),
                  expected_codes=json_digest(compact_codes(raw["codes"])),
                  checks={key: raw[key] for key in CHECKS if key in raw})
    validate(result)
    return result


def validate(s):
    require(s["format"] == 1 and s["version"] in (18, 30), "V18 또는 V30 증거가 필요합니다")
    version, history = s["version"], s["history"]
    require(len(history) == version, "Flyway 이력 수가 다릅니다")
    for n, row in enumerate(history, 1):
        require(row["version"] == str(n) and row["installed_rank"] == n and row["type"] == "SQL"
                and row["installed_by"] == "tna_migration" and row["success"] is True
                and isinstance(row["checksum"], int) and re.fullmatch(rf"V{n}__[a-z0-9_]+\.sql", row["script"]),
                f"Flyway V{n} 이력 불일치")
    expected = LEGACY if version == 18 else LEGACY | NEW
    require(s["tables"].keys() == expected, "업무 테이블 목록 불일치")
    for table, (count, actual, projected) in s["tables"].items():
        require(isinstance(count, int) and count >= 0 and
                all(re.fullmatch("[0-9a-f]{64}", x) for x in (actual, projected)), f"{table} 지문 형식 오류")
    for field in ("schema", "legacy_policies", "database", "roles", "codes", "expected_codes"):
        require(isinstance(s[field], str) and re.fullmatch("[0-9a-f]{64}", s[field]), field + " 지문 형식 오류")
    expected_checks = set() if version == 18 else CHECKS
    require(s["checks"].keys() == expected_checks, "스키마 검사 목록 불일치")


def compare(mode, before, after):
    validate(before)
    validate(after)
    if mode == "compare-restore":
        for key in before.keys() | after.keys():
            require(before.get(key) == after.get(key), "독립 복원 불일치 " + key)
        return
    require(mode in ("compare-upgrade", "compare-schema"), "알 수 없는 검증 모드")
    source_version = 18 if mode == "compare-upgrade" else 30
    require((before["version"], after["version"]) == (source_version, 30), "비교할 스키마 버전 불일치")
    for key in ("database", "roles", "legacy_policies"):
        require(before[key] == after[key], "DB 권한·정책 불일치 " + key)
    for key, passed in after["checks"].items():
        require(passed is True, "V30 계약 불일치 " + key)
    old_grants = {tuple(row) for row in before["grants"]}
    new_grants = {tuple(row) for row in after["grants"]}
    if mode == "compare-schema":
        require(all(value is True for value in before["checks"].values()), "V30 리허설 계약 불일치")
        require(before["schema"] == after["schema"] and old_grants == new_grants, "리허설·최종 스키마/권한 불일치")
        for old, new in zip(before["history"], after["history"]):
            stable = {k for k in old.keys() | new.keys() if k not in ("installed_on", "execution_time")}
            require(all(old.get(k) == new.get(k) for k in stable), "리허설·최종 Flyway 이력 불일치")
        return
    require(after["history"][:18] == before["history"], "기존 Flyway 이력이 변경됐습니다")
    require(new_grants == old_grants | ADDED_GRANTS | NEW_GRANTS, "예상과 다른 테이블/컬럼 권한 변경")
    require(before["expected_codes"] == after["codes"], "기존 근태코드·고객 코드 보존 불일치")
    for table in sorted(LEGACY):
        old, new = before["tables"][table], after["tables"][table]
        require((old[0], old[2]) == (new[0], new[2]), f"기존 행 또는 신규 열 초기값 불일치 {table}")
    tenants = before["tables"]["platform.tenant_registry"][0]
    for table in sorted(NEW):
        expected = tenants * 5 if table in ("tna.request_form", "tna.request_form_version") else 0
        require(after["tables"][table][0] == expected, "신규 테이블의 초기 행 수 불일치 " + table)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    commands.add_parser("snapshot").add_argument("database")
    for mode in MODES:
        command = commands.add_parser(mode)
        command.add_argument("before", type=Path)
        command.add_argument("after", type=Path)
    args = parser.parse_args()
    if args.mode == "snapshot":
        print(json.dumps(snapshot(args.database), sort_keys=True, separators=(",", ":")))
        return
    compare(args.mode, json.loads(args.before.read_bytes()), json.loads(args.after.read_bytes()))
    print("WORKLOG_V30_PASS", args.mode)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # PostgreSQL errors may include values. Keep source data out of console output.
        print(f"WorkLog PostgreSQL 증거 수집 실패 (exit {error.returncode})", file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
