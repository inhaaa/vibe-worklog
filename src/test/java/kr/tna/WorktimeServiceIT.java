package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.worktime.WorktimeService;
import kr.tna.worktime.WorktimeData.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class WorktimeServiceIT {
    @Autowired WorktimeService reports;
    @Autowired ClockService clocks;
    @Autowired AttendanceSettingsService settings;
    @Autowired ApproverService approvers;
    @Autowired LeaveWorkflowService workflow;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal manager,employee,approver;
    private LocalDate date;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("spring.datasource.hikari.maximum-pool-size",()->5);p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void historicalSyntheticFacts(TestInfo info) {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        if(info.getTestMethod().orElseThrow().getName().equals("resultSchemaExists"))return;
        date=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);tenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 집계 고객사')",tenant,"RESULT-"+tenant);
        manager=member("합성 담당자",Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));
        employee=member("=합성 수식 문자열",Set.of("EMPLOYEE"));approver=member("합성 승인자",Set.of("EMPLOYEE","APPROVER"));
        approvers.set(manager,new ApproverForm(employee.employeeId(),approver.employeeId(),date.plusDays(1),0L,"합성 승인 관계",UUID.randomUUID()));
        UUID template=UUID.randomUUID(),version=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 과거 일정',?,?)",tenant,template,template.toString(),manager.accountId(),manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 과거 버전',?)",tenant,version,template,date.minusDays(7),manager.accountId());
        for(int day=1;day<=7;day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '09:00',TIME '18:00')",tenant,version,day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",tenant,version,day);
        }
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 과거 배정',?,?)",tenant,UUID.randomUUID(),employee.employeeId(),version,date.minusDays(7),manager.accountId(),manager.accountId());
        settings.calendar(manager,new CalendarForm(date.minusDays(7),date.plusDays(7),true,"합성 공휴일 검토","","합성 달력",UUID.randomUUID()));
        UUID network=UUID.randomUUID();db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 근거',?)",tenant,network,java.sql.Timestamp.from(date.minusDays(7).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),manager.accountId());
        for(String kind:List.of("IN","OUT")) {
            Instant at=date.atTime(kind.equals("IN")?9:18,0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            db.update("INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)",tenant,UUID.randomUUID(),employee.employeeId(),kind,java.sql.Timestamp.from(at),java.sql.Timestamp.from(at),UUID.randomUUID(),network,date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",tenant,employee.employeeId(),date);
        var before=clocks.day(employee,date);clocks.confirm(employee,new ConfirmationForm(date,2L,0L,true,true,before.sourceFingerprint(),UUID.randomUUID()));
    }
    @Test void resultSchemaExists() {
        assertNotNull(db.queryForObject("SELECT to_regclass('tna.day_result')::text",String.class));
        assertEquals(5,db.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname IN ('work_policy_version','employment_rule_assessment','calculation_run','day_result','report_snapshot') AND c.relrowsecurity AND c.relforcerowsecurity",Integer.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_auth','tna.day_result','SELECT')",Boolean.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.day_result','UPDATE')",Boolean.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.day_result','DELETE')",Boolean.class));
    }
    @Test void missingLegalReviewPreservesConfirmedWorkButDoesNotInventLegalClassifications() {
        assertNotNull(recalculate());var day=reports.days(employee,employee.employeeId(),date,date).getFirst();
        assertEquals(8*3_600_000L,day.result().actualWorkMillis());assertNull(day.result().over8hMillis());assertNull(day.result().legalOvertimeMillis());
        assertTrue(day.current());assertTrue(day.result().reasons().contains("LEGAL_APPLICABILITY_UNCONFIRMED"));
    }
    @Test void explicitAssessmentAndPolicyAreVersionedAndAChangeMakesOldResultsStale() {
        assertNotNull(recalculate());var old=reports.days(employee,employee.employeeId(),date,date).getFirst();
        assertNotNull(reports.assess(manager,new AssessmentForm(employee.employeeId(),date.minusDays(7),date.plusDays(7),true,"합성 일반 규정 검토","검토 기록",UUID.randomUUID())));
        assertNotNull(reports.reviewPolicy(manager,new PolicyForm(date.minusDays(7),date.plusDays(7),true,"합성 월요일 주 일치 근거","검토 기록",UUID.randomUUID())));
        assertFalse(reports.days(employee,employee.employeeId(),date,date).getFirst().current());
        recalculate();var fresh=reports.days(employee,employee.employeeId(),date,date).getFirst();assertTrue(fresh.current());assertNotEquals(old.id(),fresh.id());
        assertEquals(0L,fresh.result().over8hMillis());assertEquals(8*3_600_000L,fresh.result().actualWorkMillis());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?",Integer.class,tenant,employee.employeeId(),date));
    }
    @Test void employmentDateChangesInvalidateAResultWithoutChangingAttendanceRevisions() {
        reports.assess(manager,new AssessmentForm(employee.employeeId(),date.minusDays(7),date.plusDays(7),true,"합성 적용 대상 검토","기간 검토",UUID.randomUUID()));
        assertNotNull(recalculate());var before=reports.days(manager,employee.employeeId(),date,date).getFirst();
        assertTrue(before.current());assertEquals(0L,before.result().over8hMillis());
        String frozenEmployment=db.queryForObject("SELECT (input_refs->0->'employment')::text FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,before.id());
        assertNotNull(frozenEmployment,"입력 근거에 당시 재직 기간을 저장해야 합니다.");
        assertEquals(date.minusYears(1).toString(),db.queryForObject("SELECT input_refs->0->'employment'->>'joinedOn' FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,before.id()));
        assertEquals("null",db.queryForObject("SELECT jsonb_typeof(input_refs->0->'employment'->'leftOn') FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,before.id()));
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?",date.plusDays(1),tenant,employee.employeeId());
        assertFalse(reports.days(manager,employee.employeeId(),date,date).getFirst().current(),"입사일 변경으로 적용 검토 의미가 바뀌면 이전 결과는 오래됨이어야 합니다.");
        db.update("UPDATE tna.employee SET joined_on=?,left_on=? WHERE tenant_id=? AND id=?",date.minusYears(1),date.minusDays(1),tenant,employee.employeeId());
        assertFalse(reports.days(manager,employee.employeeId(),date,date).getFirst().current(),"마지막 재직일 변경도 이전 결과를 무효화해야 합니다.");
        assertEquals(frozenEmployment,db.queryForObject("SELECT (input_refs->0->'employment')::text FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,before.id()),"재직 정정 뒤에도 당시 근거를 덮어쓰면 안 됩니다.");
        assertEquals(before.result().inputRevision(),db.queryForObject("SELECT input_revision FROM tna.day_input_revision WHERE tenant_id=? AND employee_id=? AND work_date=?",Long.class,tenant,employee.employeeId(),date));
        assertEquals(before.result().reviewRevision(),db.queryForObject("SELECT review_revision FROM tna.day_input_revision WHERE tenant_id=? AND employee_id=? AND work_date=?",Long.class,tenant,employee.employeeId(),date));
    }
    @Test void readLocksBeforeLoadingEmployeeFactsSoConcurrentEmploymentChangesCannotMixSnapshots() throws Exception {
        reports.assess(manager,new AssessmentForm(employee.employeeId(),date.minusDays(7),date.plusDays(7),true,"합성 적용 대상 검토","동시성 검토",UUID.randomUUID()));
        assertNotNull(recalculate());assertTrue(reports.days(manager,employee.employeeId(),date,date).getFirst().current());
        try(var writer=DatabaseFixture.start().adminDataSource().getConnection();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setAutoCommit(false);
            try {
                try(var lock=writer.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))")) {
                    lock.setObject(1,tenant);lock.execute();
                }
                try(var update=writer.prepareStatement("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?")) {
                    update.setObject(1,date.plusDays(1));update.setObject(2,tenant);update.setObject(3,employee.employeeId());update.executeUpdate();
                }
                var reader=executor.submit(()->reports.days(manager,employee.employeeId(),date,date).getFirst());
                boolean waiting=false;
                for(int attempt=0;attempt<250;attempt++) {
                    waiting=db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)",Boolean.class);
                    if(waiting)break;Thread.sleep(20);
                }
                assertTrue(waiting,"보고 조회가 같은 고객사 잠금에서 대기해야 합니다.");
                writer.commit();assertFalse(reader.get(10,java.util.concurrent.TimeUnit.SECONDS).current(),"잠금 대기 전에 읽은 옛 재직 사실을 사용하면 안 됩니다.");
            }finally {writer.rollback();}
        }
    }
    @Test void bothFactAndReviewChangesInvalidateLatestnessWithoutDeletingHistory() {
        assertNotNull(recalculate());var before=clocks.day(employee,date);
        clocks.confirm(employee,new ConfirmationForm(date,before.inputRevision(),before.reviewRevision(),true,true,before.sourceFingerprint(),UUID.randomUUID()));
        assertFalse(reports.days(employee,employee.employeeId(),date,date).getFirst().current());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?",Integer.class,tenant,employee.employeeId(),date));
    }
    @Test void pendingCorrectionMakesPreviouslyConfirmedWorkUnknown() {
        var before=clocks.day(employee,date);
        assertNotNull(workflow.submitCorrection(employee,new CorrectionForm(null,date,before.inputRevision(),before.sourceFingerprint(),"KEEP","","SET",date+"T18:00",true,true,date+"T12:00/"+date+"T13:00",null,null,false,"민감 사유를 출력하지 않음",UUID.randomUUID())));
        assertNotNull(recalculate());var day=reports.days(employee,employee.employeeId(),date,date).getFirst();
        assertNull(day.result().actualWorkMillis());assertTrue(day.result().reasons().contains("PENDING_CORRECTION"));
    }
    @Test void employeeCannotRecalculateExportOrReadAnotherEmployeeAndRangesAreBounded() {
        assertStatus(403,()->reports.recalculate(employee,employee.employeeId(),date,date,UUID.randomUUID(),"직원 집계 우회"));
        assertStatus(403,()->reports.prepareExport(employee,employee.employeeId(),date,date,UUID.randomUUID(),"직원 출력 우회"));
        assertStatus(403,()->reports.days(employee,manager.employeeId(),date,date));
        assertStatus(422,()->reports.days(manager,employee.employeeId(),date,date.plusDays(31)));
        assertStatus(404,()->reports.days(manager,UUID.randomUUID(),date,date));
    }
    @Test void preparedWorkbookIsImmutableAndExcludesEmailIpAndFreeReasons() throws Exception {
        assertNotNull(recalculate());UUID snapshot=reports.prepareExport(manager,employee.employeeId(),date,date,UUID.randomUUID(),"합성 검토용 출력");assertNotNull(snapshot);
        byte[] first=reports.download(manager,snapshot);assertTrue(first.length>100);
        var text=new StringBuilder();try(var zip=new ZipInputStream(new ByteArrayInputStream(first),StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())text.append(new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }
        assertTrue(text.toString().contains("REVIEW"));assertTrue(text.toString().contains("=합성 수식 문자열"));
        assertFalse(text.toString().contains("@example.test"));assertFalse(text.toString().contains("127.0.0.1"));assertFalse(text.toString().contains("민감 사유"));
        recalculate();assertArrayEquals(first,reports.download(manager,snapshot));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,manager.accountId());
        assertStatus(403,()->reports.download(manager,snapshot));
    }
    @Test void workbookCarriesTenantFiltersSnapshotIdentityAndFullWeekReferenceMetadata() throws Exception {
        assertNotNull(recalculate());LocalDate from=date.minusDays(1);
        UUID snapshot=reports.prepareExport(manager,employee.employeeId(),from,date,UUID.randomUUID(),"합성 필터 검토 출력");
        assertNotNull(snapshot);String sheet=null;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(reports.download(manager,snapshot)),StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if(entry.getName().equals("xl/worksheets/sheet1.xml"))sheet=new String(zip.readAllBytes(),StandardCharsets.UTF_8);
            }
        }
        assertNotNull(sheet);assertTrue(sheet.contains("합성 집계 고객사"));
        for(String expected:List.of("고객사 ID",tenant.toString(),"조회 시작일",from.toString(),"조회 종료일",date.toString(),
                "스냅샷 ID",snapshot.toString(),"주 시작일","주 40시간 초과 참고(ms)","주 미확정 일수","미확정")) {
            assertTrue(sheet.contains(expected),"출력 근거 메타데이터 누락: "+expected);
        }
        assertTrue(db.queryForObject("SELECT jsonb_array_length(result_refs) FROM tna.report_snapshot WHERE tenant_id=? AND id=?",Integer.class,tenant,snapshot)>=7,
                "선택 기간 밖의 같은 주 입력 참조도 스냅샷에 포함해야 합니다.");
    }
    @Test void monthUsesFullBoundaryWeeksAndAuditViewingIsItselfAudited() {
        assertNotNull(recalculate());var month=reports.month(employee,employee.employeeId(),java.time.YearMonth.from(date));
        assertNotNull(month);assertEquals(java.time.YearMonth.from(date).lengthOfMonth(),month.days().size());
        assertEquals(java.time.DayOfWeek.MONDAY,month.weeks().getFirst().from().getDayOfWeek());
        assertTrue(month.summary().result().unconfirmedDays()>0);assertNull(month.weeks().getFirst().result().over40hMillis());
        int before=db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='AUDIT_VIEW'",Integer.class,tenant);
        assertNotNull(reports.audits(manager));assertEquals(before+1,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='AUDIT_VIEW'",Integer.class,tenant));
    }
    private UUID recalculate(){return reports.recalculate(manager,employee.employeeId(),date,date,UUID.randomUUID(),"합성 수동 재집계");}
    private TnaPrincipal member(String name,Set<String> roles) {
        UUID employeeId=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",tenant,employeeId,"E-"+employeeId,name,date.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",tenant,account,employeeId,account+"@example.test");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employeeId,1,roles,Instant.now());
    }
    private void assertStatus(int code,Runnable action){assertEquals(code,assertThrows(OrgException.class,action::run).status());}
}
