-- Payroll attendance totals captured with each month close (design section 5).
-- Time totals are NULL when any day's actual work is unconfirmed; approved overtime and unapproved extra are also
-- NULL when a confirmed day predates CALC-3 (no approved_overtime_millis) and an approved effective overtime exists for
-- that day; without one, such a day counts as 0 approved overtime. Late/early/absence count unjudged days as 0.
DROP FUNCTION tna.month_close_export_values(uuid,date,timestamptz);
CREATE FUNCTION tna.month_close_export_values(company uuid, month_start date, legacy_closed_at timestamptz DEFAULT NULL)
RETURNS TABLE (employee_id uuid, employee_no text, employee_name text, organization_id uuid,
               organization_code text, organization_name text, employed_days integer,
               worked_days integer, actual_millis bigint, leave_days numeric,
               approved_overtime_millis bigint, unapproved_extra_millis bigint, night_work_millis bigint,
               holiday_work_millis bigint, late_count integer, late_millis bigint, early_leave_count integer,
               early_leave_millis bigint, absence_days numeric)
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
                       OR coalesce(greatest(e.created_at,e.updated_at)>legacy_closed_at,false))) AS unknown_work,
               bool_or(r.actual_work_millis IS NOT NULL AND r.approved_overtime_millis IS NULL AND EXISTS (
                       SELECT 1 FROM tna.request_effect f JOIN tna.approval_request ar ON ar.tenant_id=f.tenant_id AND ar.id=f.request_id
                       JOIN tna.overtime_request_detail od ON od.tenant_id=ar.tenant_id AND od.request_id=ar.id
                       WHERE f.tenant_id=company AND f.employee_id=e.id AND f.effect_kind='OVERTIME' AND f.effect_status='EFFECTIVE'
                         AND ar.kind='OVERTIME' AND ar.status='APPROVED' AND od.work_date=d.work_date)) AS pre_calc3,
               coalesce(sum(r.approved_overtime_millis),0)::bigint AS approved_overtime_millis,
               coalesce(sum(greatest(coalesce(r.outside_schedule_millis,0)-coalesce(r.approved_overtime_millis,0)-coalesce(r.holiday_work_millis,0),0)),0)::bigint
                   AS unapproved_extra_millis,
               coalesce(sum(r.night_work_millis),0)::bigint AS night_work_millis,
               coalesce(sum(r.holiday_work_millis),0)::bigint AS holiday_work_millis,
               (count(*) FILTER (WHERE r.late_millis>0))::integer AS late_count,
               coalesce(sum(r.late_millis),0)::bigint AS late_millis,
               (count(*) FILTER (WHERE r.early_leave_millis>0))::integer AS early_leave_count,
               coalesce(sum(r.early_leave_millis),0)::bigint AS early_leave_millis,
               coalesce(sum(CASE r.absence WHEN 'FULL' THEN 1 WHEN 'HALF' THEN 0.5 END),0) AS absence_days
        FROM tna.employee e JOIN dates d ON d.work_date>=e.joined_on AND (e.left_on IS NULL OR d.work_date<=e.left_on)
        LEFT JOIN tna.employee_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
             AND a.valid_from<=d.work_date AND (a.valid_to IS NULL OR d.work_date<a.valid_to)
        LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
        LEFT JOIN LATERAL (
            SELECT id,actual_work_millis,approved_overtime_millis,outside_schedule_millis,night_work_millis,
                   holiday_work_millis,late_millis,early_leave_millis,absence
            FROM tna.day_result WHERE tenant_id=e.tenant_id AND employee_id=e.id
              AND work_date=d.work_date ORDER BY result_revision DESC LIMIT 1
        ) r ON true
        LEFT JOIN leaves l ON l.employee_id=e.id AND l.leave_date=d.work_date
        WHERE e.tenant_id=company GROUP BY e.tenant_id,e.id,o.tenant_id,o.id
    )
    SELECT employee_id,employee_no,employee_name,organization_id,organization_code,organization_name,employed_days,
           CASE WHEN NOT unknown_work THEN worked_days END, CASE WHEN NOT unknown_work THEN actual_millis END, leave_days,
           CASE WHEN NOT (unknown_work OR pre_calc3) THEN approved_overtime_millis END,
           CASE WHEN NOT (unknown_work OR pre_calc3) THEN unapproved_extra_millis END,
           CASE WHEN NOT unknown_work THEN night_work_millis END,
           CASE WHEN NOT unknown_work THEN holiday_work_millis END,
           late_count,late_millis,early_leave_count,early_leave_millis,absence_days
    FROM totals
$$;
REVOKE ALL ON FUNCTION tna.month_close_export_values(uuid,date,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tna.month_close_export_values(uuid,date,timestamptz) TO tna_app;

CREATE OR REPLACE FUNCTION tna.capture_attendance_month_close() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    INSERT INTO tna.attendance_month_export_row(tenant_id,closure_id,employee_id,employee_no,employee_name,organization_id,
        organization_code,organization_name,employed_days,worked_days,actual_millis,leave_days,payroll_captured,
        approved_overtime_millis,unapproved_extra_millis,night_work_millis,holiday_work_millis,late_count,late_millis,
        early_leave_count,early_leave_millis,absence_days)
        SELECT NEW.tenant_id,NEW.id,v.employee_id,v.employee_no,v.employee_name,v.organization_id,v.organization_code,
               v.organization_name,v.employed_days,v.worked_days,v.actual_millis,v.leave_days,true,
               v.approved_overtime_millis,v.unapproved_extra_millis,v.night_work_millis,v.holiday_work_millis,v.late_count,
               v.late_millis,v.early_leave_count,v.early_leave_millis,v.absence_days
        FROM tna.month_close_export_values(NEW.tenant_id,NEW.month_start) v;
    INSERT INTO tna.attendance_month_selective_member
        SELECT NEW.tenant_id,NEW.id,s.* FROM tna.selective_close_members(NEW.tenant_id,NEW.id,NEW.month_start,NULL) s;
    RETURN NEW;
END $$;
