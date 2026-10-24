CREATE TABLE tna.leave_bucket (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    base_date date NOT NULL,
    usable_from date NOT NULL,
    usable_to date,
    source_reference varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,id,employee_id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT leave_bucket_dates_valid CHECK (usable_to IS NULL OR usable_to>=usable_from),
    CONSTRAINT leave_bucket_source_valid CHECK (btrim(source_reference)<>''),
    CONSTRAINT leave_bucket_version_valid CHECK (version>=0)
);

CREATE TABLE tna.approval_request (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    target_employee_id uuid NOT NULL,
    author_account_id uuid NOT NULL,
    kind varchar(40) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    current_approver_id uuid NOT NULL,
    approver_assignment_id uuid NOT NULL,
    approver_assignment_version bigint NOT NULL,
    submitted_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason varchar(500) NOT NULL,
    predecessor_request_id uuid,
    original_request_id uuid,
    leave_bucket_id uuid,
    from_date date NOT NULL,
    to_date date NOT NULL,
    exceptional boolean NOT NULL DEFAULT false,
    client_command_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,id,target_employee_id),
    UNIQUE (tenant_id,author_account_id,client_command_id),
    FOREIGN KEY (tenant_id,target_employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,author_account_id) REFERENCES tna.tenant_account (tenant_id,id),
    FOREIGN KEY (tenant_id,current_approver_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,approver_assignment_id) REFERENCES tna.approver_assignment (tenant_id,id),
    FOREIGN KEY (tenant_id,predecessor_request_id) REFERENCES tna.approval_request (tenant_id,id),
    FOREIGN KEY (tenant_id,original_request_id) REFERENCES tna.approval_request (tenant_id,id),
    FOREIGN KEY (tenant_id,leave_bucket_id,target_employee_id) REFERENCES tna.leave_bucket (tenant_id,id,employee_id),
    CONSTRAINT approval_request_kind_valid CHECK (kind IN ('LEAVE_DAY','ATTENDANCE_CORRECTION','CANCEL_LEAVE')),
    CONSTRAINT approval_request_status_valid CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT approval_request_dates_valid CHECK (from_date<=to_date),
    CONSTRAINT approval_request_correction_date_valid CHECK (kind<>'ATTENDANCE_CORRECTION' OR from_date=to_date),
    CONSTRAINT approval_request_leave_bucket_required CHECK (kind<>'LEAVE_DAY' OR leave_bucket_id IS NOT NULL),
    CONSTRAINT approval_request_cancel_original_required CHECK (kind<>'CANCEL_LEAVE' OR original_request_id IS NOT NULL),
    CONSTRAINT approval_request_predecessor_not_self CHECK (predecessor_request_id IS NULL OR predecessor_request_id<>id),
    CONSTRAINT approval_request_original_not_self CHECK (original_request_id IS NULL OR original_request_id<>id),
    CONSTRAINT approval_request_approver_not_target CHECK (target_employee_id<>current_approver_id),
    CONSTRAINT approval_request_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT approval_request_versions_valid CHECK (version>=0 AND approver_assignment_version>=0)
);
CREATE UNIQUE INDEX approval_request_pending_correction_unique
    ON tna.approval_request (tenant_id,target_employee_id,from_date)
    WHERE kind='ATTENDANCE_CORRECTION' AND status='PENDING';
CREATE UNIQUE INDEX approval_request_pending_cancel_unique
    ON tna.approval_request (tenant_id,original_request_id)
    WHERE kind='CANCEL_LEAVE' AND status='PENDING';
CREATE INDEX approval_request_current_approver ON tna.approval_request (tenant_id,current_approver_id,status,submitted_at,id);

