-- Historical synthetic evidence only. The application calculates the day and closes the month.
BEGIN;
SET LOCAL ROLE tna_app;
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='30s';
SET LOCAL app.tenant_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd';
DO $example$
DECLARE
    tenant constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
    manager constant uuid := 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2';
    developer_team constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddddddd0102';
    example_employee constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020001';
    archived_account constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020002';
    template constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020004';
    schedule constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020005';
    calendar constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020007';
    network_rule constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020008';
    input_in constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020009';
    input_out constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020010';
    marker constant uuid := 'dddddddd-dddd-4ddd-8ddd-dddd31020015';
    work_day constant date := DATE '2025-02-28';
    first_day constant date := DATE '2025-02-01';
    note constant text := '공개 데모 2025년 2월 월 마감 예시 · 합성 자료';
    proof constant text := '95123b5ad3891f8861b9aae44ed4505041f3c46b67075f782e10549cf4b319bd';
    clock_in constant timestamptz := TIMESTAMPTZ '2025-02-28 09:00:00+09:00';
    clock_out constant timestamptz := TIMESTAMPTZ '2025-02-28 18:00:00+09:00';
    meal constant jsonb := '[{"startAt":"2025-02-28T03:00:00Z","endAt":"2025-02-28T04:00:00Z"}]';
