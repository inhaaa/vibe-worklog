-- Each existing pending request keeps its original one-stage decision contract.
ALTER TABLE tna.approval_request
    ADD COLUMN form_version_id uuid,
    ADD COLUMN current_step smallint NOT NULL DEFAULT 1,
    ADD COLUMN total_steps smallint NOT NULL DEFAULT 1,
    ADD CHECK (current_step BETWEEN 1 AND total_steps AND total_steps BETWEEN 1 AND 2),
    ADD FOREIGN KEY (tenant_id,form_version_id) REFERENCES tna.request_form_version(tenant_id,id),
    ADD UNIQUE (tenant_id,id,form_version_id);
ALTER TABLE tna.request_form_version ADD UNIQUE (tenant_id,id,route_version_id);
ALTER TABLE tna.approval_route_snapshot
    ALTER COLUMN assignment_id DROP NOT NULL,
    ALTER COLUMN assignment_version DROP NOT NULL,
    ADD COLUMN form_version_id uuid,
    ADD COLUMN organization_path jsonb,
    ADD CHECK ((assignment_id IS NULL) = (assignment_version IS NULL)),
    ADD CHECK ((assignment_id IS NULL) = (form_version_id IS NOT NULL)),
    ADD CHECK ((form_version_id IS NULL) = (organization_path IS NULL)),
    ADD FOREIGN KEY (tenant_id,request_id,form_version_id)
        REFERENCES tna.approval_request(tenant_id,id,form_version_id),
    ADD FOREIGN KEY (tenant_id,form_version_id,route_version_id)
        REFERENCES tna.request_form_version(tenant_id,id,route_version_id);

CREATE TABLE tna.request_approval_step (
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    step_number smallint NOT NULL CHECK (step_number BETWEEN 1 AND 2),
    role text NOT NULL,
    original_approver_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    status varchar(12) NOT NULL CHECK (status IN ('ACTIVE','WAITING','APPROVED','REJECTED','CLOSED')),
    decided_by uuid,
    decided_at timestamptz(3),
    reason text,
    PRIMARY KEY (tenant_id,request_id,step_number),
    UNIQUE (tenant_id,request_id,approver_id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request(tenant_id,id),
    FOREIGN KEY (tenant_id,original_approver_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,approver_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,decided_by) REFERENCES tna.employee(tenant_id,id),
    CHECK ((status IN ('APPROVED','REJECTED'))=(decided_by IS NOT NULL)),
    CHECK ((decided_by IS NULL)=(decided_at IS NULL) AND (decided_by IS NULL)=(reason IS NULL))
);
CREATE UNIQUE INDEX request_approval_active ON tna.request_approval_step(tenant_id,request_id) WHERE status='ACTIVE';
ALTER TABLE tna.request_approval_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.request_approval_step FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.request_approval_step TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.request_approval_step FROM PUBLIC;
GRANT SELECT, INSERT ON tna.request_approval_step TO tna_app;
GRANT UPDATE(approver_id,status,decided_by,decided_at,reason) ON tna.request_approval_step TO tna_app;
GRANT UPDATE(current_step) ON tna.approval_request TO tna_app;
