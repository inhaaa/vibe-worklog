-- 연장근무 인정(설계 2026-11-26-overtime-payroll-design.md §2·§5): 일별 인정 연장, 규칙 CALC-3, 월 마감 급여용 열
ALTER TABLE tna.day_result
    ADD COLUMN approved_overtime_millis bigint,
    ADD CONSTRAINT day_result_approved_overtime_nonnegative CHECK (approved_overtime_millis IS NULL OR approved_overtime_millis >= 0);

ALTER TABLE tna.calculation_run DROP CONSTRAINT calculation_run_engine_status_valid;
ALTER TABLE tna.day_result DROP CONSTRAINT day_result_fingerprint_rule_valid;
ALTER TABLE tna.calculation_run
    ADD CONSTRAINT calculation_run_engine_status_valid
        CHECK (engine_version IN ('CALC-1','CALC-2','CALC-3') AND status IN ('RUNNING','COMPLETED','FAILED'));
ALTER TABLE tna.day_result
    ADD CONSTRAINT day_result_fingerprint_rule_valid
        CHECK (source_fingerprint ~ '^[0-9a-f]{64}$' AND rule_version IN ('CALC-1','CALC-2','CALC-3'));

ALTER TABLE tna.attendance_month_export_row
    ADD COLUMN payroll_captured boolean NOT NULL DEFAULT false,
    ADD COLUMN approved_overtime_millis bigint CHECK (approved_overtime_millis >= 0),
    ADD COLUMN unapproved_extra_millis bigint CHECK (unapproved_extra_millis >= 0),
    ADD COLUMN night_work_millis bigint CHECK (night_work_millis >= 0),
    ADD COLUMN holiday_work_millis bigint CHECK (holiday_work_millis >= 0),
    ADD COLUMN late_count integer CHECK (late_count >= 0),
    ADD COLUMN late_millis bigint CHECK (late_millis >= 0),
    ADD COLUMN early_leave_count integer CHECK (early_leave_count >= 0),
    ADD COLUMN early_leave_millis bigint CHECK (early_leave_millis >= 0),
    ADD COLUMN absence_days numeric CHECK (absence_days >= 0);
