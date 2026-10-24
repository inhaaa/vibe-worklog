CREATE TABLE tna.work_policy_version (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),
    id uuid NOT NULL,
    revision integer NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    monday_week_reviewed boolean NOT NULL,
    rule_version varchar(80) NOT NULL DEFAULT 'CALC-1',
    basis varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,revision),
    FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK(revision>0 AND (valid_to IS NULL OR valid_to>valid_from)),
    CHECK(rule_version='CALC-1' AND btrim(basis)<>'')
);
CREATE TABLE tna.employment_rule_assessment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    revision integer NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    general_rule_reviewed boolean NOT NULL,
    basis varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,employee_id,revision),
    FOREIGN KEY(tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY(tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK(revision>0 AND (valid_to IS NULL OR valid_to>valid_from)),
    CHECK(btrim(basis)<>'')
);
CREATE TABLE tna.calculation_run (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    from_date date NOT NULL,
    to_date date NOT NULL,
    expanded_from date NOT NULL,
    expanded_to date NOT NULL,
    engine_version varchar(80) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'RUNNING',
    started_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamptz(3),
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,id,employee_id),
    FOREIGN KEY(tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY(tenant_id,requested_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK(from_date<=to_date AND expanded_from<=from_date AND expanded_to>=to_date),
    CHECK(engine_version='CALC-1' AND status IN ('RUNNING','COMPLETED','FAILED')),
    CHECK((status='RUNNING')=(finished_at IS NULL))
);
CREATE TABLE tna.day_result (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date NOT NULL,
    result_revision bigint NOT NULL,
    input_revision bigint NOT NULL,
    review_revision bigint NOT NULL,
    source_fingerprint char(64) NOT NULL,
    policy_version_id uuid,
    assessment_id uuid,
    rule_version varchar(80) NOT NULL,
    status varchar(30) NOT NULL,
    scheduled_millis bigint,
    presence_millis bigint,
    actual_work_millis bigint,
    confirmed_break_millis bigint,
    outside_schedule_millis bigint,
    over_8h_millis bigint,
    night_work_millis bigint,
    holiday_work_millis bigint,
    legal_overtime_millis bigint,
    leave_days integer NOT NULL,
    reason_codes jsonb NOT NULL,
    input_refs jsonb NOT NULL,
    calculated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,employee_id,work_date,result_revision),
    FOREIGN KEY(tenant_id,run_id,employee_id) REFERENCES tna.calculation_run(tenant_id,id,employee_id),
    FOREIGN KEY(tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY(tenant_id,policy_version_id) REFERENCES tna.work_policy_version(tenant_id,id),
    FOREIGN KEY(tenant_id,assessment_id) REFERENCES tna.employment_rule_assessment(tenant_id,id),
    CHECK(result_revision>0 AND input_revision>=0 AND review_revision>=0),
    CHECK(source_fingerprint ~ '^[0-9a-f]{64}$' AND rule_version='CALC-1'),
    CHECK(status IN ('CALCULATED','REVIEW_REQUIRED','NO_SCHEDULED_WORK')),
    CHECK(legal_overtime_millis IS NULL AND leave_days IN (0,1)),
    CHECK((scheduled_millis IS NULL OR scheduled_millis>=0) AND (presence_millis IS NULL OR presence_millis>=0)
      AND (actual_work_millis IS NULL OR actual_work_millis>=0) AND (confirmed_break_millis IS NULL OR confirmed_break_millis>=0)
      AND (outside_schedule_millis IS NULL OR outside_schedule_millis>=0) AND (over_8h_millis IS NULL OR over_8h_millis>=0)
      AND (night_work_millis IS NULL OR night_work_millis>=0) AND (holiday_work_millis IS NULL OR holiday_work_millis>=0)),
    CHECK(jsonb_typeof(reason_codes)='array' AND jsonb_typeof(input_refs)='array')
);
CREATE TABLE tna.report_snapshot (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    requested_by uuid NOT NULL,
    employee_id uuid NOT NULL,
    from_date date NOT NULL,
    to_date date NOT NULL,
    purpose varchar(20) NOT NULL DEFAULT 'REVIEW',
    as_of timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    filters jsonb NOT NULL,
    result_refs jsonb NOT NULL,
    workbook bytea NOT NULL,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,requested_by) REFERENCES tna.tenant_account(tenant_id,id),
    FOREIGN KEY(tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    CHECK(from_date<=to_date AND purpose='REVIEW'),
    CHECK(jsonb_typeof(filters)='object' AND jsonb_typeof(result_refs)='array'),
    CHECK(octet_length(workbook)>0 AND octet_length(workbook)<=10485760)
);

ALTER TABLE tna.work_policy_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.work_policy_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.work_policy_version TO tna_migration,tna_app
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.work_policy_version FROM PUBLIC;
GRANT SELECT,INSERT ON tna.work_policy_version TO tna_app;

ALTER TABLE tna.employment_rule_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employment_rule_assessment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.employment_rule_assessment TO tna_migration,tna_app
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.employment_rule_assessment FROM PUBLIC;
GRANT SELECT,INSERT ON tna.employment_rule_assessment TO tna_app;

ALTER TABLE tna.calculation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.calculation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.calculation_run TO tna_migration,tna_app
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.calculation_run FROM PUBLIC;
GRANT SELECT,INSERT ON tna.calculation_run TO tna_app;

ALTER TABLE tna.day_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.day_result FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.day_result TO tna_migration,tna_app
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.day_result FROM PUBLIC;
GRANT SELECT,INSERT ON tna.day_result TO tna_app;

ALTER TABLE tna.report_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.report_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.report_snapshot TO tna_migration,tna_app
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.report_snapshot FROM PUBLIC;
GRANT SELECT,INSERT ON tna.report_snapshot TO tna_app;

GRANT UPDATE(status,finished_at) ON tna.calculation_run TO tna_app;
