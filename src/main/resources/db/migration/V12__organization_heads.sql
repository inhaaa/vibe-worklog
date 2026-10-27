CREATE TABLE tna.organization_head_assignment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    superseded_at timestamptz(3),
    reason varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,organization_id) REFERENCES tna.organization (tenant_id,id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT organization_head_dates_valid CHECK (valid_to IS NULL OR valid_to>valid_from),
    CONSTRAINT organization_head_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT organization_head_version_valid CHECK (version>=0),
    CONSTRAINT organization_head_period_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        organization_id tna.gist_uuid_ops WITH =,
        daterange(valid_from,valid_to,'[)') WITH &&
    ) WHERE (superseded_at IS NULL)
);

ALTER TABLE tna.organization_head_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.organization_head_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.organization_head_assignment
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

REVOKE ALL ON tna.organization_head_assignment FROM PUBLIC;
GRANT SELECT,INSERT ON tna.organization_head_assignment TO tna_app;
GRANT UPDATE (valid_to,superseded_at,version,updated_at,updated_by)
    ON tna.organization_head_assignment TO tna_app;
