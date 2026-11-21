CREATE TABLE tna.leave_monthly_review (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    joined_on date NOT NULL,
    month_number integer NOT NULL CHECK (month_number BETWEEN 1 AND 11),
    period_start date NOT NULL,
    period_end date NOT NULL,
    accrual_on date NOT NULL,
    usable_to date NOT NULL,
    entitlement_year integer NOT NULL CHECK (entitlement_year BETWEEN 1900 AND 9998),
    scheduled_days integer NOT NULL CHECK (scheduled_days BETWEEN 0 AND 32),
    attended_days integer NOT NULL CHECK (attended_days BETWEEN 0 AND scheduled_days),
    leave_days integer NOT NULL CHECK (leave_days BETWEEN 0 AND scheduled_days),
    unresolved_days integer NOT NULL CHECK (unresolved_days BETWEEN 0 AND 32),
    decision varchar(12) NOT NULL CHECK (decision IN ('FULL','NOT_FULL','INCLUDED')),
    granted_days numeric NOT NULL CHECK (granted_days = CASE decision WHEN 'FULL' THEN 1 ELSE 0 END),
    bucket_id uuid NOT NULL,
    included_bucket_id uuid,
    included_source_reference varchar(500),
    evidence_token char(64) NOT NULL CHECK (evidence_token ~ '^[0-9a-f]{64}$'),
    reason varchar(500) NOT NULL CHECK (btrim(reason)<>''),
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, employee_id, accrual_on),
    UNIQUE (tenant_id, employee_id, month_number),
    UNIQUE (tenant_id, id, employee_id, bucket_id, accrual_on, granted_days),
    FOREIGN KEY (tenant_id, bucket_id, employee_id) REFERENCES tna.leave_bucket(tenant_id, id, employee_id),
    FOREIGN KEY (tenant_id, included_bucket_id, employee_id) REFERENCES tna.leave_bucket(tenant_id, id, employee_id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account(tenant_id, id),
    CHECK (joined_on<=period_start AND period_start<=period_end AND accrual_on=period_end+1 AND accrual_on<=usable_to),
    CHECK ((decision='INCLUDED' AND included_bucket_id IS NOT NULL AND included_bucket_id<>bucket_id
            AND included_source_reference IS NOT NULL AND btrim(included_source_reference)<>'')
        OR (decision<>'INCLUDED' AND included_bucket_id IS NULL AND included_source_reference IS NULL))
);
CREATE INDEX leave_monthly_review_bucket_idx ON tna.leave_monthly_review (tenant_id,bucket_id);
CREATE INDEX leave_monthly_review_included_idx ON tna.leave_monthly_review (tenant_id,included_bucket_id)
    WHERE included_bucket_id IS NOT NULL;
ALTER TABLE tna.leave_monthly_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_monthly_review FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_monthly_review TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.leave_monthly_review FROM PUBLIC;
GRANT SELECT,INSERT ON tna.leave_monthly_review TO tna_app;

ALTER TABLE tna.leave_ledger
    ADD COLUMN monthly_review_id uuid,
    ADD FOREIGN KEY (tenant_id,monthly_review_id,employee_id,bucket_id,effective_on,signed_quantity_days)
        REFERENCES tna.leave_monthly_review(tenant_id,id,employee_id,bucket_id,accrual_on,granted_days),
    ADD CONSTRAINT leave_ledger_monthly_reference_valid CHECK ((entry_kind='MONTHLY_ACCRUAL')=(monthly_review_id IS NOT NULL)),
    DROP CONSTRAINT leave_ledger_kind_valid,
    ADD CONSTRAINT leave_ledger_kind_valid CHECK (
        entry_kind IN ('OPENING','ADJUSTMENT','USE','RESTORE','GRANT','CARRY_IN','CARRY_OUT','EXPIRE','MONTHLY_ACCRUAL')
    );
-- Extend the validated V37 operation rules, retaining every condition on older movement kinds.
DO $$
DECLARE existing_rules text;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT existing_rules FROM pg_constraint
      WHERE conrelid='tna.leave_ledger'::regclass AND conname='leave_ledger_quantity_refs_valid';
    ALTER TABLE tna.leave_ledger DROP CONSTRAINT leave_ledger_quantity_refs_valid;
    EXECUTE format($check$ALTER TABLE tna.leave_ledger ADD CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (%s) OR (entry_kind='MONTHLY_ACCRUAL' AND signed_quantity_days IN (0,1)
            AND source_reference IS NOT NULL AND btrim(source_reference)<>''
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL))$check$, existing_rules);
END $$;
CREATE UNIQUE INDEX leave_ledger_one_monthly_review ON tna.leave_ledger (tenant_id,monthly_review_id)
    WHERE monthly_review_id IS NOT NULL;
