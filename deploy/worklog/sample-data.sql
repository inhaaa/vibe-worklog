-- worklog_demo 최초 준비 전용: owner의 빈 전용 DB 확인 → migrate → public-demo-bootstrap 뒤 한 번만 적용한다.
-- psql -X -v ON_ERROR_STOP=1 -U tna_migration -d worklog_demo -f sample-data.sql
-- 다른 tenant의 존재 여부는 FORCE RLS를 우회하지 않고는 검사할 수 없으므로 owner 사전 확인을 생략하지 않는다.
-- 실제 기업 자료·계정·출퇴근·실제 휴게 확인·허용 IP·승인 효력은 생성하지 않는다.
-- 적용 뒤 bootstrap을 재실행하지 않는다. bootstrap의 최초 사원 1명 확인 계약을 유지한다.
BEGIN;
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

DO $seed$
DECLARE
    tenant constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
    admin_employee constant uuid := 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1';
    admin_account constant uuid := 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2';
    operations constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0101';
    development constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0102';
    template constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0301';
    schedule constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0302';
    calendar constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0401';
    bucket constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0501';
    source constant text := '공개 데모 초기 합성 기초 12일; 법정 발생·실제 사원 잔여가 아님';
    year_from date := make_date(extract(year FROM CURRENT_DATE)::integer, 1, 1);
    year_to date := make_date(extract(year FROM CURRENT_DATE)::integer + 1, 1, 1);
    row record;
    actual bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(tenant::text, 0));
    IF (SELECT count(*) FROM platform.tenant_registry r
        JOIN tna.tenant_account a ON a.tenant_id = r.id AND a.id = admin_account
        JOIN tna.employee e ON e.tenant_id = a.tenant_id AND e.id = a.employee_id
        WHERE r.id = tenant AND r.company_code = 'TNA-LOCAL-DEMO' AND r.display_name = '로컬 합성 데모'
          AND r.active AND a.active AND a.email = 'admin@local.example.test'
          AND a.employee_id = admin_employee AND a.password_hash IS NOT NULL AND a.auth_version = 1
          AND e.employee_no = 'LOCAL-DEMO' AND e.display_name = '로컬 데모 담당자'
          AND e.active AND e.joined_on <= CURRENT_DATE AND e.left_on IS NULL) <> 1
       OR (SELECT array_agg(role ORDER BY role) FROM tna.role_grant
           WHERE tenant_id = tenant AND account_id = admin_account AND active)
           IS DISTINCT FROM ARRAY['ATTENDANCE_ADMIN', 'EMPLOYEE']::varchar[]
       OR (SELECT count(*) FROM tna.org_audit_event
           WHERE tenant_id = tenant AND action = 'LOCAL_DEMO_BOOTSTRAP'
             AND command_id = tenant AND actor_account_id = admin_account
             AND target_kind = 'TENANT' AND target_id = tenant
             AND after_state @> '{"localDemo":true,"employeeId":"dddddddd-dddd-4ddd-8ddd-ddddddddddd1","accountId":"dddddddd-dddd-4ddd-8ddd-ddddddddddd2"}'::jsonb) <> 1 THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_BOOTSTRAP: 예약된 합성 데모 bootstrap 자료가 필요합니다.';
    END IF;

    -- bootstrap 직후 사원·계정·역할·감사 외의 업무 자료가 한 행이라도 있으면 전체 중단한다.
    FOR row IN
        SELECT c.relname, coalesce(e.expected, 0) AS expected
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN (VALUES ('employee', 1), ('tenant_account', 1), ('role_grant', 2), ('org_audit_event', 1))
            AS e(table_name, expected) ON e.table_name = c.relname
        WHERE n.nspname = 'tna' AND c.relkind = 'r'
    LOOP
        EXECUTE format('SELECT count(*) FROM tna.%I WHERE tenant_id = $1', row.relname) INTO actual USING tenant;
        IF actual <> row.expected THEN
            RAISE EXCEPTION 'WORKLOG_SAMPLE_NOT_EMPTY: % 기대값 %, 실제 %. 기존 자료를 변경하지 않습니다.', row.relname, row.expected, actual;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE (n.nspname = 'tna' OR (n.nspname = 'platform' AND c.relname = 'tenant_registry'))
                 AND c.relkind = 'r' AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity))
       OR EXISTS (SELECT 1 FROM pg_constraint f JOIN pg_class c ON c.oid = f.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'tna' AND f.contype = 'f' AND NOT f.convalidated)
       OR EXISTS (SELECT 1 FROM pg_trigger g JOIN pg_class c ON c.oid = g.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'tna' AND g.tgisinternal AND g.tgenabled NOT IN ('O', 'A')) THEN
        RAISE EXCEPTION 'WORKLOG_SAMPLE_CONSTRAINTS: FORCE RLS·검증된 FK·제약 trigger를 유지해야 합니다.';
    END IF;

    INSERT INTO tna.organization (tenant_id, id, code, name, created_by, updated_by)
    VALUES (tenant, operations, 'DEMO-OPS', '가상 운영팀', admin_account, admin_account),
           (tenant, development, 'DEMO-DEV', '가상 개발팀', admin_account, admin_account);

    INSERT INTO tna.employee
        (tenant_id, id, employee_no, display_name, joined_on, created_at, created_by, updated_at, updated_by)
    VALUES (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0201', 'DEMO-001', '가상 사원 하나', year_from,
            CURRENT_TIMESTAMP, admin_account, CURRENT_TIMESTAMP, admin_account),
           (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0202', 'DEMO-002', '가상 사원 둘', year_from,
            CURRENT_TIMESTAMP, admin_account, CURRENT_TIMESTAMP, admin_account),
           (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0203', 'DEMO-003', '가상 사원 셋', year_from,
            CURRENT_TIMESTAMP, admin_account, CURRENT_TIMESTAMP, admin_account);

    INSERT INTO tna.employee_assignment
        (tenant_id, id, employee_id, organization_id, valid_from, valid_to, reason, created_by, updated_by)
    SELECT tenant, md5(e.id::text || ':public-demo-organization')::uuid, e.id,
           CASE WHEN e.employee_no IN ('LOCAL-DEMO', 'DEMO-001') THEN operations ELSE development END,
           year_from, NULL, '공개 데모 초기 합성 소속 배정', admin_account, admin_account
    FROM tna.employee e WHERE e.tenant_id = tenant;

    INSERT INTO tna.schedule_template (tenant_id, id, code, name, created_by, updated_by)
    VALUES (tenant, template, 'DEMO-FIXED', '가상 월~금 09~18', admin_account, admin_account);
    INSERT INTO tna.schedule_version (tenant_id, id, template_id, revision, valid_from, reason, created_by)
    VALUES (tenant, schedule, template, 1, year_from, '공개 데모 초기 합성 고정 일정', admin_account);
    INSERT INTO tna.schedule_day (tenant_id, version_id, weekday, workday, start_time, end_time)
    SELECT tenant, schedule, weekday, weekday <= 5,
           CASE WHEN weekday <= 5 THEN TIME '09:00' END,
           CASE WHEN weekday <= 5 THEN TIME '18:00' END
    FROM generate_series(1, 7) AS days(weekday);
    -- 예정 휴게일 뿐이며 실제 휴게 확인(work_confirmation)은 생성하지 않는다.
    INSERT INTO tna.schedule_break (tenant_id, version_id, weekday, sequence, start_time, end_time)
    SELECT tenant, schedule, weekday, 1, TIME '12:00', TIME '13:00'
    FROM generate_series(1, 5) AS days(weekday);
    INSERT INTO tna.schedule_assignment
        (tenant_id, id, employee_id, schedule_version_id, valid_from, valid_to, reason, created_by, updated_by)
    VALUES (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0303', admin_employee, schedule, year_from, NULL,
            '공개 데모 초기 합성 일정 배정', admin_account, admin_account);

    INSERT INTO tna.calendar_version (tenant_id, id, revision, valid_from, valid_to, reason, created_by)
    VALUES (tenant, calendar, 1, year_from, year_to, '공개 데모 초기 합성 요일 구분', admin_account);
    INSERT INTO tna.calendar_day (tenant_id, version_id, work_date, kind, name, basis)
    SELECT tenant, calendar, day::date,
           CASE WHEN extract(isodow FROM day) <= 5 THEN 'WORKDAY' ELSE 'NON_WORKDAY' END,
           CASE WHEN extract(isodow FROM day) <= 5 THEN '가상 평일 근무일' ELSE '가상 주말 비근로일' END,
           '공개 데모용 합성 요일 구분이며 실제 법정공휴일 검증 자료가 아님'
    FROM generate_series(year_from::timestamp, (year_to - 1)::timestamp, INTERVAL '1 day') AS days(day);
    INSERT INTO tna.holiday_year (tenant_id, year, version, applied_at, applied_by)
    VALUES (tenant, extract(year FROM year_from)::integer, 1, CURRENT_TIMESTAMP, admin_account);

    -- migration 초기 합성 fixture다. 일반 서비스의 본인 기초 등록 금지와 독립 승인 정책은 그대로 유지한다.
    INSERT INTO tna.leave_bucket
        (tenant_id, id, employee_id, base_date, usable_from, usable_to, source_reference, created_by)
    VALUES (tenant, bucket, admin_employee, year_from, year_from, year_to - 1, source, admin_account);
    INSERT INTO tna.leave_ledger
        (tenant_id, id, bucket_id, employee_id, entry_kind, signed_quantity_days, effective_on,
         source_reference, reason, created_by)
    VALUES (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0502', bucket, admin_employee, 'OPENING', 12, year_from,
            source, '명시적 공개 데모 초기 합성 자료 준비; 독립 담당자의 실제 등록 이력이 아님', admin_account);

    INSERT INTO tna.org_audit_event
        (tenant_id, id, command_id, actor_account_id, action, target_kind, target_id, input_hash,
         reason, before_state, after_state, request_trace_id)
    VALUES (tenant, 'dddddddd-dddd-4ddd-8ddd-dddddddd0901', 'dddddddd-dddd-4ddd-8ddd-dddddddd0902',
            admin_account, 'PUBLIC_DEMO_SAMPLE_BOOTSTRAP', 'TENANT', tenant,
            encode(sha256(convert_to(concat_ws(':', 'PUBLIC_DEMO_SAMPLE_BOOTSTRAP', tenant, year_from, year_to, 2, 4, 12), 'UTF8')), 'hex'),
            '명시적 migration 초기 합성 자료 준비; 일반 업무 명령·실제 근태·법정 발생 이력이 아님',
            '{"bootstrapOnly":true}'::jsonb,
            jsonb_build_object('synthetic', true, 'yearFrom', year_from, 'yearToExclusive', year_to,
                               'organizations', 2, 'employees', 4, 'attendanceEvents', 0, 'allowedNetworks', 0,
                               'openingLeaveDays', 12, 'actualBreaksConfirmed', false),
            'dddddddd-dddd-4ddd-8ddd-dddddddd0903');
    RAISE NOTICE 'WORKLOG_SAMPLE_CREATED: 서울 기준 %년 합성 자료 준비. verify-sample-data.sql로 검증해 주세요.', extract(year FROM CURRENT_DATE);
END
$seed$;
COMMIT;
