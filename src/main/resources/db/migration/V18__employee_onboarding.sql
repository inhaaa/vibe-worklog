CREATE TABLE tna.employee_onboarding (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    display_name varchar(120) NOT NULL,
    joined_on date NOT NULL,
    left_on date,
    organization_id uuid,
    schedule_version_id uuid,
    state varchar(12) NOT NULL DEFAULT 'PLANNED',
    version bigint NOT NULL DEFAULT 0,
    employee_id uuid,
    organization_assignment_id uuid,
    schedule_assignment_id uuid,
    organization_code varchar(40),
    organization_name varchar(120),
    schedule_template_name varchar(120),
    schedule_revision integer,
    decided_by uuid,
    decided_at timestamptz(3),
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,organization_id) REFERENCES tna.organization(tenant_id,id),
    FOREIGN KEY (tenant_id,schedule_version_id) REFERENCES tna.schedule_version(tenant_id,id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,organization_assignment_id) REFERENCES tna.employee_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,schedule_assignment_id) REFERENCES tna.schedule_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY (tenant_id,decided_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (btrim(display_name) <> '' AND version >= 0),
    CHECK (joined_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CHECK (left_on IS NULL OR left_on BETWEEN joined_on AND DATE '9999-12-31'),
    CHECK (state IN ('PLANNED','CONFIRMED','WITHDRAWN')),
    CHECK ((state = 'PLANNED' AND num_nonnulls(decided_by,decided_at) = 0)
        OR (state <> 'PLANNED' AND num_nonnulls(decided_by,decided_at) = 2)),
    CHECK ((state = 'CONFIRMED' AND organization_id IS NOT NULL AND schedule_version_id IS NOT NULL
        AND num_nonnulls(employee_id,organization_assignment_id,schedule_assignment_id,organization_code,
            organization_name,schedule_template_name,schedule_revision) = 7 AND schedule_revision > 0)
        OR (state <> 'CONFIRMED' AND num_nonnulls(employee_id,organization_assignment_id,schedule_assignment_id,
            organization_code,organization_name,schedule_template_name,schedule_revision) = 0))
);

CREATE UNIQUE INDEX employee_onboarding_confirmed_employee
    ON tna.employee_onboarding(tenant_id,employee_id) WHERE employee_id IS NOT NULL;
CREATE INDEX employee_onboarding_directory ON tna.employee_onboarding(tenant_id,joined_on,id);

ALTER TABLE tna.employee_onboarding ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_onboarding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.employee_onboarding TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.employee_onboarding FROM PUBLIC;
GRANT SELECT ON tna.employee_onboarding TO tna_app;
GRANT INSERT (tenant_id,id,display_name,joined_on,left_on,organization_id,schedule_version_id,created_by,updated_by)
    ON tna.employee_onboarding TO tna_app;
GRANT UPDATE (display_name,joined_on,left_on,organization_id,schedule_version_id,state,version,employee_id,
    organization_assignment_id,schedule_assignment_id,organization_code,organization_name,schedule_template_name,
    schedule_revision,decided_by,decided_at,updated_by,updated_at) ON tna.employee_onboarding TO tna_app;
