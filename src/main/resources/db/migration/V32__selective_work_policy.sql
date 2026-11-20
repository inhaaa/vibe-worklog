ALTER TABLE tna.work_time_code_version
    ADD COLUMN work_mode varchar(12) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN core_start time(0),
    ADD COLUMN core_end time(0),
    ADD COLUMN standard_minutes integer,
    ADD COLUMN settlement_period varchar(16),
    ADD CONSTRAINT work_time_code_mode_policy CHECK (
        (work_mode = 'FIXED' AND core_start IS NULL AND core_end IS NULL
            AND standard_minutes IS NULL AND settlement_period IS NULL)
        OR
        (work_mode = 'SELECTIVE'
            AND before_start IS NULL AND before_end IS NULL AND after_start IS NULL AND after_end IS NULL
            AND extract(second FROM start_time) = 0 AND extract(second FROM end_time) = 0
            AND standard_minutes IS NOT NULL
            AND standard_minutes BETWEEN 1 AND extract(epoch FROM (end_time - start_time)) / 60
            AND settlement_period IS NOT NULL AND settlement_period IN ('CALENDAR_MONTH','ISO_WEEK')
            AND ((core_start IS NULL AND core_end IS NULL)
                OR (core_start IS NOT NULL AND core_end IS NOT NULL
                    AND start_time <= core_start AND core_start < core_end AND core_end <= end_time
                    AND extract(second FROM core_start) = 0 AND extract(second FROM core_end) = 0)))
    );

GRANT INSERT(work_mode,core_start,core_end,standard_minutes,settlement_period)
    ON tna.work_time_code_version TO tna_app;
