ALTER TABLE tna.leave_ledger
    DROP CONSTRAINT leave_ledger_quantity_refs_valid,
    ADD CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (entry_kind IN ('OPENING','ADJUSTMENT') AND mod(signed_quantity_days, 0.5) = 0
            AND signed_quantity_days BETWEEN -2147483648 AND 2147483647
            AND (entry_kind <> 'OPENING' OR signed_quantity_days >= 0)
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'USE' AND signed_quantity_days IN (-1,-0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'RESTORE' AND signed_quantity_days IN (1,0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NOT NULL)
    ),
    -- The older database accepted zero adjustments and the full integer range.
    -- Enforce the form's bounds on new entries while preserving immutable historical rows.
    ADD CONSTRAINT leave_ledger_admin_entry_bounds CHECK (
        entry_kind NOT IN ('OPENING','ADJUSTMENT') OR
        (signed_quantity_days BETWEEN -100000 AND 100000 AND
            (entry_kind <> 'ADJUSTMENT' OR signed_quantity_days <> 0))
    ) NOT VALID;
