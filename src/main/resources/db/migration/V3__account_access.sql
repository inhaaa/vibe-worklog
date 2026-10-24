ALTER TABLE tna.tenant_account
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN credential_set boolean GENERATED ALWAYS AS (password_hash IS NOT NULL) STORED,
    ADD CONSTRAINT account_active_requires_password CHECK (NOT active OR password_hash IS NOT NULL);

ALTER TABLE tna.role_grant ADD COLUMN active boolean NOT NULL DEFAULT true;

CREATE TABLE tna.account_token (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    purpose varchar(20) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    email varchar(254) NOT NULL,
    issued_auth_version bigint NOT NULL,
    expires_at timestamptz(3) NOT NULL,
    used_at timestamptz(3),
    revoked_at timestamptz(3),
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT account_token_purpose_valid CHECK (purpose IN ('INVITE', 'RESET')),
    CONSTRAINT account_token_hash_valid CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_token_email_normalized CHECK (email = lower(btrim(email)) AND email <> ''),
    CONSTRAINT account_token_auth_version_nonnegative CHECK (issued_auth_version >= 0)
);

CREATE TABLE tna.approver_assignment (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    approver_employee_id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    superseded_at timestamptz(3),
    reason varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES tna.employee (tenant_id, id),
    FOREIGN KEY (tenant_id, approver_employee_id) REFERENCES tna.employee (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES tna.tenant_account (tenant_id, id),
    CONSTRAINT approver_assignment_not_self CHECK (employee_id <> approver_employee_id),
    CONSTRAINT approver_assignment_dates_valid CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT approver_assignment_reason_not_blank CHECK (btrim(reason) <> ''),
    CONSTRAINT approver_assignment_version_nonnegative CHECK (version >= 0),
    CONSTRAINT approver_assignment_period_not_overlapping EXCLUDE USING gist (
        tenant_id tna.gist_uuid_ops WITH =,
        employee_id tna.gist_uuid_ops WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (superseded_at IS NULL)
);

ALTER TABLE tna.account_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.account_token FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.approver_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.approver_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tna.account_token
    TO tna_migration, tna_app, tna_auth
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON tna.approver_assignment
    TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY authentication_invitation_audit ON tna.org_audit_event
    FOR INSERT TO tna_auth
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND action = 'ACCOUNT_INVITE_ACCEPT'
        AND target_kind = 'ACCOUNT'
        AND target_id = actor_account_id
    );

REVOKE ALL ON tna.account_token, tna.approver_assignment FROM PUBLIC;
GRANT SELECT (email, credential_set) ON tna.tenant_account TO tna_app;
GRANT INSERT (tenant_id, id, employee_id, email, active) ON tna.tenant_account TO tna_app;
GRANT INSERT ON tna.role_grant TO tna_app;
GRANT UPDATE (active) ON tna.role_grant TO tna_app;

GRANT SELECT (tenant_id, id, account_id, purpose, email, issued_auth_version, expires_at,
    used_at, revoked_at, created_at, created_by) ON tna.account_token TO tna_app;
GRANT INSERT (tenant_id, id, account_id, purpose, token_hash, email, issued_auth_version,
    expires_at, created_by) ON tna.account_token TO tna_app;
GRANT UPDATE (revoked_at) ON tna.account_token TO tna_app;
GRANT SELECT, INSERT ON tna.approver_assignment TO tna_app;
GRANT UPDATE (valid_to, superseded_at, version, updated_at, updated_by)
    ON tna.approver_assignment TO tna_app;

GRANT UPDATE (password_hash, active, auth_version) ON tna.tenant_account TO tna_auth;
GRANT SELECT ON tna.account_token TO tna_auth;
GRANT UPDATE (used_at) ON tna.account_token TO tna_auth;
GRANT INSERT ON tna.org_audit_event TO tna_auth;