CREATE TABLE tna.leave_request_day (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    leave_date date NOT NULL,
    quantity_days integer NOT NULL,
    schedule_version_id uuid NOT NULL,
    calendar_version_id uuid NOT NULL,
    zero_reason varchar(500),
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,request_id,leave_date),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request (tenant_id,id),
    FOREIGN KEY (tenant_id,schedule_version_id) REFERENCES tna.schedule_version (tenant_id,id),
    FOREIGN KEY (tenant_id,calendar_version_id) REFERENCES tna.calendar_version (tenant_id,id),
    CONSTRAINT leave_request_day_quantity_valid CHECK (quantity_days IN (0,1)),
    CONSTRAINT leave_request_zero_reason_required CHECK (
        quantity_days<>0 OR (zero_reason IS NOT NULL AND btrim(zero_reason)<>''))
);

CREATE TABLE tna.correction_detail (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    work_date date NOT NULL,
    base_input_revision bigint NOT NULL,
    source_fingerprint char(64) NOT NULL,
    in_action varchar(10) NOT NULL,
    out_action varchar(10) NOT NULL,
    requested_in_at timestamptz(3),
    requested_out_at timestamptz(3),
    actual_breaks jsonb NOT NULL DEFAULT '[]'::jsonb,
    presence_as_work boolean NOT NULL,
    breaks_confirmed boolean NOT NULL,
    before_snapshot jsonb NOT NULL,
    replaces_request_id uuid,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,request_id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request (tenant_id,id),
    FOREIGN KEY (tenant_id,replaces_request_id) REFERENCES tna.approval_request (tenant_id,id),
    CONSTRAINT correction_detail_revision_valid CHECK (base_input_revision>=0),
    CONSTRAINT correction_detail_fingerprint_valid CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT correction_detail_actions_valid CHECK (in_action IN ('KEEP','SET','CLEAR') AND out_action IN ('KEEP','SET','CLEAR')),
    CONSTRAINT correction_detail_in_value_valid CHECK ((in_action='SET')=(requested_in_at IS NOT NULL)),
    CONSTRAINT correction_detail_out_value_valid CHECK ((out_action='SET')=(requested_out_at IS NOT NULL)),
    CONSTRAINT correction_detail_breaks_valid CHECK (jsonb_typeof(actual_breaks)='array'),
    CONSTRAINT correction_detail_before_valid CHECK (jsonb_typeof(before_snapshot)='object')
);

CREATE TABLE tna.request_effect (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date,
    effect_kind varchar(20) NOT NULL,
    effect_status varchar(20) NOT NULL DEFAULT 'EFFECTIVE',
    validated_input_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
    reverses_effect_id uuid,
    supersedes_effect_id uuid,
    effective_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,id,employee_id),
    UNIQUE (tenant_id,request_id,effect_kind),
    UNIQUE (tenant_id,reverses_effect_id),
    UNIQUE (tenant_id,supersedes_effect_id),
    FOREIGN KEY (tenant_id,request_id,employee_id) REFERENCES tna.approval_request (tenant_id,id,target_employee_id),
    FOREIGN KEY (tenant_id,reverses_effect_id) REFERENCES tna.request_effect (tenant_id,id),
    FOREIGN KEY (tenant_id,supersedes_effect_id) REFERENCES tna.request_effect (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT request_effect_kind_valid CHECK (effect_kind IN ('LEAVE','CORRECTION','CANCEL')),
    CONSTRAINT request_effect_status_valid CHECK (effect_status IN ('EFFECTIVE','CANCELED','SUPERSEDED')),
    CONSTRAINT request_effect_work_date_valid CHECK ((effect_kind='CORRECTION')=(work_date IS NOT NULL)),
    CONSTRAINT request_effect_refs_valid CHECK (jsonb_typeof(validated_input_refs)='array'),
    CONSTRAINT request_effect_reversal_not_self CHECK (reverses_effect_id IS NULL OR reverses_effect_id<>id),
    CONSTRAINT request_effect_supersession_not_self CHECK (supersedes_effect_id IS NULL OR supersedes_effect_id<>id)
);
CREATE UNIQUE INDEX request_effect_current_correction_unique ON tna.request_effect (tenant_id,employee_id,work_date)
    WHERE effect_kind='CORRECTION' AND effect_status='EFFECTIVE';

