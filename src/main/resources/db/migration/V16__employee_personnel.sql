CREATE TABLE tna.employee_personnel (
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    birth_date date,
    grade_name varchar(120),
    position_name varchar(120),
    job_name varchar(120),
    photo_content_type varchar(20),
    photo_bytes bytea,
    version bigint NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,employee_id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (version>=1),
    CHECK (birth_date IS NULL OR birth_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CHECK (grade_name IS NULL OR btrim(grade_name)<>''),
    CHECK (position_name IS NULL OR btrim(position_name)<>''),
    CHECK (job_name IS NULL OR btrim(job_name)<>''),
    CHECK ((photo_content_type IS NULL)=(photo_bytes IS NULL)),
    CHECK (photo_content_type IS NULL OR photo_content_type IN ('image/png','image/jpeg')),
    CHECK (photo_bytes IS NULL OR octet_length(photo_bytes) BETWEEN 1 AND 131072)
);

-- An append-only timeline; the highest personnel revision wins for the same effective day.
CREATE TABLE tna.employee_employment_status (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    employment_status varchar(20) NOT NULL,
    effective_from date NOT NULL,
    revision bigint NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,employee_id,revision),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee_personnel(tenant_id,employee_id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (employment_status IN ('WORKING','ON_LEAVE','SUSPENDED','UNKNOWN')),
    CHECK (revision>=1 AND btrim(reason)<>''),
    CHECK (effective_from BETWEEN DATE '0001-01-01' AND DATE '9999-12-31')
);
CREATE INDEX employee_employment_status_effective
    ON tna.employee_employment_status(tenant_id,employee_id,effective_from DESC,revision DESC);

ALTER TABLE tna.employee_personnel ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_personnel FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_employment_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_employment_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.employee_personnel TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.employee_employment_status TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.employee_personnel,tna.employee_employment_status FROM PUBLIC;
GRANT SELECT,INSERT ON tna.employee_personnel,tna.employee_employment_status TO tna_app;
GRANT UPDATE(birth_date,grade_name,position_name,job_name,photo_content_type,photo_bytes,version,updated_at,updated_by)
    ON tna.employee_personnel TO tna_app;
