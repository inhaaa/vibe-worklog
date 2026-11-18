CREATE TABLE tna.checkout_follow_up (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    requested_by uuid NOT NULL,
    requested_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
    last_request_id uuid,
    resolved_result_id uuid,
    resolved_at timestamptz(3),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES tna.employee(tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES tna.tenant_account(tenant_id, id),
    FOREIGN KEY (tenant_id, last_request_id, employee_id) REFERENCES tna.approval_request(tenant_id, id, target_employee_id),
    FOREIGN KEY (tenant_id, resolved_result_id) REFERENCES tna.day_result(tenant_id, id),
    CHECK ((resolved_at IS NULL) = (resolved_result_id IS NULL)),
    CHECK (resolved_at IS NULL OR (last_request_id IS NOT NULL AND resolved_at >= requested_at))
);
CREATE UNIQUE INDEX checkout_follow_up_open_day ON tna.checkout_follow_up(tenant_id, employee_id, work_date)
    WHERE resolved_at IS NULL;

ALTER TABLE tna.checkout_follow_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.checkout_follow_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.checkout_follow_up TO tna_migration,tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.checkout_follow_up FROM PUBLIC;
GRANT SELECT, INSERT ON tna.checkout_follow_up TO tna_app;
GRANT UPDATE(last_request_id, resolved_result_id, resolved_at) ON tna.checkout_follow_up TO tna_app;
