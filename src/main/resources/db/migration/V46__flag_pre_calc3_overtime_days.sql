-- One-time: days in open months with an approved effective overtime whose latest result predates CALC-3 are flagged so
-- the nightly batch recalculates them before a month close would freeze blank recognized-overtime columns.
CREATE POLICY flag_pre_calc3_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $body$
DECLARE _tenant uuid;
BEGIN
    FOR _tenant IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
        INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
        SELECT DISTINCT _tenant,f.employee_id,od.work_date,0,1,true
        FROM tna.request_effect f
        JOIN tna.approval_request ar ON ar.tenant_id=f.tenant_id AND ar.id=f.request_id
        JOIN tna.overtime_request_detail od ON od.tenant_id=ar.tenant_id AND od.request_id=ar.id
        WHERE f.tenant_id=_tenant AND f.effect_kind='OVERTIME' AND f.effect_status='EFFECTIVE'
          AND ar.kind='OVERTIME' AND ar.status='APPROVED'
          AND od.work_date>=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-62
          AND (SELECT r.rule_version FROM tna.day_result r WHERE r.tenant_id=_tenant AND r.employee_id=f.employee_id
                        AND r.work_date=od.work_date ORDER BY r.result_revision DESC LIMIT 1)<>'CALC-3'
          AND NOT EXISTS (SELECT 1 FROM (
                SELECT DISTINCT ON (c.month_start) c.month_start,c.status FROM tna.attendance_month_closure c
                WHERE c.tenant_id=_tenant ORDER BY c.month_start,c.version DESC) l
              WHERE l.status='CLOSED' AND l.month_start=date_trunc('month',od.work_date::timestamp)::date)
        ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE SET
          review_revision=tna.day_input_revision.review_revision+1,recalculation_required=true;
    END LOOP;
    PERFORM pg_catalog.set_config('app.tenant_id','',true);
END $body$;
DROP POLICY flag_pre_calc3_registry_scan ON platform.tenant_registry;
