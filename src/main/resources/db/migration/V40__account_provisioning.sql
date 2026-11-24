-- 담당자 등록·입사 확정 때 만드는 계정의 최초 접속 대기 상태
ALTER TABLE tna.tenant_account
    ALTER COLUMN email DROP NOT NULL,
    ADD COLUMN activation_expires_at timestamptz(3),
    ADD COLUMN activation_failures smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT account_activation_failures_range CHECK (activation_failures BETWEEN 0 AND 5);

GRANT SELECT (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_app;
GRANT INSERT (activation_expires_at) ON tna.tenant_account TO tna_app;
GRANT UPDATE (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_app;
GRANT UPDATE (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_auth;

-- 최초 접속의 생년월일 확인은 인증 역할이 고객사 범위 안에서, 최초 접속 대기 계정의 사원만 읽는다.
GRANT SELECT (tenant_id, employee_id, birth_date) ON tna.employee_personnel TO tna_auth;
CREATE POLICY authentication_birth_date_read ON tna.employee_personnel FOR SELECT TO tna_auth
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
        AND EXISTS (SELECT 1 FROM tna.tenant_account a
            WHERE a.tenant_id=employee_personnel.tenant_id AND a.employee_id=employee_personnel.employee_id
              AND a.activation_expires_at IS NOT NULL));

-- 최초 접속 결과(성공·실패·잠금)는 인증 역할이 계정 자신을 대상으로만 감사에 남긴다.
CREATE POLICY authentication_activation_audit ON tna.org_audit_event FOR INSERT TO tna_auth
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
        AND action IN ('ACCOUNT_ACTIVATED','ACCOUNT_ACTIVATION_FAILED','ACCOUNT_LOCKED')
        AND target_kind='ACCOUNT' AND target_id=actor_account_id);

-- 입사 예정 단계에서 받는 생년월일(기존 행 때문에 NULL 허용, 필수 여부는 명령에서 검사)
ALTER TABLE tna.employee_onboarding
    ADD COLUMN birth_date date,
    ADD CONSTRAINT onboarding_birth_date_range CHECK (birth_date IS NULL OR birth_date BETWEEN DATE '1900-01-01' AND joined_on);
GRANT INSERT (birth_date), UPDATE (birth_date) ON tna.employee_onboarding TO tna_app;
