-- 법정 연차 자동 산정(설계 2026-11-27-statutory-annual-leave-design.md §2·§6): 회사 부여 기준과 사원별 판정 근거
ALTER TABLE tna.annual_leave_rule
    ADD COLUMN basis varchar(10) NOT NULL DEFAULT 'FISCAL',
    ADD CONSTRAINT annual_leave_rule_basis_valid CHECK (basis IN ('FISCAL','HIRE_DATE'));

-- V47 이전 부여 행은 근거가 비어 있다. 새 행은 근거 열을 모두 채운다.
ALTER TABLE tna.annual_leave_award
    ADD COLUMN service_year integer,
    ADD COLUMN period_start date,
    ADD COLUMN period_end date,
    ADD COLUMN scheduled_days integer,
    ADD COLUMN absence_days numeric,
    ADD COLUMN granted_days numeric,
    -- 출근율 판정이 '확인 필요'인 사원을 담당자가 골라 법정 일수로 부여했는지(설계 §4.1). 기존 행은 거짓이다.
    ADD COLUMN reviewed_by_admin boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT annual_leave_award_evidence_valid CHECK (
        (service_year IS NULL AND period_start IS NULL AND period_end IS NULL AND scheduled_days IS NULL
            AND absence_days IS NULL AND granted_days IS NULL AND NOT reviewed_by_admin)
        OR (service_year >= 1 AND period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end
            AND scheduled_days >= 0 AND (scheduled_days > 0 OR reviewed_by_admin)
            AND absence_days >= 0 AND mod(absence_days, 0.5) = 0 AND absence_days <= scheduled_days
            AND granted_days BETWEEN 0 AND 25 AND mod(granted_days, 0.5) = 0)
    );
