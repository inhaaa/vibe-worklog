-- One employee can have multiple department export rows. Keep one set of immutable
-- daily result references per employee and close version, independent of department.
CREATE TABLE tna.attendance_month_selective_member (
    tenant_id uuid NOT NULL,
    closure_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    result_ids uuid[], -- NULL only when an older partial month's dates cannot be reconstructed.
    PRIMARY KEY (tenant_id,closure_id,employee_id),
    FOREIGN KEY (tenant_id,closure_id) REFERENCES tna.attendance_month_closure(tenant_id,id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    CHECK (cardinality(result_ids) BETWEEN 1 AND 31)
);
ALTER TABLE tna.attendance_month_selective_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_month_selective_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.attendance_month_selective_member TO tna_migration,tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.attendance_month_selective_member FROM PUBLIC;
GRANT SELECT,INSERT ON tna.attendance_month_selective_member TO tna_app;

CREATE FUNCTION tna.selective_close_members(company uuid, closure uuid, starts date, legacy_closed_at timestamptz)
RETURNS TABLE(employee_id uuid,result_ids uuid[])
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
    WITH bounds AS (SELECT starts AS first_day,(starts+interval '1 month')::date-1 AS last_day),
    exported AS (
        SELECT x.employee_id,sum(x.employed_days) AS days FROM tna.attendance_month_export_row x
        WHERE x.tenant_id=company AND x.closure_id=closure GROUP BY x.employee_id
    ), membership AS (
        SELECT x.employee_id,
            CASE WHEN x.days=b.last_day-b.first_day+1 THEN b.first_day ELSE greatest(b.first_day,e.joined_on) END AS first_day,
            CASE WHEN x.days=b.last_day-b.first_day+1 THEN b.last_day ELSE least(b.last_day,e.left_on) END AS last_day,
            legacy_closed_at IS NULL OR x.days=b.last_day-b.first_day+1 OR
                (greatest(e.created_at,e.updated_at)<=legacy_closed_at AND
                 least(b.last_day,e.left_on)-greatest(b.first_day,e.joined_on)+1=x.days) AS known
        FROM exported x JOIN tna.employee e ON e.tenant_id=company AND e.id=x.employee_id CROSS JOIN bounds b
    ), latest AS (
        SELECT DISTINCT ON (r.employee_id,r.work_date) r.employee_id,r.work_date,r.id,r.input_refs,m.known
        FROM membership m JOIN tna.day_result r ON r.tenant_id=company AND r.employee_id=m.employee_id
        CROSS JOIN bounds b WHERE r.work_date BETWEEN b.first_day AND b.last_day
          AND (m.known IS NOT TRUE OR r.work_date BETWEEN m.first_day AND m.last_day)
          AND (legacy_closed_at IS NULL OR r.calculated_at<=legacy_closed_at)
        ORDER BY r.employee_id,r.work_date,r.result_revision DESC
    )
    SELECT r.employee_id,CASE WHEN r.known THEN array_agg(r.id ORDER BY r.work_date) END
    FROM latest r WHERE r.input_refs#>>'{0,clock,schedule,workMode}'='SELECTIVE'
      AND (legacy_closed_at IS NOT NULL OR EXISTS (
          SELECT 1 FROM tna.effective_schedule_assignment a WHERE a.tenant_id=company AND a.employee_id=r.employee_id
            AND r.work_date>=a.valid_from AND (a.valid_to IS NULL OR r.work_date<a.valid_to)
            AND a.schedule_version_id::text=r.input_refs#>>'{0,clock,scheduleVersionId}'))
    GROUP BY r.employee_id,r.known
$$;
REVOKE ALL ON FUNCTION tna.selective_close_members(uuid,uuid,date,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tna.selective_close_members(uuid,uuid,date,timestamptz) TO tna_app;

CREATE OR REPLACE FUNCTION tna.capture_attendance_month_close() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    INSERT INTO tna.attendance_month_export_row
        SELECT NEW.tenant_id,NEW.id,v.* FROM tna.month_close_export_values(NEW.tenant_id,NEW.month_start) v;
    INSERT INTO tna.attendance_month_selective_member
        SELECT NEW.tenant_id,NEW.id,s.* FROM tna.selective_close_members(NEW.tenant_id,NEW.id,NEW.month_start,NULL) s;
    RETURN NEW;
END $$;

-- V31 captured employed-day totals. A full month is provable even after HR edits.
-- A partial month requires the HR range to predate the close and match that total.
CREATE POLICY selective_close_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $$
DECLARE
    company uuid;
    previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    FOR company IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM set_config('app.tenant_id',company::text,true);
        INSERT INTO tna.attendance_month_selective_member
            SELECT company,c.id,s.* FROM tna.attendance_month_closure c
            CROSS JOIN LATERAL tna.selective_close_members(company,c.id,c.month_start,c.changed_at) s
            WHERE c.tenant_id=company AND c.status='CLOSED' AND c.export_at_close;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END $$;
DROP POLICY selective_close_registry_scan ON platform.tenant_registry;
