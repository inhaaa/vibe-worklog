-- 결재 알림: 결재 이력 1건당 받는 사람 1명의 화면 알림과 메일 발송 상태 (설계 2026-11-26-approval-notifications-design.md §3)
CREATE TABLE tna.notification (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    recipient_employee_id uuid NOT NULL,
    event_id uuid NOT NULL,
    request_id uuid NOT NULL,
    kind varchar(30) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at timestamptz(3),
    mail_state varchar(10) NOT NULL DEFAULT 'PENDING',
    mail_attempts smallint NOT NULL DEFAULT 0,
    mail_next_at timestamptz(3) DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,event_id),
    FOREIGN KEY (tenant_id,recipient_employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,event_id) REFERENCES tna.approval_event (tenant_id,id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request (tenant_id,id),
    CONSTRAINT notification_kind_valid CHECK (kind IN ('APPROVAL_REQUESTED','APPROVED','REJECTED')),
    CONSTRAINT notification_mail_state_valid CHECK (mail_state IN ('PENDING','SENT','SKIPPED','FAILED')),
    CONSTRAINT notification_mail_attempts_range CHECK (mail_attempts BETWEEN 0 AND 3),
    CONSTRAINT notification_mail_next_only_pending CHECK ((mail_state='PENDING')=(mail_next_at IS NOT NULL))
);
CREATE INDEX notification_recipient_recent ON tna.notification (tenant_id,recipient_employee_id,created_at DESC,id DESC);
CREATE INDEX notification_recipient_unread ON tna.notification (tenant_id,recipient_employee_id,request_id) WHERE read_at IS NULL;
CREATE INDEX notification_mail_pending ON tna.notification (tenant_id,mail_next_at) WHERE mail_state='PENDING';

ALTER TABLE tna.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.notification
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.notification FROM PUBLIC;
GRANT SELECT,INSERT ON tna.notification TO tna_app;
GRANT UPDATE (read_at,mail_state,mail_attempts,mail_next_at) ON tna.notification TO tna_app;
