CREATE SCHEMA platform AUTHORIZATION tna_migration;
CREATE SCHEMA tna AUTHORIZATION tna_migration;

REVOKE ALL ON SCHEMA platform, tna FROM PUBLIC;
GRANT USAGE ON SCHEMA platform, tna TO tna_app, tna_auth;

CREATE TABLE platform.tenant_registry (
    id uuid PRIMARY KEY,
    company_code varchar(80) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT tenant_company_code_not_blank CHECK (company_code = btrim(company_code) AND company_code <> ''),
    CONSTRAINT tenant_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE tna.employee (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    employee_no varchar(40) NOT NULL,
    display_name varchar(120) NOT NULL,
    joined_on date NOT NULL,
    left_on date,
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, employee_no),
    CONSTRAINT employee_no_not_blank CHECK (employee_no = btrim(employee_no) AND employee_no <> ''),
    CONSTRAINT employee_display_name_not_blank CHECK (btrim(display_name) <> ''),
    CONSTRAINT employee_employment_dates_valid CHECK (left_on IS NULL OR left_on >= joined_on)
);

CREATE TABLE tna.tenant_account (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    email varchar(254) NOT NULL,
    password_hash text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    auth_version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, email),
    UNIQUE (tenant_id, employee_id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES tna.employee (tenant_id, id),
    CONSTRAINT account_email_normalized CHECK (email = lower(btrim(email)) AND email <> ''),
    CONSTRAINT account_password_hash_not_blank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT account_auth_version_nonnegative CHECK (auth_version >= 0)
);

CREATE TABLE tna.role_grant (
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    role varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, account_id, role),
    FOREIGN KEY (tenant_id, account_id) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT role_grant_supported_role CHECK (role IN ('EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN'))
);

ALTER TABLE platform.tenant_registry ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_registry FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.employee FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.tenant_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.tenant_account FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.role_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.role_grant FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON platform.tenant_registry
    TO tna_migration, tna_app
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Pre-authentication may only resolve a company code, not read its identities.
CREATE POLICY authentication_company_lookup ON platform.tenant_registry
    FOR SELECT TO tna_auth USING (true);

CREATE POLICY tenant_isolation ON tna.employee
    TO tna_migration, tna_app, tna_auth
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON tna.tenant_account
    TO tna_migration, tna_app, tna_auth
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON tna.role_grant
    TO tna_migration, tna_app, tna_auth
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON ALL TABLES IN SCHEMA platform, tna FROM PUBLIC;
GRANT SELECT ON platform.tenant_registry, tna.employee TO tna_app;
GRANT SELECT ON platform.tenant_registry, tna.employee, tna.tenant_account, tna.role_grant TO tna_auth;
