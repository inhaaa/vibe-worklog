CREATE TABLE tna.calendar_version (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    revision integer NOT NULL,
    valid_from date NOT NULL,
    valid_to date NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,revision),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT calendar_revision_positive CHECK (revision>0),
    CONSTRAINT calendar_dates_valid CHECK (valid_to>valid_from),
    CONSTRAINT calendar_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT calendar_period_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        daterange(valid_from,valid_to,'[)') WITH &&
    )
);

CREATE TABLE tna.calendar_day (
    tenant_id uuid NOT NULL,
    version_id uuid NOT NULL,
    work_date date NOT NULL,
    kind varchar(20) NOT NULL,
    name varchar(120) NOT NULL,
    basis varchar(500) NOT NULL,
    PRIMARY KEY (tenant_id,version_id,work_date),
    FOREIGN KEY (tenant_id,version_id) REFERENCES tna.calendar_version (tenant_id,id),
    CONSTRAINT calendar_day_kind_valid CHECK (kind IN ('WORKDAY','NON_WORKDAY','HOLIDAY')),
    CONSTRAINT calendar_day_name_valid CHECK (btrim(name)<>''),
    CONSTRAINT calendar_day_basis_valid CHECK (btrim(basis)<>'')
);

CREATE TABLE tna.allowed_network (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    network cidr NOT NULL,
    label varchar(120) NOT NULL,
    valid_from timestamptz(3) NOT NULL,
    valid_to timestamptz(3),
    reason varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT allowed_network_label_valid CHECK (btrim(label)<>''),
    CONSTRAINT allowed_network_reason_valid CHECK (btrim(reason)<>''),
    CONSTRAINT allowed_network_dates_valid CHECK (valid_to IS NULL OR valid_to>=valid_from),
    CONSTRAINT allowed_network_version_valid CHECK (version>=0)
);
CREATE UNIQUE INDEX allowed_network_open_unique ON tna.allowed_network (tenant_id,network) WHERE valid_to IS NULL;

CREATE TABLE tna.attendance_event (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    event_kind varchar(10) NOT NULL,
    occurred_at timestamptz(3) NOT NULL,
    received_at timestamptz(3) NOT NULL,
    client_command_id uuid NOT NULL,
    source varchar(20) NOT NULL,
    validated_ip inet NOT NULL,
    network_id uuid NOT NULL,
    assigned_work_date date,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,employee_id,client_command_id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,network_id) REFERENCES tna.allowed_network (tenant_id,id),
    CONSTRAINT attendance_event_kind_valid CHECK (event_kind IN ('IN','OUT')),
    CONSTRAINT attendance_event_source_valid CHECK (source='IP')
);
CREATE INDEX attendance_event_employee_date ON tna.attendance_event (tenant_id,employee_id,assigned_work_date,occurred_at,id);

CREATE TABLE tna.day_input_revision (
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date NOT NULL,
    input_revision bigint NOT NULL DEFAULT 0,
    review_revision bigint NOT NULL DEFAULT 0,
    recalculation_required boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id,employee_id,work_date),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee (tenant_id,id),
    CONSTRAINT attendance_input_revision_valid CHECK (input_revision>=0),
    CONSTRAINT attendance_review_revision_valid CHECK (review_revision>=0)
);

CREATE TABLE tna.work_confirmation (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date NOT NULL,
    confirmed_input_revision bigint NOT NULL,
    review_revision bigint NOT NULL,
    source_fingerprint char(64) NOT NULL,
    presence_as_work boolean NOT NULL,
    breaks_match boolean NOT NULL,
    confirmed_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by uuid NOT NULL,
    break_snapshot jsonb NOT NULL,
    client_command_id uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,employee_id,client_command_id),
    UNIQUE (tenant_id,employee_id,work_date,confirmed_input_revision,review_revision),
    FOREIGN KEY (tenant_id,employee_id,work_date) REFERENCES tna.day_input_revision (tenant_id,employee_id,work_date),
    FOREIGN KEY (tenant_id,confirmed_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT confirmation_input_revision_valid CHECK (confirmed_input_revision>=0),
    CONSTRAINT confirmation_review_revision_valid CHECK (review_revision>0),
    CONSTRAINT confirmation_fingerprint_valid CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT confirmation_assertions_valid CHECK (presence_as_work AND breaks_match),
    CONSTRAINT confirmation_break_snapshot_valid CHECK (jsonb_typeof(break_snapshot)='array')
);

ALTER TABLE tna.calendar_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.calendar_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.calendar_version
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.calendar_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.calendar_day FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.calendar_day
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.allowed_network ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.allowed_network FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.allowed_network
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.attendance_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.attendance_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.attendance_event
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.day_input_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.day_input_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.day_input_revision
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE tna.work_confirmation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.work_confirmation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.work_confirmation
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

REVOKE ALL ON tna.calendar_version,tna.calendar_day,tna.allowed_network,tna.attendance_event,
    tna.day_input_revision,tna.work_confirmation FROM PUBLIC;
GRANT SELECT,INSERT ON tna.calendar_version,tna.calendar_day,tna.allowed_network,
    tna.day_input_revision,tna.work_confirmation TO tna_app;
GRANT UPDATE (valid_to,version) ON tna.allowed_network TO tna_app;
GRANT UPDATE (input_revision,review_revision,recalculation_required) ON tna.day_input_revision TO tna_app;
GRANT INSERT ON tna.attendance_event TO tna_app;
GRANT SELECT (tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
    source,network_id,assigned_work_date) ON tna.attendance_event TO tna_app;
