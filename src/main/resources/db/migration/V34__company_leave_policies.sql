ALTER TABLE tna.attendance_type_version
    DROP CONSTRAINT attendance_type_version_unit_check,
    DROP CONSTRAINT attendance_type_version_recognition_rule_check,
    ADD CONSTRAINT attendance_type_version_leave_policy_check CHECK (
        (recognition_rule = 'NOT_SUPPORTED' AND unit IN ('DAY', 'HOUR', 'NONE'))
        OR (recognition_rule = 'COMPANY_LEAVE' AND NOT system_seed
            AND unit IN ('DAY', 'DAY_HALF') AND pay_kind IN ('PAID', 'UNPAID'))
    );
