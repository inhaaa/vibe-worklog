-- B 묶음: 휴게 자동 적용, 지각·조퇴·결근, 시스템(새벽 일괄) 계산.
ALTER TABLE tna.day_result
    ADD COLUMN late_millis bigint,
    ADD COLUMN early_leave_millis bigint,
    ADD COLUMN absence varchar(10),
    ADD CONSTRAINT day_result_late_valid CHECK (late_millis IS NULL OR late_millis >= 0),
    ADD CONSTRAINT day_result_early_leave_valid CHECK (early_leave_millis IS NULL OR early_leave_millis >= 0),
    ADD CONSTRAINT day_result_absence_valid CHECK (absence IS NULL OR absence IN ('FULL','HALF'));

ALTER TABLE tna.work_confirmation
    ADD COLUMN method varchar(20) NOT NULL DEFAULT 'EMPLOYEE',
    ALTER COLUMN confirmed_by DROP NOT NULL,
    ADD CONSTRAINT work_confirmation_method_valid CHECK (method IN ('EMPLOYEE','AUTO_SCHEDULE')),
    ADD CONSTRAINT work_confirmation_employee_has_actor CHECK (method <> 'EMPLOYEE' OR confirmed_by IS NOT NULL);

-- NULL = 시스템(새벽 일괄) 실행.
ALTER TABLE tna.calculation_run ALTER COLUMN requested_by DROP NOT NULL;

-- 규칙 버전 CALC-2 허용. 기존 CALC-1 결과는 그대로 둔다. V7이 이름 없이 둔 검사를 정의로 찾는다.
DO $$
DECLARE old_check record;
BEGIN
    FOR old_check IN
        SELECT c.conrelid::regclass AS tbl, c.conname
        FROM pg_catalog.pg_constraint c
        WHERE c.conrelid IN ('tna.calculation_run'::regclass, 'tna.day_result'::regclass) AND c.contype = 'c'
          AND pg_catalog.pg_get_constraintdef(c.oid) LIKE '%CALC-1%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', old_check.tbl, old_check.conname);
    END LOOP;
END $$;
ALTER TABLE tna.calculation_run
    ADD CONSTRAINT calculation_run_engine_status_valid
        CHECK (engine_version IN ('CALC-1','CALC-2') AND status IN ('RUNNING','COMPLETED','FAILED'));
ALTER TABLE tna.day_result
    ADD CONSTRAINT day_result_fingerprint_rule_valid
        CHECK (source_fingerprint ~ '^[0-9a-f]{64}$' AND rule_version IN ('CALC-1','CALC-2'));
