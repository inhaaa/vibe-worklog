CREATE TABLE tna.request_form (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),
    id uuid NOT NULL, code varchar(8) NOT NULL, kind varchar(30) NOT NULL, version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,id), UNIQUE (tenant_id,code),
    CHECK (code ~ '^[A-Z0-9]{2,8}$'), CHECK (version >= 0),
    CHECK (kind IN ('LEAVE_DAY','CANCEL_LEAVE','ATTENDANCE_CORRECTION'))
);
CREATE TABLE tna.request_form_version (
    tenant_id uuid NOT NULL, id uuid NOT NULL, form_id uuid NOT NULL, revision bigint NOT NULL,
    effective_from date NOT NULL, enabled boolean NOT NULL, name varchar(120) NOT NULL,
    route_version_id uuid, reason varchar(500) NOT NULL, created_by uuid,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id), UNIQUE (tenant_id,form_id,revision),
    FOREIGN KEY (tenant_id,form_id) REFERENCES tna.request_form(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY (tenant_id,route_version_id) REFERENCES tna.approval_route_version(tenant_id,id),
    CHECK (revision >= 1 AND btrim(name) <> '' AND btrim(reason) <> ''),
    CHECK (created_by IS NOT NULL OR (revision=1 AND effective_from=DATE '0001-01-01'))
);
CREATE INDEX request_form_at_date ON tna.request_form_version(tenant_id,form_id,effective_from DESC,revision DESC);

CREATE FUNCTION tna.seed_request_forms(company uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    PERFORM set_config('app.tenant_id',company::text,true);
    WITH inserted AS (
        INSERT INTO tna.request_form(tenant_id,id,code,kind)
        VALUES (company,gen_random_uuid(),'L01','LEAVE_DAY'),(company,gen_random_uuid(),'L02','CANCEL_LEAVE'),
               (company,gen_random_uuid(),'A01','ATTENDANCE_CORRECTION')
        RETURNING tenant_id,id,code
    )
    INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,reason)
    SELECT tenant_id,gen_random_uuid(),id,1,DATE '0001-01-01',true,
           CASE code WHEN 'L01' THEN '휴가 신청서' WHEN 'L02' THEN '휴가 취소 신청서' ELSE '출퇴근 정정 신청서' END,
           '기존 결재선 적용' FROM inserted;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END $$;
REVOKE ALL ON FUNCTION tna.seed_request_forms(uuid) FROM PUBLIC;
CREATE FUNCTION tna.provision_request_forms() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN PERFORM tna.seed_request_forms(NEW.id); RETURN NEW; END $$;
REVOKE ALL ON FUNCTION tna.provision_request_forms() FROM PUBLIC;
CREATE TRIGGER request_forms_provision AFTER INSERT ON platform.tenant_registry
FOR EACH ROW EXECUTE FUNCTION tna.provision_request_forms();

CREATE POLICY request_form_migration_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $$ DECLARE company uuid;
BEGIN
    FOR company IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM tna.seed_request_forms(company);
    END LOOP;
END $$;
DROP POLICY request_form_migration_registry_scan ON platform.tenant_registry;

ALTER TABLE tna.request_form ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.request_form FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.request_form_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.request_form_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.request_form TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.request_form_version TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.request_form,tna.request_form_version FROM PUBLIC;
GRANT SELECT ON tna.request_form,tna.request_form_version TO tna_app;
GRANT INSERT(tenant_id,id,code,kind),UPDATE(version) ON tna.request_form TO tna_app;
GRANT INSERT(tenant_id,id,form_id,revision,effective_from,enabled,name,route_version_id,reason,created_by)
    ON tna.request_form_version TO tna_app;
