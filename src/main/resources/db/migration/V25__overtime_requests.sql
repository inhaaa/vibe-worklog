ALTER TABLE tna.request_form DROP CONSTRAINT request_form_kind_check,
    ADD CONSTRAINT request_form_kind_check CHECK (kind IN
        ('LEAVE_DAY','CANCEL_LEAVE','ATTENDANCE_CORRECTION','OVERTIME','CANCEL_OVERTIME'));

ALTER TABLE tna.approval_request DROP CONSTRAINT approval_request_kind_valid,
    ADD CONSTRAINT approval_request_kind_valid CHECK (kind IN
        ('LEAVE_DAY','CANCEL_LEAVE','ATTENDANCE_CORRECTION','OVERTIME','CANCEL_OVERTIME')),
    ALTER COLUMN attendance_type_version_id DROP NOT NULL,
    ADD CONSTRAINT request_policy_required CHECK ((kind IN ('OVERTIME','CANCEL_OVERTIME')) = (attendance_type_version_id IS NULL)),
    ADD CONSTRAINT overtime_request_shape CHECK (kind NOT IN ('OVERTIME','CANCEL_OVERTIME') OR
        (from_date=to_date AND NOT exceptional AND leave_bucket_id IS NULL AND form_version_id IS NOT NULL
         AND (kind='CANCEL_OVERTIME')=(original_request_id IS NOT NULL)));

CREATE UNIQUE INDEX overtime_cancel_pending ON tna.approval_request(tenant_id,original_request_id)
    WHERE kind='CANCEL_OVERTIME' AND status='PENDING';

ALTER TABLE tna.request_effect DROP CONSTRAINT request_effect_kind_valid,
    ADD CONSTRAINT request_effect_kind_valid CHECK (effect_kind IN
        ('LEAVE','CORRECTION','CANCEL','OVERTIME','CANCEL_OVERTIME'));

CREATE TABLE tna.overtime_request_detail (
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    work_date date NOT NULL,
    start_time time(0) NOT NULL,
    end_time time(0) NOT NULL,
    requested_minutes integer NOT NULL,
    schedule_version_id uuid NOT NULL,
    code_version_id uuid NOT NULL,
    calendar_version_id uuid NOT NULL,
    basis_token varchar(64) NOT NULL,
    PRIMARY KEY (tenant_id,request_id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request(tenant_id,id),
    FOREIGN KEY (tenant_id,schedule_version_id) REFERENCES tna.schedule_version(tenant_id,id),
    FOREIGN KEY (tenant_id,code_version_id) REFERENCES tna.work_time_code_version(tenant_id,id),
    FOREIGN KEY (tenant_id,calendar_version_id) REFERENCES tna.calendar_version(tenant_id,id),
    CHECK (start_time < end_time AND requested_minutes > 0 AND requested_minutes <= extract(epoch FROM end_time-start_time)/60),
    CHECK (basis_token ~ '^[a-f0-9]{64}$')
);
ALTER TABLE tna.overtime_request_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.overtime_request_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.overtime_request_detail TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.overtime_request_detail FROM PUBLIC;
GRANT SELECT,INSERT ON tna.overtime_request_detail TO tna_app;

-- Customer-defined form codes (including O01 and O02) are preserved during upgrade.
CREATE FUNCTION tna.provision_overtime_forms(company uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous_tenant text := current_setting('app.tenant_id',true);
        entry record; form_id uuid; candidate text; suffix integer;
BEGIN
    PERFORM set_config('app.tenant_id',company::text,true);
    FOR entry IN SELECT * FROM (VALUES ('O01','OVERTIME','연장근무 신청서'),
                                       ('O02','CANCEL_OVERTIME','연장근무 취소 신청서')) f(code,kind,name)
    LOOP
        candidate := entry.code;
        suffix := 0;
        WHILE EXISTS (SELECT 1 FROM tna.request_form WHERE tenant_id=company AND code=candidate) LOOP
            suffix := suffix+1;
            candidate := 'OT'||suffix::text;
        END LOOP;
        form_id := gen_random_uuid();
        INSERT INTO tna.request_form(tenant_id,id,code,kind) VALUES (company,form_id,candidate,entry.kind);
        INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,reason)
        VALUES (company,gen_random_uuid(),form_id,1,DATE '0001-01-01',true,entry.name,'기존 결재선 적용');
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END $$;
REVOKE ALL ON FUNCTION tna.provision_overtime_forms(uuid) FROM PUBLIC;
CREATE OR REPLACE FUNCTION tna.provision_request_forms() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    PERFORM tna.seed_request_forms(NEW.id);
    PERFORM tna.provision_overtime_forms(NEW.id);
    RETURN NEW;
END $$;
CREATE POLICY overtime_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $$ DECLARE company uuid;
BEGIN
    FOR company IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM tna.provision_overtime_forms(company);
    END LOOP;
END $$;
DROP POLICY overtime_registry_scan ON platform.tenant_registry;