CREATE TABLE tna.leave_ledger (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    bucket_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    entry_kind varchar(20) NOT NULL,
    signed_quantity_days integer NOT NULL,
    effective_on date NOT NULL,
    request_effect_id uuid,
    leave_date date,
    reverses_entry_id uuid,
    source_reference varchar(500),
    reason varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,reverses_entry_id),
    FOREIGN KEY (tenant_id,bucket_id,employee_id) REFERENCES tna.leave_bucket (tenant_id,id,employee_id),
    FOREIGN KEY (tenant_id,request_effect_id,employee_id) REFERENCES tna.request_effect (tenant_id,id,employee_id),
    FOREIGN KEY (tenant_id,reverses_entry_id) REFERENCES tna.leave_ledger (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT leave_ledger_kind_valid CHECK (entry_kind IN ('OPENING','ADJUSTMENT','USE','RESTORE')),
    CONSTRAINT leave_ledger_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT leave_ledger_source_valid CHECK (
        entry_kind NOT IN ('OPENING','ADJUSTMENT') OR (source_reference IS NOT NULL AND btrim(source_reference)<>'')),
    CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (entry_kind='OPENING' AND signed_quantity_days>=0 AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL)
        OR (entry_kind='ADJUSTMENT' AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL)
        OR (entry_kind='USE' AND signed_quantity_days=-1 AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NULL)
        OR (entry_kind='RESTORE' AND signed_quantity_days=1 AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX leave_ledger_opening_unique ON tna.leave_ledger (tenant_id,bucket_id) WHERE entry_kind='OPENING';
CREATE UNIQUE INDEX leave_ledger_use_unique ON tna.leave_ledger (tenant_id,request_effect_id,leave_date) WHERE entry_kind='USE';
CREATE INDEX leave_ledger_bucket_history ON tna.leave_ledger (tenant_id,bucket_id,created_at,id);

CREATE TABLE tna.approval_event (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    event_kind varchar(40) NOT NULL,
    actor_account_id uuid NOT NULL,
    from_status varchar(20),
    to_status varchar(20),
    reason varchar(500) NOT NULL,
    previous_approver_id uuid,
    new_approver_id uuid,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request (tenant_id,id),
    FOREIGN KEY (tenant_id,actor_account_id) REFERENCES tna.tenant_account (tenant_id,id),
    FOREIGN KEY (tenant_id,previous_approver_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,new_approver_id) REFERENCES tna.employee (tenant_id,id),
    CONSTRAINT approval_event_kind_valid CHECK (btrim(event_kind)<>''),
    CONSTRAINT approval_event_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT approval_event_from_status_valid CHECK (from_status IS NULL OR from_status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT approval_event_to_status_valid CHECK (to_status IS NULL OR to_status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN'))
);

ALTER TABLE tna.leave_bucket ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_bucket FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_bucket
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.leave_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_ledger
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.approval_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.approval_request
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.leave_request_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_request_day FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_request_day
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.correction_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.correction_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.correction_detail
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.request_effect ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.request_effect FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.request_effect
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.approval_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approval_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.approval_event
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

REVOKE ALL ON tna.leave_bucket,tna.leave_ledger,tna.approval_request,tna.leave_request_day,
    tna.correction_detail,tna.request_effect,tna.approval_event FROM PUBLIC;
GRANT SELECT,INSERT ON tna.leave_bucket,tna.leave_ledger,tna.approval_request,tna.leave_request_day,
    tna.correction_detail,tna.request_effect,tna.approval_event TO tna_app;
GRANT UPDATE (version) ON tna.leave_bucket TO tna_app;
GRANT UPDATE (status,current_approver_id,approver_assignment_id,approver_assignment_version,version)
    ON tna.approval_request TO tna_app;
GRANT UPDATE (effect_status) ON tna.request_effect TO tna_app;
