CREATE TABLE tna.employee_personal_details (
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    admission_type varchar(20) NOT NULL DEFAULT 'UNKNOWN',
    recognized_months integer,
    group_joined_on date,
    english_name varchar(120),
    marital_status varchar(10) NOT NULL DEFAULT 'UNKNOWN',
    married_on date,
    veteran_status varchar(10) NOT NULL DEFAULT 'UNKNOWN',
    disability_status varchar(10) NOT NULL DEFAULT 'UNKNOWN',
    military_status varchar(20) NOT NULL DEFAULT 'UNKNOWN',
    military_branch varchar(60),
    mobile_phone varchar(40),
    postal_code varchar(20),
    address varchar(200),
    address_detail varchar(200),
    contact_email varchar(254),
    version bigint NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,employee_id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (version>=1),
    CHECK (admission_type IN ('UNKNOWN','NEW','EXPERIENCED','TRANSFER')),
    CHECK (recognized_months BETWEEN 0 AND 1200),
    CHECK (marital_status IN ('UNKNOWN','YES','NO')),
    CHECK (married_on IS NULL OR marital_status='YES'),
    CHECK (veteran_status IN ('UNKNOWN','YES','NO')),
    CHECK (disability_status IN ('UNKNOWN','YES','NO')),
    CHECK (military_status IN ('UNKNOWN','COMPLETED','EXEMPT','PENDING','NOT_APPLICABLE')),
    CHECK (group_joined_on IS NULL OR group_joined_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CHECK (married_on IS NULL OR married_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31')
);

-- Minimal append-only private history. No resident-registration-number source is collected.
CREATE TABLE tna.employee_personal_entry (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    kind varchar(20) NOT NULL,
    title varchar(120) NOT NULL,
    detail varchar(200),
    effective_from date NOT NULL,
    effective_to date,
    revision bigint NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,employee_id,revision),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee_personal_details(tenant_id,employee_id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id),
    CHECK (kind IN ('FAMILY','EDUCATION','QUALIFICATION','LANGUAGE')),
    CHECK (btrim(title)<>'' AND revision>=1),
    CHECK (effective_from BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CHECK (effective_to IS NULL OR effective_to BETWEEN effective_from AND DATE '9999-12-31')
);
CREATE INDEX employee_personal_entry_lookup
    ON tna.employee_personal_entry(tenant_id,employee_id,effective_from DESC,revision DESC);

ALTER TABLE tna.employee_personal_details ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_personal_details FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_personal_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee_personal_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.employee_personal_details TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON tna.employee_personal_entry TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.employee_personal_details,tna.employee_personal_entry FROM PUBLIC;
GRANT SELECT,INSERT ON tna.employee_personal_details,tna.employee_personal_entry TO tna_app;
GRANT UPDATE(admission_type,recognized_months,group_joined_on,english_name,marital_status,married_on,
    veteran_status,disability_status,military_status,military_branch,mobile_phone,postal_code,address,
    address_detail,contact_email,version,updated_at,updated_by) ON tna.employee_personal_details TO tna_app;
