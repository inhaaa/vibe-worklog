-- 최초 sample-data.sql 적용 직후에만 실행하는 읽기 전용 검증이다.
-- psql -X -v ON_ERROR_STOP=1 -U tna_migration -d worklog_demo -f verify-sample-data.sql
-- 샘플 적용 전에는 조직 개수 검증이 실패해야 한다. 방문자의 변경 뒤 재검증용이 아니다.
BEGIN READ ONLY;
SET LOCAL TIME ZONE 'Asia/Seoul';

DO $guard$
BEGIN
    IF current_database() <> 'worklog_demo'
       OR session_user <> 'tna_migration' OR current_user <> 'tna_migration'
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_GUARD: worklog_demo와 RLS 적용 tna_migration 직접 접속이 필요합니다.';
    END IF;
END
$guard$;

SELECT set_config('app.tenant_id', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', true);

DO $verify$
DECLARE
    tenant constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
    admin_employee constant uuid := 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1';
    admin_account constant uuid := 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2';
    year_from date := make_date(extract(year FROM CURRENT_DATE)::integer, 1, 1);
    year_to date := make_date(extract(year FROM CURRENT_DATE)::integer + 1, 1, 1);
    row record;
    actual bigint;
BEGIN
    IF (SELECT count(*) FROM platform.tenant_registry r
        JOIN tna.tenant_account a ON a.tenant_id = r.id AND a.id = admin_account
        JOIN tna.employee e ON e.tenant_id = a.tenant_id AND e.id = a.employee_id
        WHERE r.id = tenant AND r.company_code = 'TNA-LOCAL-DEMO' AND r.display_name = '로컬 합성 데모'
          AND r.active AND a.active AND a.email = 'admin@local.example.test'
          AND a.employee_id = admin_employee AND a.password_hash IS NOT NULL AND a.auth_version = 1
          AND e.employee_no = 'LOCAL-DEMO' AND e.display_name = '로컬 데모 담당자'
          AND e.active AND e.joined_on <= CURRENT_DATE AND e.left_on IS NULL) <> 1
       OR (SELECT count(*) FROM tna.org_audit_event
           WHERE tenant_id = tenant AND action = 'LOCAL_DEMO_BOOTSTRAP'
             AND command_id = tenant AND actor_account_id = admin_account
             AND target_kind = 'TENANT' AND target_id = tenant
             AND after_state @> '{"localDemo":true,"employeeId":"dddddddd-dddd-4ddd-8ddd-ddddddddddd1","accountId":"dddddddd-dddd-4ddd-8ddd-ddddddddddd2"}'::jsonb) <> 1 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_BOOTSTRAP: 예약된 합성 데모 bootstrap 자료가 필요합니다.';
    END IF;

    -- 샘플 부재 시 가장 먼저 실패할 기대값이다.
    IF (SELECT count(*) FROM tna.organization WHERE tenant_id = tenant) <> 2 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_COUNT: 합성 조직 기대값은 2개입니다.';
    END IF;
    FOR row IN
        SELECT c.relname, coalesce(e.expected, 0) AS expected
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN (VALUES
            ('employee', 4), ('tenant_account', 1), ('role_grant', 2),
            ('organization', 2), ('employee_assignment', 4), ('org_audit_event', 2),
            ('schedule_template', 1), ('schedule_version', 1), ('schedule_day', 7),
            ('schedule_break', 5), ('schedule_assignment', 1), ('calendar_version', 1),
            ('calendar_day', year_to - year_from), ('leave_bucket', 1), ('leave_ledger', 1)
        ) AS e(table_name, expected) ON e.table_name = c.relname
        WHERE n.nspname = 'tna' AND c.relkind = 'r'
    LOOP
        EXECUTE format('SELECT count(*) FROM tna.%I WHERE tenant_id = $1', row.relname) INTO actual USING tenant;
        IF actual <> row.expected THEN
            RAISE EXCEPTION 'WORKLOG_SAMPLE_COUNT: % 기대값 %, 실제 %', row.relname, row.expected, actual;
        END IF;
    END LOOP;

    IF (SELECT array_agg(role ORDER BY role) FROM tna.role_grant
        WHERE tenant_id = tenant AND account_id = admin_account AND active)
        IS DISTINCT FROM ARRAY['ATTENDANCE_ADMIN', 'EMPLOYEE']::varchar[] THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_ROLES: 기존 admin 권한이 달라졌습니다.';
    END IF;
    IF (SELECT count(*) FROM tna.organization WHERE tenant_id = tenant AND active
        AND ((code = 'DEMO-OPS' AND name = '가상 운영팀') OR (code = 'DEMO-DEV' AND name = '가상 개발팀'))
        AND parent_id IS NULL AND created_by = admin_account AND updated_by = admin_account) <> 2
       OR (SELECT count(*) FROM tna.employee WHERE tenant_id = tenant AND active AND left_on IS NULL
           AND joined_on = year_from AND created_by = admin_account AND updated_by = admin_account
           AND ((employee_no = 'DEMO-001' AND display_name = '가상 사원 하나')
             OR (employee_no = 'DEMO-002' AND display_name = '가상 사원 둘')
             OR (employee_no = 'DEMO-003' AND display_name = '가상 사원 셋'))) <> 3
       OR (SELECT count(*) FROM tna.employee_assignment a
           JOIN tna.organization o ON o.tenant_id = a.tenant_id AND o.id = a.organization_id
           JOIN tna.employee e ON e.tenant_id = a.tenant_id AND e.id = a.employee_id
           WHERE a.tenant_id = tenant AND a.valid_from = year_from AND a.valid_to IS NULL
             AND a.valid_from <= CURRENT_DATE
             AND ((e.employee_no IN ('LOCAL-DEMO', 'DEMO-001') AND o.code = 'DEMO-OPS')
               OR (e.employee_no IN ('DEMO-002', 'DEMO-003') AND o.code = 'DEMO-DEV'))) <> 4 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_ORGANIZATION: 합성 조직·사원·당해년 소속 배정을 확인해 주세요.';
    END IF;

    IF (SELECT count(*) FROM tna.schedule_assignment a
        JOIN tna.schedule_version v ON v.tenant_id = a.tenant_id AND v.id = a.schedule_version_id
        JOIN tna.schedule_template t ON t.tenant_id = v.tenant_id AND t.id = v.template_id
        WHERE a.tenant_id = tenant AND a.employee_id = admin_employee
          AND a.valid_from = year_from AND a.valid_to IS NULL
          AND a.valid_from <= CURRENT_DATE
          AND v.valid_from = year_from AND v.revision = 1 AND t.code = 'DEMO-FIXED' AND t.active) <> 1
       OR (SELECT count(*) FROM tna.schedule_day WHERE tenant_id = tenant
           AND ((weekday BETWEEN 1 AND 5 AND workday AND start_time = TIME '09:00' AND end_time = TIME '18:00')
             OR (weekday IN (6, 7) AND NOT workday AND start_time IS NULL AND end_time IS NULL))) <> 7
       OR (SELECT count(*) FROM tna.schedule_break WHERE tenant_id = tenant
           AND weekday BETWEEN 1 AND 5 AND sequence = 1 AND start_time = TIME '12:00' AND end_time = TIME '13:00') <> 5 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_SCHEDULE: 당해년 admin 고정 일정·예정 휴게가 일치하지 않습니다.';
    END IF;

    IF (SELECT count(*) FROM tna.calendar_version WHERE tenant_id = tenant
        AND revision = 1 AND valid_from = year_from AND valid_to = year_to) <> 1
       OR (SELECT count(*) FROM tna.calendar_day WHERE tenant_id = tenant
           AND work_date >= year_from AND work_date < year_to
           AND kind = CASE WHEN extract(isodow FROM work_date) <= 5 THEN 'WORKDAY' ELSE 'NON_WORKDAY' END
           AND basis = '공개 데모용 합성 요일 구분이며 실제 법정공휴일 검증 자료가 아님') <> year_to - year_from THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_CALENDAR: 당해년 모든 날짜의 합성 근무일 구분이 필요합니다.';
    END IF;

    IF (SELECT count(*) FROM tna.leave_bucket b
        JOIN tna.leave_ledger l ON l.tenant_id = b.tenant_id AND l.bucket_id = b.id AND l.employee_id = b.employee_id
        WHERE b.tenant_id = tenant AND b.employee_id = admin_employee
          AND b.base_date = year_from AND b.usable_from = year_from AND b.usable_to = year_to - 1
          AND b.base_date <= CURRENT_DATE AND b.usable_from <= CURRENT_DATE AND b.usable_to >= CURRENT_DATE
          AND l.entry_kind = 'OPENING' AND l.signed_quantity_days = 12 AND l.effective_on = year_from
          AND l.source_reference = '공개 데모 초기 합성 기초 12일; 법정 발생·실제 사원 잔여가 아님'
          AND b.source_reference = l.source_reference) <> 1
       OR (SELECT coalesce(sum(signed_quantity_days), 0) FROM tna.leave_ledger
           WHERE tenant_id = tenant AND employee_id = admin_employee) <> 12 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_LEAVE: 당해년 admin 합성 기초·사용 가능 잔여는 12일이어야 합니다.';
    END IF;

    IF (SELECT count(*) FROM tna.org_audit_event WHERE tenant_id = tenant
        AND action = 'PUBLIC_DEMO_SAMPLE_BOOTSTRAP' AND actor_account_id = admin_account
        AND target_kind = 'TENANT' AND target_id = tenant
        AND after_state @> '{"synthetic":true,"organizations":2,"employees":4,"attendanceEvents":0,"allowedNetworks":0}'::jsonb) <> 1 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_AUDIT: 합성 초기 자료 준비 근거가 필요합니다.';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE (n.nspname = 'tna' OR (n.nspname = 'platform' AND c.relname = 'tenant_registry'))
                 AND c.relkind = 'r' AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity))
       OR EXISTS (SELECT 1 FROM pg_constraint f JOIN pg_class c ON c.oid = f.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'tna' AND f.contype = 'f' AND NOT f.convalidated)
       OR EXISTS (SELECT 1 FROM pg_trigger g JOIN pg_class c ON c.oid = g.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'tna' AND g.tgisinternal AND g.tgenabled = 'D') THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_CONSTRAINTS: FORCE RLS·검증된 FK·제약 trigger를 유지해야 합니다.';
    END IF;

    -- 같은 연결에서도 tenant 컨텍스트가 없거나 다르면 샘플 행은 보이지 않아야 한다.
    PERFORM set_config('app.tenant_id', '', true);
    IF EXISTS (SELECT 1 FROM platform.tenant_registry) OR EXISTS (SELECT 1 FROM tna.employee)
       OR EXISTS (SELECT 1 FROM tna.organization) OR EXISTS (SELECT 1 FROM tna.leave_ledger) THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_RLS: tenant 미설정 읽기가 차단되지 않았습니다.';
    END IF;
    PERFORM set_config('app.tenant_id', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', true);
    FOR row IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'tna' AND c.relkind = 'r'
    LOOP
        EXECUTE format('SELECT count(*) FROM tna.%I WHERE tenant_id = $1', row.relname) INTO actual USING tenant;
        IF actual <> 0 THEN
            RAISE EXCEPTION 'WORKLOG_SAMPLE_RLS: 다른 tenant 컨텍스트에 % 샘플이 노출됐습니다.', row.relname;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id', tenant::text, true);
    RAISE NOTICE 'WORKLOG_SAMPLE_OK: 서울 기준 %년 합성 자료·잔여12일·미기록 근태·FK·FORCE RLS 확인', extract(year FROM CURRENT_DATE);
END
$verify$;
ROLLBACK;
