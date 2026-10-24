CREATE TABLE tna.schedule_template (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    code varchar(40) NOT NULL,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT schedule_template_code_valid CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT schedule_template_name_valid CHECK (btrim(name) <> ''),
    CONSTRAINT schedule_template_version_valid CHECK (version >= 0)
);

CREATE TABLE tna.schedule_version (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    template_id uuid NOT NULL,
    revision integer NOT NULL,
    valid_from date NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, template_id, revision),
    FOREIGN KEY (tenant_id, template_id) REFERENCES tna.schedule_template (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT schedule_version_revision_positive CHECK (revision > 0),
    CONSTRAINT schedule_version_reason_valid CHECK (btrim(reason) <> '')
);

CREATE TABLE tna.schedule_day (
    tenant_id uuid NOT NULL,
    version_id uuid NOT NULL,
    weekday integer NOT NULL,
    workday boolean NOT NULL,
    start_time time(3),
    end_time time(3),
    PRIMARY KEY (tenant_id, version_id, weekday),
    FOREIGN KEY (tenant_id, version_id) REFERENCES tna.schedule_version (tenant_id, id),
    CONSTRAINT schedule_day_weekday_valid CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT schedule_day_interval_valid CHECK (
        (workday AND start_time IS NOT NULL AND end_time IS NOT NULL
            AND start_time < end_time AND end_time < TIME '24:00')
        OR (NOT workday AND start_time IS NULL AND end_time IS NULL)
    )
);

CREATE TABLE tna.schedule_break (
    tenant_id uuid NOT NULL,
    version_id uuid NOT NULL,
    weekday integer NOT NULL,
    sequence integer NOT NULL,
    start_time time(3) NOT NULL,
    end_time time(3) NOT NULL,
    PRIMARY KEY (tenant_id, version_id, weekday, sequence),
    FOREIGN KEY (tenant_id, version_id, weekday) REFERENCES tna.schedule_day (tenant_id, version_id, weekday),
    CONSTRAINT schedule_break_sequence_positive CHECK (sequence > 0),
    CONSTRAINT schedule_break_interval_valid CHECK (start_time < end_time AND end_time < TIME '24:00'),
    CONSTRAINT schedule_break_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        version_id tna.gist_uuid_ops WITH =,
        weekday tna.gist_int4_ops WITH =,
        tsrange(DATE '2000-01-01' + start_time, DATE '2000-01-01' + end_time, '[)') WITH &&
    )
);

CREATE TABLE tna.schedule_assignment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    schedule_version_id uuid NOT NULL,
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
    FOREIGN KEY (tenant_id, schedule_version_id) REFERENCES tna.schedule_version (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT schedule_assignment_dates_valid CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT schedule_assignment_reason_valid CHECK (btrim(reason) <> ''),
    CONSTRAINT schedule_assignment_version_valid CHECK (version >= 0),
    CONSTRAINT schedule_assignment_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        employee_id tna.gist_uuid_ops WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&
    )
);

ALTER TABLE tna.schedule_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_template FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_version FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_day FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_break ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_break FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.schedule_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tna.schedule_template
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.schedule_version
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.schedule_day
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.schedule_break
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.schedule_assignment
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON tna.schedule_template, tna.schedule_version, tna.schedule_day,
    tna.schedule_break, tna.schedule_assignment FROM PUBLIC;
GRANT SELECT, INSERT ON tna.schedule_template, tna.schedule_version, tna.schedule_day,
    tna.schedule_break, tna.schedule_assignment TO tna_app;
GRANT UPDATE (name, active, version, updated_at, updated_by) ON tna.schedule_template TO tna_app;
GRANT UPDATE (valid_to, version, updated_at, updated_by) ON tna.schedule_assignment TO tna_app;
