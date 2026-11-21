-- Company rules and their first application to each entitlement year are append-only.
CREATE TABLE tna.annual_leave_rule (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry(id),
    version bigint NOT NULL CHECK (version > 0),
    id uuid NOT NULL,
    fiscal_start_month integer NOT NULL CHECK (fiscal_start_month BETWEEN 1 AND 12),
    grant_days numeric NOT NULL CHECK (grant_days BETWEEN 0 AND 100000 AND mod(grant_days, 0.5) = 0),
    carry_limit_days numeric NOT NULL CHECK (carry_limit_days BETWEEN 0 AND 100000 AND mod(carry_limit_days, 0.5) = 0),
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account(tenant_id, id)
);

CREATE TABLE tna.annual_leave_cycle (
    tenant_id uuid NOT NULL,
    entitlement_year integer NOT NULL CHECK (entitlement_year BETWEEN 1900 AND 9998),
    rule_version bigint NOT NULL,
    id uuid NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, entitlement_year),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, rule_version) REFERENCES tna.annual_leave_rule(tenant_id, version),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account(tenant_id, id)
);

CREATE TABLE tna.annual_leave_award (
    tenant_id uuid NOT NULL,
    entitlement_year integer NOT NULL,
    employee_id uuid NOT NULL,
    bucket_id uuid NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, entitlement_year, employee_id),
    UNIQUE (tenant_id, bucket_id, employee_id),
    FOREIGN KEY (tenant_id, entitlement_year) REFERENCES tna.annual_leave_cycle(tenant_id, entitlement_year),
    FOREIGN KEY (tenant_id, bucket_id, employee_id) REFERENCES tna.leave_bucket(tenant_id, id, employee_id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES tna.tenant_account(tenant_id, id)
);

ALTER TABLE tna.annual_leave_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.annual_leave_rule FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.annual_leave_cycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.annual_leave_cycle FORCE ROW LEVEL SECURITY;
ALTER TABLE tna.annual_leave_award ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.annual_leave_award FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.annual_leave_rule TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.annual_leave_cycle TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tna.annual_leave_award TO tna_migration, tna_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON tna.annual_leave_rule, tna.annual_leave_cycle, tna.annual_leave_award FROM PUBLIC;
GRANT SELECT, INSERT ON tna.annual_leave_rule, tna.annual_leave_cycle, tna.annual_leave_award TO tna_app;

-- Each annual movement refers to the same employee's new award. Existing ledger rows keep their original meaning.
ALTER TABLE tna.leave_ledger
    ADD COLUMN annual_award_bucket_id uuid,
    ADD FOREIGN KEY (tenant_id, annual_award_bucket_id, employee_id)
        REFERENCES tna.annual_leave_award(tenant_id, bucket_id, employee_id),
    DROP CONSTRAINT leave_ledger_kind_valid,
    DROP CONSTRAINT leave_ledger_quantity_refs_valid,
    ADD CONSTRAINT leave_ledger_kind_valid CHECK (
        entry_kind IN ('OPENING','ADJUSTMENT','USE','RESTORE','GRANT','CARRY_IN','CARRY_OUT','EXPIRE')
    ),
    ADD CONSTRAINT leave_ledger_annual_reference_valid CHECK (
        (entry_kind IN ('GRANT','CARRY_IN','CARRY_OUT','EXPIRE')) = (annual_award_bucket_id IS NOT NULL)
    ),
    ADD CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (entry_kind IN ('OPENING','ADJUSTMENT') AND mod(signed_quantity_days, 0.5) = 0
            AND signed_quantity_days BETWEEN -2147483648 AND 2147483647
            AND (entry_kind <> 'OPENING' OR signed_quantity_days >= 0)
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'USE' AND signed_quantity_days IN (-1,-0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'RESTORE' AND signed_quantity_days IN (1,0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NOT NULL)
        OR (entry_kind IN ('GRANT','CARRY_IN','CARRY_OUT','EXPIRE') AND mod(signed_quantity_days, 0.5) = 0
            AND source_reference IS NOT NULL AND btrim(source_reference) <> ''
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL
            AND ((entry_kind='GRANT' AND bucket_id=annual_award_bucket_id AND signed_quantity_days BETWEEN 0 AND 100000)
                OR (entry_kind='CARRY_IN' AND bucket_id=annual_award_bucket_id AND signed_quantity_days>0 AND signed_quantity_days<=100000)
                OR (entry_kind IN ('CARRY_OUT','EXPIRE') AND bucket_id<>annual_award_bucket_id AND signed_quantity_days<0)))
    );
CREATE UNIQUE INDEX leave_ledger_one_annual_movement_per_bucket ON tna.leave_ledger (tenant_id, bucket_id, entry_kind)
    WHERE entry_kind IN ('GRANT','CARRY_IN','CARRY_OUT','EXPIRE');