BEGIN
    IF current_database()<>'worklog_demo' OR current_user<>'tna_app'
       OR (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user)
       OR NOT EXISTS (SELECT 1 FROM platform.tenant_registry WHERE id=tenant AND active)
       OR NOT EXISTS (SELECT 1 FROM tna.tenant_account a JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                      WHERE a.tenant_id=tenant AND a.id=manager AND a.active AND e.employee_no='99999'
                        AND e.id='dddddddd-dddd-4ddd-8ddd-ddddddddddd1')
       OR NOT EXISTS (SELECT 1 FROM tna.organization WHERE tenant_id=tenant AND id=developer_team AND active AND name='가상 개발팀') THEN
        RAISE EXCEPTION '월 마감 예시 대상 DB·역할·회사·담당자·부서 불일치';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(tenant::text,0));
    IF (SELECT min(joined_on) FROM tna.employee WHERE tenant_id=tenant AND id<>example_employee)
            IS DISTINCT FROM DATE '2025-03-01' OR EXISTS (
        SELECT FROM tna.employee WHERE tenant_id=tenant AND id<>example_employee AND joined_on<=work_day
          AND (left_on IS NULL OR left_on>=first_day)) THEN
        RAISE EXCEPTION '기존 사원의 근무월과 겹치거나 최초 입사일이 다름';
    END IF;
    IF EXISTS (SELECT FROM tna.org_audit_event WHERE tenant_id=tenant AND command_id=marker) THEN
        IF NOT EXISTS (SELECT FROM tna.org_audit_event WHERE tenant_id=tenant AND command_id=marker
                     AND action='PUBLIC_CLOSE_EXAMPLE_IMPORT' AND target_id=example_employee AND actor_account_id=manager)
           OR NOT EXISTS (SELECT FROM tna.employee WHERE tenant_id=tenant AND id=example_employee AND employee_no='25002'
                     AND display_name='월 마감 예시' AND joined_on=work_day AND left_on=work_day AND NOT active)
           OR NOT EXISTS (SELECT FROM tna.employee_assignment WHERE tenant_id=tenant AND employee_id=example_employee
                     AND organization_id=developer_team AND valid_from=work_day AND valid_to=work_day+1)
           OR NOT EXISTS (SELECT FROM tna.tenant_account WHERE tenant_id=tenant AND id=archived_account
                     AND employee_id=example_employee AND NOT active AND NOT credential_set)
           OR (SELECT count(*) FROM tna.attendance_event WHERE tenant_id=tenant AND employee_id=example_employee
                     AND assigned_work_date=work_day AND ((id=input_in AND event_kind='IN' AND occurred_at=clock_in)
                     OR (id=input_out AND event_kind='OUT' AND occurred_at=clock_out)))<>2
           OR NOT EXISTS (SELECT FROM tna.work_confirmation WHERE tenant_id=tenant AND employee_id=example_employee AND work_date=work_day
                     AND confirmed_by=archived_account AND source_fingerprint=proof AND break_snapshot=meal)
           THEN RAISE EXCEPTION '기존 예시 표식과 원천 자료 불일치'; END IF;
        RAISE NOTICE 'PUBLIC_CLOSE_EXAMPLE_ALREADY_APPLIED';
        RETURN;
    END IF;
    IF EXISTS (SELECT FROM tna.attendance_month_closure WHERE tenant_id=tenant AND tna.attendance_month_closure.month_start=DATE '2025-02-01')
       OR EXISTS (SELECT FROM tna.employee WHERE tenant_id=tenant AND (id=example_employee OR employee_no='25002'))
       OR EXISTS (SELECT FROM tna.attendance_event WHERE tenant_id=tenant AND assigned_work_date BETWEEN first_day AND work_day)
       OR EXISTS (SELECT FROM tna.day_result WHERE tenant_id=tenant AND work_date BETWEEN first_day AND work_day)
       OR EXISTS (SELECT FROM tna.approval_request WHERE tenant_id=tenant AND from_date<=work_day AND to_date>=first_day)
       OR EXISTS (SELECT FROM tna.checkout_follow_up WHERE tenant_id=tenant AND work_date BETWEEN first_day AND work_day)
       OR EXISTS (SELECT FROM tna.calendar_version WHERE tenant_id=tenant AND valid_from<=work_day AND valid_to>work_day) THEN
        RAISE EXCEPTION '기존 원천·캘린더·사번·월 마감 이력을 보존하기 위해 중단';
    END IF;

    INSERT INTO tna.employee (tenant_id,id,employee_no,display_name,joined_on,left_on,active,created_at,created_by,updated_at,updated_by)
       VALUES(tenant,example_employee,'25002','월 마감 예시',work_day,work_day,false,current_timestamp,manager,current_timestamp,manager);
    INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,active)
       VALUES(tenant,archived_account,example_employee,'archived-month-close-25002@example.invalid',false);
    INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,valid_to,reason,created_by,updated_by)
       VALUES(tenant,'dddddddd-dddd-4ddd-8ddd-dddd31020003',example_employee,developer_team,work_day,work_day+1,note,manager,manager);
    INSERT INTO tna.schedule_template(tenant_id,id,code,name,active,created_by,updated_by)
       VALUES(tenant,template,'DEMO-CLOSE-2502','월 마감 예시 일정',false,manager,manager);
    INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by)
       VALUES(tenant,schedule,template,1,work_day,note,manager);
    INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time)
       VALUES(tenant,schedule,5,true,TIME '09:00',TIME '18:00');
    INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time)
       VALUES(tenant,schedule,5,1,TIME '12:00',TIME '13:00');
    INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by)
       VALUES(tenant,'dddddddd-dddd-4ddd-8ddd-dddd31020006',example_employee,schedule,work_day,work_day+1,note,manager,manager);
    INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
       SELECT tenant,calendar,coalesce(max(revision),0)+1,work_day,work_day+1,note,manager FROM tna.calendar_version WHERE tenant_id=tenant;
    INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
       VALUES(tenant,calendar,work_day,'WORKDAY','월 마감 예시 근무일',note);
    INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,valid_to,reason,created_by)
       VALUES(tenant,network_rule,'192.0.2.31/32','월 마감 예시 기록망',clock_in,clock_out+interval '1 second',note,manager);
    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date)
       VALUES(tenant,input_in,example_employee,'IN',clock_in,clock_in,'dddddddd-dddd-4ddd-8ddd-dddd31020011','IP','192.0.2.31',network_rule,work_day),
             (tenant,input_out,example_employee,'OUT',clock_out,clock_out,'dddddddd-dddd-4ddd-8ddd-dddd31020012','IP','192.0.2.31',network_rule,work_day);
    INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision)
       VALUES(tenant,example_employee,work_day,2,1);
    INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,review_revision,source_fingerprint,
                                     presence_as_work,breaks_match,confirmed_at,confirmed_by,break_snapshot,client_command_id)
       VALUES(tenant,'dddddddd-dddd-4ddd-8ddd-dddd31020013',example_employee,work_day,2,1,proof,true,true,clock_out+interval '1 minute',
              archived_account,meal,'dddddddd-dddd-4ddd-8ddd-dddd31020014');
    INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                                   reason,before_state,after_state,request_trace_id)
       VALUES(tenant,'dddddddd-dddd-4ddd-8ddd-dddd31020016',marker,manager,'PUBLIC_CLOSE_EXAMPLE_IMPORT','EMPLOYEE',example_employee,
              encode(sha256(convert_to(note,'UTF8')),'hex'),note,'null'::jsonb,jsonb_build_object('month','2025-02','synthetic',true),
              'dddddddd-dddd-4ddd-8ddd-dddd31020017');
    RAISE NOTICE 'PUBLIC_CLOSE_EXAMPLE_IMPORTED';
END
$example$;
COMMIT;
