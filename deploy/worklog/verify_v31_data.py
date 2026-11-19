"""Read-only, release-scoped data and access checks for WorkLog V30 -> V31."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ORIGINAL = HERE / "verify_v30_data.py"
SQL_FILE = HERE / "V31__attendance_month_close_export.sql"
OLD_VERIFY = "1b1c2c8580c77bb14c296bc3d52f299051efd12864871973f59f7c7b882759b6"
SQL_HASH = "927cb3cd493b6800ce6b04f3518d543289e7805c4a17787d1edfd2e82db801b6"
DB_NAME = re.compile(r"worklog_demo(?:_v31_[0-9]{14}_[a-f0-9]{7}_(?:test|final))?")
EXPORT = "tna.attendance_month_export_row"
CLOSURE = "tna.attendance_month_closure"

def require(test, message):
    if not test:
        raise ValueError(message)

def fixed_text(path, expected):
    require(path.is_file() and path.resolve(strict=True) == path, "invalid verifier source path")
    data = path.read_bytes().replace(b"\r\n", b"\n")
    require(hashlib.sha256(data).hexdigest() == expected, "unexpected " + path.name + " digest")
    return data.decode("utf-8")

fixed_text(ORIGINAL, OLD_VERIFY)
spec = importlib.util.spec_from_file_location("verified_v30_data", ORIGINAL)
old = importlib.util.module_from_spec(spec)
spec.loader.exec_module(old)
ORIGINAL_TABLES = old.LEGACY | old.NEW
# Include the V19-V30 tables when hashing the existing tenant policies.
old.LEGACY = ORIGINAL_TABLES

def rows(label, query):
    return f"""SELECT jsonb_build_array('rows','{label}',count(*),
        encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h COLLATE "C"),''),'UTF8')),'hex'))
        FROM (SELECT encode(sha256(convert_to(js::text,'UTF8')),'hex') AS h FROM ({query}) q) d;"""

def expected():
    migration = fixed_text(SQL_FILE, SQL_HASH)
    start = migration.index(' AS $$\n', migration.index('CREATE FUNCTION tna.month_close_export_values')) + len(' AS $$\n')
    sql = migration[start:migration.index('\n$$;', start)]
    sql = re.sub(r'\b(month_start|company|legacy_closed_at)\b',lambda m: {
        "month_start":"c.month_start","company":"c.tenant_id","legacy_closed_at":"c.changed_at"}[m.group()],sql)
    return rows("expected", f"""SELECT jsonb_build_object('tenant_id',c.tenant_id,'closure_id',c.id) || to_jsonb(v) AS js
        FROM (SELECT DISTINCT ON (tenant_id,month_start) tenant_id,id,month_start,changed_at,status
              FROM tna.attendance_month_closure ORDER BY tenant_id,month_start,version DESC) c
        CROSS JOIN LATERAL ({sql}) AS v(employee_id,employee_no,employee_name,organization_id,organization_code,
            organization_name,employed_days,worked_days,actual_millis,leave_days) WHERE c.status='CLOSED'""")

META31 = """
SELECT jsonb_build_array('v31-contract',(
  SELECT c.relrowsecurity AND c.relforcerowsecurity AND pg_get_userbyid(c.relowner)='tna_migration'
    AND NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname='month_close_export_registry_scan')
    AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid AND p.polname='tenant_isolation'
      AND p.polqual IS NOT NULL AND p.polwithcheck IS NOT NULL
      AND p.polroles @> ARRAY['tna_migration'::regrole::oid,'tna_app'::regrole::oid]
      AND cardinality(p.polroles)=2)
    AND EXISTS(SELECT 1 FROM pg_trigger tr WHERE tr.tgrelid='tna.attendance_month_closure'::regclass
      AND tr.tgname='capture_close_result' AND tr.tgenabled='O' AND NOT tr.tgisinternal
      AND tr.tgfoid='tna.capture_attendance_month_close()'::regprocedure)
    AND EXISTS(SELECT 1 FROM pg_proc f WHERE f.oid='tna.month_close_export_values(uuid,date,timestamptz)'::regprocedure
      AND NOT f.prosecdef AND has_function_privilege('tna_app',f.oid,'EXECUTE')
      AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(f.proacl,acldefault('f', f.proowner))) a
                     WHERE a.grantee=0 AND a.privilege_type='EXECUTE'))
    AND NOT EXISTS(SELECT 1 FROM tna.attendance_month_closure WHERE export_at_close)
  FROM pg_class c WHERE c.oid='tna.attendance_month_export_row'::regclass));
