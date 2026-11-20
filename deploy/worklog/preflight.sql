-- DB owner only; stop the WorkLog web app before this first-bootstrap check.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
DO $guard$
BEGIN
    IF session_user <> 'postgres' OR current_database() <> 'worklog_demo' THEN
        RAISE EXCEPTION 'WORKLOG_PREFLIGHT: requires postgres on worklog_demo';
    END IF;
    IF EXISTS (SELECT 1 FROM platform.tenant_registry
               WHERE id <> 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'::uuid)
       OR EXISTS (SELECT 1 FROM platform.operator_account) THEN
        RAISE EXCEPTION 'WORKLOG_PREFLIGHT: unexpected tenant or operator; no changes made';
    END IF;
END
$guard$;
ROLLBACK;
