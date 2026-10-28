CREATE TABLE tna.approval_route (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),
    id uuid NOT NULL,
    code varchar(40) NOT NULL,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,code),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (code=btrim(code) AND code<>''),
    CHECK (btrim(name)<>''),
    CHECK (version>=0)
);

CREATE TABLE tna.approval_route_version (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    route_id uuid NOT NULL,
    revision bigint NOT NULL,
    name varchar(120) NOT NULL,
    approver_kind varchar(30) NOT NULL,
    employee_id uuid,
    reason varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,route_id,revision),
    FOREIGN KEY (tenant_id,route_id) REFERENCES tna.approval_route(tenant_id,id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (revision>=1),
    CHECK (btrim(name)<>'' AND btrim(reason)<>''),
    CHECK (approver_kind IN ('DIRECT_EMPLOYEE','ORGANIZATION_HEAD')),
    CHECK ((approver_kind='DIRECT_EMPLOYEE')=(employee_id IS NOT NULL))
);

CREATE TABLE tna.approval_route_assignment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    route_version_id uuid NOT NULL,
    scope_kind varchar(20) NOT NULL,
    employee_id uuid,
    organization_id uuid,
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
    UNIQUE (tenant_id,id,route_version_id),
    FOREIGN KEY (tenant_id,route_version_id) REFERENCES tna.approval_route_version(tenant_id,id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,organization_id) REFERENCES tna.organization(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (scope_kind IN ('EMPLOYEE','ORGANIZATION')),
    CHECK ((scope_kind='EMPLOYEE' AND employee_id IS NOT NULL AND organization_id IS NULL)
        OR (scope_kind='ORGANIZATION' AND organization_id IS NOT NULL AND employee_id IS NULL)),
    CHECK (valid_to IS NULL OR valid_to>valid_from),
    CHECK (btrim(reason)<>'' AND version>=0),
    EXCLUDE USING gist (tenant_id tna.gist_uuid_ops WITH =, employee_id tna.gist_uuid_ops WITH =,
        daterange(valid_from,valid_to,'[)') WITH &&) WHERE (superseded_at IS NULL AND scope_kind='EMPLOYEE'),
    EXCLUDE USING gist (tenant_id tna.gist_uuid_ops WITH =, organization_id tna.gist_uuid_ops WITH =,
        daterange(valid_from,valid_to,'[)') WITH &&) WHERE (superseded_at IS NULL AND scope_kind='ORGANIZATION')
);

CREATE TABLE tna.approval_route_snapshot (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    route_version_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    assignment_version bigint NOT NULL,
    organization_id uuid,
    organization_assignment_id uuid,
    organization_assignment_version bigint,
    organization_head_assignment_id uuid,
    organization_head_assignment_version bigint,
    resolved_approver_employee_id uuid NOT NULL,
    resolved_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,request_id),
    UNIQUE (tenant_id,id,request_id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request(tenant_id,id),
    FOREIGN KEY (tenant_id,route_version_id) REFERENCES tna.approval_route_version(tenant_id,id),
    FOREIGN KEY (tenant_id,assignment_id,route_version_id)
        REFERENCES tna.approval_route_assignment(tenant_id,id,route_version_id),
    FOREIGN KEY (tenant_id,organization_id) REFERENCES tna.organization(tenant_id,id),
    FOREIGN KEY (tenant_id,organization_assignment_id) REFERENCES tna.employee_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,organization_head_assignment_id) REFERENCES tna.organization_head_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,resolved_approver_employee_id) REFERENCES tna.employee(tenant_id,id),
    CHECK (assignment_version>=0),
    CHECK ((organization_id IS NULL)=(organization_assignment_id IS NULL)),
    CHECK ((organization_assignment_id IS NULL)=(organization_assignment_version IS NULL)),
    CHECK ((organization_head_assignment_id IS NULL)=(organization_head_assignment_version IS NULL)),
    CHECK (organization_assignment_version IS NULL OR organization_assignment_version>=0),
    CHECK (organization_head_assignment_version IS NULL OR organization_head_assignment_version>=0),
    CHECK (organization_head_assignment_id IS NULL OR organization_id IS NOT NULL)
);

ALTER TABLE tna.approval_request
    ALTER COLUMN approver_assignment_id DROP NOT NULL,
    ALTER COLUMN approver_assignment_version DROP NOT NULL,
    ADD COLUMN route_snapshot_id uuid,
    ADD CONSTRAINT approval_request_legacy_pair CHECK ((approver_assignment_id IS NULL)=(approver_assignment_version IS NULL)),
    ADD CONSTRAINT approval_request_resolution_xor CHECK ((approver_assignment_id IS NULL)<>(route_snapshot_id IS NULL)),
    ADD CONSTRAINT approval_request_route_snapshot_bound FOREIGN KEY (tenant_id,route_snapshot_id,id)
        REFERENCES tna.approval_route_snapshot(tenant_id,id,request_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE tna.approval_route ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_version FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_assignment FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_route_snapshot FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tna.approval_route TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.approval_route_version TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.approval_route_assignment TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.approval_route_snapshot TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

REVOKE ALL ON tna.approval_route,tna.approval_route_version,tna.approval_route_assignment,tna.approval_route_snapshot FROM PUBLIC;
GRANT SELECT,INSERT ON tna.approval_route,tna.approval_route_version,tna.approval_route_assignment,tna.approval_route_snapshot TO tna_app;
GRANT UPDATE (name,active,version,updated_at,updated_by) ON tna.approval_route TO tna_app;
GRANT UPDATE (valid_to,superseded_at,version,updated_at,updated_by) ON tna.approval_route_assignment TO tna_app;
REVOKE UPDATE (approver_assignment_id,approver_assignment_version) ON tna.approval_request FROM tna_app;