"""

def query():
    body = ["BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;", "SET LOCAL TIME ZONE 'UTC';",
            "SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='31' AND success) AS is_v31",
            "\\gset",old.METADATA]
    for table in sorted(ORIGINAL_TABLES):
        js = "to_jsonb(t)" + (" - 'export_at_close'" if table == CLOSURE else "")
        body.append(rows(table, f"SELECT {js} AS js FROM {table} t"))
    body.extend([expected(), "\\if :is_v31",rows(EXPORT, f"SELECT to_jsonb(t) AS js FROM {EXPORT} t"),META31,"\\endif","COMMIT;"])
    return "\n".join(body)

def ddl_for_restore(raw):
    # PG18 flattens just these two AND groups when a V30 pg_dump is parsed again.
    # Keep all comparison predicates, roles, function bodies and operators intact.
    left=b'(((("current_step" >= 1) AND ("current_step" <= "total_steps")) AND '
    right=b'((("current_step" >= 1) AND ("current_step" <= "total_steps") AND '
    minimum=b'("signed_quantity_days" >= (\'-100000\'::integer)::numeric)'
    maximum=b'("signed_quantity_days" <= (100000)::numeric)'
    lines=[]
    for line in raw.splitlines(keepends=True):
        if line.startswith(b'    CONSTRAINT "approval_request_check" CHECK '):
            line=line.replace(left,right)
        elif line.startswith(b'    ADD CONSTRAINT "leave_ledger_admin_entry_bounds" CHECK '):
            line=line.replace(b'OR (('+minimum+b' AND '+maximum+b') AND ',b'OR ('+minimum+b' AND '+maximum+b' AND ')
        lines.append(line)
    return b''.join(lines)

def snapshot(database):
    require(DB_NAME.fullmatch(database), "unexpected WorkLog database")
    base=["docker","exec","-i","-u","postgres","worklog-demo-db-1"]
    out=subprocess.run(base+["psql","-XAtq","--dbname="+database,"-v","ON_ERROR_STOP=1","-v","VERBOSITY=sqlstate"],
                       input=query().encode(),capture_output=True,check=True).stdout
    meta, hashes = {},{}
    for line in out.splitlines():
        key,*values=json.loads(line)
        if key=="rows":
            name,n,sha=values
            require(name not in hashes,"duplicate row fingerprint")
            hashes[name]=[n,sha]
        else:
            require(key not in meta and len(values)==1,"duplicate metadata entry")
            meta[key]=values[0]
    raw_schema=subprocess.run(base+["pg_dump","--dbname="+database,"--schema-only","--quote-all-identifiers"],
                              capture_output=True,check=True).stdout
    previous_schema, policies=old.schema_fingerprints(raw_schema)
    schema=old.schema_fingerprints(ddl_for_restore(raw_schema))[0]
    result={"format":31,"version":len(meta["history"]),"history":meta["history"],"schema":schema,
            "old_policies":policies,"roles":old.json_digest(meta["roles"]),"database":old.json_digest(meta["database"]),
            "grants":sorted(meta["grants"]),"inventory":sorted(meta["inventory"]),"rows":hashes,
            "contract":meta.get("v31-contract"), "previous_schema":previous_schema}
    validate(result)
    return result

def validate(s):
    ver=s["version"]
    require(s["format"]==31 and ver in (30,31),"unexpected Flyway schema")
    require(len(s["history"])==ver and all(h["version"]==str(i) and h["installed_rank"]==i and
            h["type"]=="SQL" and h["success"] is True and h["installed_by"]=="tna_migration" and
            isinstance(h["checksum"],int) and re.fullmatch(rf"V{i}__[a-z0-9_]+\.sql",h["script"])
            for i,h in enumerate(s["history"],1)), "unexpected Flyway history")
    tables=ORIGINAL_TABLES|({EXPORT} if ver==31 else set())
    require(set(s["inventory"])==tables and set(s["rows"])==tables|{"expected"},"unexpected table inventory")
    require(all(isinstance(n,int) and n>=0 and re.fullmatch(r"[a-f0-9]{64}",v)
                for n,v in s["rows"].values()),"invalid table fingerprint")
    require(s["contract"] is (True if ver==31 else None),"V31 RLS, projection, trigger or existing closure flag mismatch")
    if ver==31:
        require(s["rows"][EXPORT]==s["rows"]["expected"],"captured export rows differ from current verified source")

def compare(mode,before,after):
    validate(before);validate(after)
    if mode=="compare-restore":
        keys=before.keys()|after.keys()
        require(all(before.get(k)==after.get(k) for k in keys if k!="previous_schema"),
                "restored data/schema/roles/privileges differ from V30 backup")
        return
    for key in ("roles","database","old_policies"):
        require(before[key]==after[key],"access boundary differs: "+key)
    if mode=="compare-schema":
        require(before["version"]==after["version"]==31 and before["schema"]==after["schema"] and
                before["grants"]==after["grants"],"rehearsal and running V31 schema or ACL differ")
        for a,b in zip(before["history"],after["history"]):
            require(all(a[k]==b.get(k) for k in a if k not in ("execution_time","installed_on")),"Flyway entry differs")
        return
    require(mode=="compare-upgrade" and before["version"]==30 and after["version"]==31,"unexpected evidence versions")
    require(before["history"]==after["history"][:30],"previous Flyway history changed")
    for table in sorted(ORIGINAL_TABLES|{"expected"}):
        require(before["rows"][table]==after["rows"][table],"existing data changed: "+table)
    base={tuple(x) for x in before["grants"]}
    added={old.grant("attendance_month_export_row","",p) for p in ("SELECT","INSERT")}
    require(base|added=={tuple(x) for x in after["grants"]},"unexpected grants on existing/new tables")

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    actions=parser.add_subparsers(dest="action",required=True)
    actions.add_parser("snapshot").add_argument("database")
    for m in ("compare-restore","compare-upgrade","compare-schema"):
        p=actions.add_parser(m);p.add_argument("before",type=Path);p.add_argument("after",type=Path)
    a=parser.parse_args()
    if a.action=="snapshot":
        print(json.dumps(snapshot(a.database),sort_keys=True,separators=(",",":")))
    else:
        compare(a.action,json.loads(a.before.read_bytes()),json.loads(a.after.read_bytes()))
        print("WORKLOG_V31_PASS",a.action)
if __name__=="__main__":
    try:
        main()
    except subprocess.CalledProcessError as e:
        sys.exit("WorkLog database evidence collection failed: "+str(e.returncode))
    except (KeyError,TypeError,ValueError,OSError) as e:
        sys.exit(str(e))
