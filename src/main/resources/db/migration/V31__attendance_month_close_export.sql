-- Existing names and assignments can already have changed since their original V30 close.
ALTER TABLE tna.attendance_month_closure ADD COLUMN export_at_close boolean NOT NULL DEFAULT false;
ALTER TABLE tna.attendance_month_closure ALTER COLUMN export_at_close SET DEFAULT true;

CREATE TABLE tna.attendance_month_export_row (
    tenant_id uuid NOT NULL,
    closure_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    employee_no text NOT NULL,
    employee_name text NOT NULL,
    organization_id uuid,
    organization_code text,
    organization_name text,
    employed_days integer NOT NULL CHECK (employed_days BETWEEN 1 AND 31),
    worked_days integer CHECK (worked_days BETWEEN 0 AND employed_days),
    actual_millis bigint CHECK (actual_millis >= 0),
    leave_days numeric NOT NULL CHECK (leave_days >= 0),
    UNIQUE NULLS NOT DISTINCT (tenant_id,closure_id,employee_id,organization_id),
    FOREIGN KEY (tenant_id,closure_id) REFERENCES tna.attendance_month_closure(tenant_id,id)
);
ALTER TABLE tna.attendance_month_export_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_month_export_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.attendance_month_export_row TO tna_migration,tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.attendance_month_export_row FROM PUBLIC;
GRANT SELECT,INSERT ON tna.attendance_month_export_row TO tna_app;

-- Only legacy captures need to distinguish employment recorded after the original close.
CREATE FUNCTION tna.month_close_export_values(company uuid, month_start date, legacy_closed_at timestamptz DEFAULT NULL)
RETURNS TABLE (employee_id uuid, employee_no text, employee_name text, organization_id uuid,
               organization_code text, organization_name text, employed_days integer,
               worked_days integer, actual_millis bigint, leave_days numeric)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
    WITH dates AS (
        SELECT month_start+n AS work_date FROM generate_series(0,30) n
        WHERE month_start+n < (month_start+interval '1 month')::date
    ), leaves AS (
        SELECT f.employee_id,ld.leave_date,sum(ld.quantity_days) AS quantity
        FROM tna.request_effect f JOIN tna.approval_request ar ON ar.tenant_id=f.tenant_id AND ar.id=f.request_id
        JOIN tna.leave_request_day ld ON ld.tenant_id=ar.tenant_id AND ld.request_id=ar.id
        WHERE f.tenant_id=company AND f.effect_kind='LEAVE' AND f.effect_status='EFFECTIVE' AND ar.status='APPROVED'
          AND ld.leave_date>=month_start AND ld.leave_date<(month_start+interval '1 month')::date
        GROUP BY f.employee_id,ld.leave_date
    ), totals AS (
        SELECT e.id AS employee_id,e.employee_no::text,e.display_name::text AS employee_name,
               o.id AS organization_id,o.code::text AS organization_code,o.name::text AS organization_name,
               count(*)::integer AS employed_days,(count(*) FILTER (WHERE r.actual_work_millis>0))::integer AS worked_days,
               coalesce(sum(r.actual_work_millis),0)::bigint AS actual_millis,coalesce(sum(l.quantity),0) AS leave_days,
               bool_or(r.actual_work_millis IS NULL AND (r.id IS NOT NULL
                       OR coalesce(greatest(e.created_at,e.updated_at)>legacy_closed_at,false))) AS unknown_work
        FROM tna.employee e JOIN dates d ON d.work_date>=e.joined_on AND (e.left_on IS NULL OR d.work_date<=e.left_on)
        LEFT JOIN tna.employee_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
             AND a.valid_from<=d.work_date AND (a.valid_to IS NULL OR d.work_date<a.valid_to)
        LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
        LEFT JOIN LATERAL (
            SELECT id,actual_work_millis FROM tna.day_result WHERE tenant_id=e.tenant_id AND employee_id=e.id
              AND work_date=d.work_date ORDER BY result_revision DESC LIMIT 1
        ) r ON true
        LEFT JOIN leaves l ON l.employee_id=e.id AND l.leave_date=d.work_date
        WHERE e.tenant_id=company GROUP BY e.tenant_id,e.id,o.tenant_id,o.id
    )
    SELECT employee_id,employee_no,employee_name,organization_id,organization_code,organization_name,employed_days,
           CASE WHEN NOT unknown_work THEN worked_days END, CASE WHEN NOT unknown_work THEN actual_millis END, leave_days
    FROM totals
$$;
REVOKE ALL ON FUNCTION tna.month_close_export_values(uuid,date,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tna.month_close_export_values(uuid,date,timestamptz) TO tna_app;

CREATE FUNCTION tna.capture_attendance_month_close() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    INSERT INTO tna.attendance_month_export_row
        SELECT NEW.tenant_id,NEW.id,v.* FROM tna.month_close_export_values(NEW.tenant_id,NEW.month_start) v;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION tna.capture_attendance_month_close() FROM PUBLIC;
CREATE TRIGGER capture_close_result AFTER INSERT ON tna.attendance_month_closure
    FOR EACH ROW WHEN (NEW.status='CLOSED') EXECUTE FUNCTION tna.capture_attendance_month_close();

CREATE POLICY month_close_export_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $$
DECLARE
    company uuid;
    previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    FOR company IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM set_config('app.tenant_id',company::text,true);
        INSERT INTO tna.attendance_month_export_row
            SELECT company,c.id,v.* FROM (
                SELECT DISTINCT ON (month_start) id,month_start,status,changed_at FROM tna.attendance_month_closure
                WHERE tenant_id=company ORDER BY month_start,version DESC
            ) c CROSS JOIN LATERAL tna.month_close_export_values(company,c.month_start,c.changed_at) v
            WHERE c.status='CLOSED';
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END $$;
DROP POLICY month_close_export_registry_scan ON platform.tenant_registry;
