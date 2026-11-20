CREATE TABLE tna.work_time_code (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),
    id uuid NOT NULL, code varchar(8) NOT NULL CHECK (code ~ '^[A-Z0-9]{2,8}$'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id,id), UNIQUE (tenant_id,code)
);

CREATE TABLE tna.work_time_code_version (
    tenant_id uuid NOT NULL, id uuid NOT NULL, code_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0), effective_from date NOT NULL,
    enabled boolean NOT NULL, name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    start_time time(0) NOT NULL, end_time time(0) NOT NULL,
    before_start time(0), before_end time(0), after_start time(0), after_end time(0),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''), created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id), UNIQUE (tenant_id,code_id,revision),
    FOREIGN KEY (tenant_id,code_id) REFERENCES tna.work_time_code(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (start_time < end_time),
    CHECK ((before_start IS NULL) = (before_end IS NULL) AND before_start < before_end AND before_end <= start_time),
    CHECK ((after_start IS NULL) = (after_end IS NULL) AND end_time <= after_start AND after_start < after_end)
);
CREATE INDEX work_time_code_effective ON tna.work_time_code_version(tenant_id,code_id,effective_from DESC,revision DESC);

CREATE TABLE tna.work_time_code_break (
    tenant_id uuid NOT NULL, version_id uuid NOT NULL, kind varchar(8) NOT NULL CHECK (kind IN ('REGULAR','BEFORE','AFTER')),
    start_time time(0) NOT NULL, end_time time(0) NOT NULL CHECK (end_time > start_time),
    PRIMARY KEY (tenant_id,version_id,kind,start_time),
    FOREIGN KEY (tenant_id,version_id) REFERENCES tna.work_time_code_version(tenant_id,id)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['work_time_code','work_time_code_version','work_time_code_break'] LOOP
        EXECUTE format('ALTER TABLE tna.%I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE tna.%I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON tna.%I TO tna_app,tna_migration
            USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
    END LOOP;
END $$;
REVOKE ALL ON tna.work_time_code,tna.work_time_code_version,tna.work_time_code_break FROM PUBLIC;
GRANT SELECT ON tna.work_time_code,tna.work_time_code_version,tna.work_time_code_break TO tna_app;
GRANT INSERT(tenant_id,id,code),UPDATE(version) ON tna.work_time_code TO tna_app;
GRANT INSERT(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,end_time,before_start,before_end,
    after_start,after_end,reason,created_by) ON tna.work_time_code_version TO tna_app;
GRANT INSERT(tenant_id,version_id,kind,start_time,end_time) ON tna.work_time_code_break TO tna_app;
