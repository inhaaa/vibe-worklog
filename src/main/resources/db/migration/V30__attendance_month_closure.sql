CREATE TABLE tna.attendance_month_closure (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    month_start date NOT NULL CHECK (extract(day FROM month_start) = 1),
    version bigint NOT NULL CHECK (version > 0),
    status varchar(6) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    actor_account_id uuid NOT NULL,
    actor_name varchar(120) NOT NULL CHECK (btrim(actor_name) <> ''),
    changed_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, month_start, version),
    FOREIGN KEY (tenant_id, actor_account_id) REFERENCES tna.tenant_account(tenant_id, id),
    CHECK ((version % 2 = 1) = (status = 'CLOSED'))
);

ALTER TABLE tna.attendance_month_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_month_closure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.attendance_month_closure TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON tna.attendance_month_closure FROM PUBLIC;
GRANT SELECT, INSERT ON tna.attendance_month_closure TO tna_app;
