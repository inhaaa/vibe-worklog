ALTER TABLE tna.leave_request_day
    DROP CONSTRAINT leave_request_day_quantity_valid,
    ADD COLUMN leave_part varchar(4) NOT NULL DEFAULT 'FULL',
    ALTER COLUMN quantity_days TYPE numeric,
    ADD CONSTRAINT leave_request_day_quantity_valid CHECK (
        (leave_part = 'FULL' AND quantity_days IN (0, 1))
        OR (leave_part IN ('AM', 'PM') AND quantity_days = 0.5 AND zero_reason IS NULL)
    );

-- Unrestricted numeric plus explicit checks reject fractions instead of rounding them on insert.
ALTER TABLE tna.leave_ledger
    DROP CONSTRAINT leave_ledger_quantity_refs_valid,
    ALTER COLUMN signed_quantity_days TYPE numeric,
    ADD CONSTRAINT leave_ledger_quantity_refs_valid CHECK (
        (entry_kind IN ('OPENING','ADJUSTMENT') AND signed_quantity_days = trunc(signed_quantity_days)
            AND signed_quantity_days BETWEEN -2147483648 AND 2147483647
            AND (entry_kind <> 'OPENING' OR signed_quantity_days >= 0)
            AND request_effect_id IS NULL AND leave_date IS NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'USE' AND signed_quantity_days IN (-1,-0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NULL)
        OR (entry_kind = 'RESTORE' AND signed_quantity_days IN (1,0.5)
            AND request_effect_id IS NOT NULL AND leave_date IS NOT NULL AND reverses_entry_id IS NOT NULL)
    );

-- V7 defined this two-column check without a name. Locate it by its referenced leave column.
DO $$
DECLARE old_check name;
BEGIN
    SELECT c.conname INTO STRICT old_check
    FROM pg_catalog.pg_constraint c
    JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.conrelid = 'tna.day_result'::regclass AND c.contype = 'c' AND a.attname = 'leave_days';
    EXECUTE format('ALTER TABLE tna.day_result DROP CONSTRAINT %I', old_check);
END $$;
ALTER TABLE tna.day_result
    ALTER COLUMN leave_days TYPE numeric,
    ADD CONSTRAINT day_result_leave_days_valid CHECK (legal_overtime_millis IS NULL AND leave_days IN (0,0.5,1));
