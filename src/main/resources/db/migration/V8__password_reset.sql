-- Separate authentication operations from the general tenant policy before adding RESET issuance.
DROP POLICY tenant_isolation ON tna.account_token;
CREATE POLICY tenant_isolation ON tna.account_token
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE POLICY authentication_token_read ON tna.account_token
    FOR SELECT TO tna_auth
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND purpose IN ('INVITE','RESET'));
CREATE POLICY authentication_token_update ON tna.account_token
    FOR UPDATE TO tna_auth
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND purpose IN ('INVITE','RESET'))
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND purpose IN ('INVITE','RESET'));
CREATE POLICY authentication_reset_token_issue ON tna.account_token
    FOR INSERT TO tna_auth
    WITH CHECK (
        tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND purpose='RESET'
        AND created_by=account_id AND used_at IS NULL AND revoked_at IS NULL
        AND EXISTS(SELECT 1 FROM tna.tenant_account a
            WHERE a.tenant_id=account_token.tenant_id AND a.id=account_token.account_id
              AND a.email=account_token.email AND a.auth_version=account_token.issued_auth_version
              AND a.active AND a.credential_set)
    );
CREATE POLICY authentication_reset_audit ON tna.org_audit_event
    FOR INSERT TO tna_auth
    WITH CHECK (
        tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
        AND action IN ('ACCOUNT_RESET_REQUEST','ACCOUNT_RESET_COMPLETE')
        AND target_kind='ACCOUNT' AND target_id=actor_account_id
    );

GRANT INSERT (tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by)
    ON tna.account_token TO tna_auth;
GRANT UPDATE (revoked_at) ON tna.account_token TO tna_auth;
