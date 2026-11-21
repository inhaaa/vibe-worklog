CREATE TABLE tna.leave_exit_settlement (
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    id uuid NOT NULL,
    left_on date NOT NULL,
    settlement_days numeric NOT NULL CHECK (settlement_days >= 0 AND mod(settlement_days,0.5)=0),
    reason varchar(500) NOT NULL CHECK (btrim(reason)<>''),
    prior_history_confirmed boolean NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    PRIMARY KEY (tenant_id,employee_id),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,id,employee_id,left_on),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES tna.employee(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account(tenant_id,id)
);
ALTER TABLE tna.leave_exit_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.leave_exit_settlement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.leave_exit_settlement TO tna_app,tna_migration
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.leave_exit_settlement FROM PUBLIC;
GRANT SELECT, INSERT ON tna.leave_exit_settlement TO tna_app;

ALTER TABLE tna.leave_ledger
    ADD COLUMN exit_settlement_id uuid,
    ADD FOREIGN KEY (tenant_id,exit_settlement_id,employee_id,effective_on)
        REFERENCES tna.leave_exit_settlement(tenant_id,id,employee_id,left_on),
    ADD CONSTRAINT leave_ledger_exit_reference_valid CHECK ((entry_kind='EXIT_SETTLEMENT')=(exit_settlement_id IS NOT NULL)),
    DROP CONSTRAINT leave_ledger_kind_valid,
    ADD CONSTRAINT leave_ledger_kind_valid CHECK (
        entry_kind IN ('OPENING','ADJUSTMENT','USE','RESTORE','GRANT','CARRY_IN','CARRY_OUT','EXPIRE','MONTHLY_ACCRUAL','EXIT_SETTLEMENT')
    );
-- Keep the full validated V38 expressions for opening, annual, monthly, usage and restoration entries.
DO $$
DECLARE existing_rules text;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT existing_rules FROM pg_constraint
      WHERE conrelid='tna.leave_ledger'::regclass AND conname='leave_ledger_quantity_refs_valid';
    ALTER TABLE tna.leave_ledger DROP CONSTRAINT leave_ledger_quantity_refs_valid;
    EXECUTE format($check$ALTER TABLE tna.leave_ledger ADD CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (%s) OR (entry_kind='EXIT_SETTLEMENT' AND signed_quantity_days<0 AND mod(signed_quantity_days,0.5)=0
            AND source_reference IS NOT NULL AND btrim(source_reference)<>''
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL))$check$, existing_rules);
END $$;
CREATE UNIQUE INDEX leave_ledger_one_exit_per_bucket ON tna.leave_ledger(tenant_id,bucket_id)
    WHERE entry_kind='EXIT_SETTLEMENT';
