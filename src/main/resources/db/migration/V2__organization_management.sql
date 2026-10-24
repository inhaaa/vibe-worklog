CREATE EXTENSION btree_gist WITH SCHEMA tna;

CREATE TABLE tna.organization (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    code varchar(40) NOT NULL,
    name varchar(120) NOT NULL,
    parent_id uuid,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES tna.organization (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT organization_code_not_blank CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT organization_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT organization_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT organization_version_nonnegative CHECK (version >= 0)
);

-- V1 creation facts are unknown; preserve them as NULL instead of fabricating history.
ALTER TABLE tna.employee
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN created_at timestamptz(3),
    ADD COLUMN created_by uuid,
    ADD COLUMN updated_at timestamptz(3),
    ADD COLUMN updated_by uuid,
    ADD CONSTRAINT employee_created_by_same_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    ADD CONSTRAINT employee_updated_by_same_tenant
        FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    ADD CONSTRAINT employee_version_nonnegative CHECK (version >= 0);

CREATE TABLE tna.employee_assignment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    reason varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES tna.employee (tenant_id, id),
    FOREIGN KEY (tenant_id, organization_id) REFERENCES tna.organization (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT assignment_dates_valid CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT assignment_reason_not_blank CHECK (btrim(reason) <> ''),
    CONSTRAINT assignment_version_nonnegative CHECK (version >= 0),
    CONSTRAINT assignment_period_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        employee_id tna.gist_uuid_ops WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&
    )
);

CREATE TABLE tna.org_audit_event (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    command_id uuid NOT NULL,
    actor_account_id uuid NOT NULL,
    action varchar(80) NOT NULL,
    target_kind varchar(40) NOT NULL,
    target_id uuid NOT NULL,
    input_hash char(64) NOT NULL,
    reason varchar(500) NOT NULL,
    before_state jsonb NOT NULL,
    after_state jsonb NOT NULL,
    request_trace_id uuid NOT NULL,
    occurred_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, actor_account_id) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT org_audit_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT org_audit_target_kind_not_blank CHECK (btrim(target_kind) <> ''),
    CONSTRAINT org_audit_input_hash_valid CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT org_audit_reason_not_blank CHECK (btrim(reason) <> '')
);

ALTER TABLE tna.organization ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.organization FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_assignment FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.org_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.org_audit_event FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tna.organization
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON tna.employee_assignment
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON tna.org_audit_event
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON tna.organization, tna.employee_assignment, tna.org_audit_event FROM PUBLIC;
GRANT SELECT, INSERT ON tna.organization, tna.employee_assignment, tna.org_audit_event TO tna_app;
GRANT UPDATE (name, parent_id, active, version, updated_at, updated_by) ON tna.organization TO tna_app;
GRANT UPDATE (valid_to, version, updated_at, updated_by) ON tna.employee_assignment TO tna_app;
GRANT INSERT ON tna.employee TO tna_app;
GRANT UPDATE (display_name, joined_on, left_on, active, version, updated_at, updated_by)
    ON tna.employee TO tna_app;
GRANT SELECT (tenant_id, id, employee_id, active, auth_version) ON tna.tenant_account TO tna_app;
GRANT UPDATE (auth_version) ON tna.tenant_account TO tna_app;
GRANT SELECT ON tna.role_grant TO tna_app;
