package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.org.OrgException;
import kr.tna.request.*;
import kr.tna.request.EmploymentAccrualData.*;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class EmploymentAccrualIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026,11,22);
    @TestConfiguration(proxyBeanMethods=false)
    static class Dates {
        @Bean @Primary Clock employmentReviewClock() { return Clock.fixed(TODAY.atTime(10,0).atZone(SEOUL).toInstant(),SEOUL); }
    }
    @Autowired EmploymentAccrualService accrual;
    @Autowired WorktimeService worktime;
    @Autowired ClockService clocks;
    @Autowired AnnualLeaveCycleService annual;
    @Autowired LeaveAttributionService attributions;
    @Autowired LeaveWorkflowMapper workflow;
    @Autowired LeaveWorkflowService leave;
    @Autowired TenantTransactions transactions;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("dataSource") javax.sql.DataSource appSource;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal admin, worker;
    final LocalDate today = TODAY;
    final LocalDate joined = today.withDayOfMonth(1).minusMonths(3);

    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        var fixture = DatabaseFixture.start();
        props.add("spring.datasource.url", fixture::jdbcUrl);
        props.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        props.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        props.add("tna.auth.url", fixture::jdbcUrl);
        props.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        props.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 회사')", tenant, "EMP-" + tenant);
        admin = member("H01", true, joined.minusYears(3));
        worker = member("W01", false, joined);
    }

    @Test void previewAndBothDecisionsMatchPersonalLedgerIncludingZeroWithFrozenAttendanceEvidence() {
        var preview = accrual.preview(admin, worker.employeeId());
        assertEquals(3, preview.periods().size());
        var first = preview.periods().getFirst();
        assertEquals(joined.lengthOfMonth(),first.unresolvedDays());
        assertNull(first.decision());
        assertEquals(0, annual.ledger(worker,null).availableDays().signum());
        var form = form(first, "FULL");
        UUID original = accrual.post(admin, form);
        assertEquals(original, accrual.post(admin, form));
        assertEquals(409, failure(() -> accrual.post(admin, form(first, "FULL"))));
        var updated = accrual.preview(admin, worker.employeeId());
        assertNull(updated.periods().getFirst().token());
        assertEquals("FULL",updated.periods().getFirst().decision());
        accrual.post(admin, form(updated.periods().get(1), "NOT_FULL"));
        updated = accrual.preview(admin, worker.employeeId());
        assertEquals(0,updated.periods().get(1).grantedDays());
        assertEquals("NOT_FULL",updated.periods().get(1).decision());
        var ledger = annual.ledger(worker,joined.getYear());
        assertEquals(0,BigDecimal.ONE.compareTo(ledger.availableDays()));
        assertTrue(ledger.entries().stream().allMatch(e -> e.reason().equals("외부 출근부 확인") && e.sourceReference().contains("미확인")));
        assertEquals(List.of(0,1),ledger.entries().stream().map(e -> e.signedQuantityDays().intValueExact()).sorted().toList());
        assertEquals(1,ledger.entries().stream().map(AnnualLeaveCycleData.Entry::bucketId).distinct().count());
        UUID bucket=ledger.entries().getFirst().bucketId();
        assertEquals(409, failure(() -> attributions.save(admin,bucket,new LeaveAttributionData.Form(1L,"CONFIRMED",joined.getYear()-1,true,"확인","다른 연도",UUID.randomUUID()))));
    }

    @Test void closedDateIsInclusiveAndAnotherCompanyOrSelfCannotPost() {
        var last=joined.plusMonths(2);
        db.update("UPDATE tna.employee SET left_on=?,active=false WHERE tenant_id=? AND id=?",last,tenant,worker.employeeId());
        var rows=accrual.preview(admin,worker.employeeId()).periods();
        assertEquals(2,rows.size());
        assertEquals(last,rows.getLast().accrualOn());
        assertEquals(403,failure(() -> accrual.preview(admin,admin.employeeId())));
        assertEquals(404,failure(() -> accrual.preview(admin,DatabaseFixture.EMPLOYEE_B)));
        assertEquals(403,failure(() -> accrual.preview(worker,worker.employeeId())));
        assertEquals(422,failure(() -> accrual.post(admin,new PostForm(worker.employeeId(),last.plusMonths(1),"FULL","확인",rows.getLast().token(),UUID.randomUUID()))));
    }

    @Test void changedEvidenceRejectsPreviewAndFrozenDecisionsRetainTheirOriginalCounts() {
        var first=accrual.preview(admin,worker.employeeId()).periods().getFirst();
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES(?,?,?,1,0)", tenant,worker.employeeId(),joined);
        assertEquals(409,failure(() -> accrual.post(admin,form(first,"FULL"))));
        var revised=accrual.preview(admin,worker.employeeId()).periods().getFirst();
        assertNotEquals(first.token(),revised.token());
        assertEquals(422,failure(() -> accrual.post(admin,form(revised,"PENDING"))));
        assertEquals(422,failure(() -> accrual.post(admin,new PostForm(worker.employeeId(),revised.accrualOn(),"FULL"," ",revised.token(),UUID.randomUUID()))));
    }

    @Test void separateSimultaneousCommandsCreateExactlyOneDecisionAndOneMovement() throws Exception {
        var first=accrual.preview(admin,worker.employeeId()).periods().getFirst();
        Callable<Integer> save=() -> { try { accrual.post(admin,form(first,"FULL")); return 200; } catch (OrgException error) { return error.status(); } };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var answers=pool.invokeAll(List.of(save,save));
            assertEquals(List.of(200,409),java.util.stream.Stream.of(answers.getFirst().get(),answers.getLast().get()).sorted().toList());
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE tenant_id=? AND entry_kind='MONTHLY_ACCRUAL'",Integer.class,tenant));
        UUID other=DatabaseFixture.TENANT_B;
        assertEquals(0,transactions.execute(other,()->workflow.listBuckets(other,worker.employeeId(),today).size()));
        var app = new JdbcTemplate(appSource);
        String decisionCount = "SELECT count(*) FROM tna.leave_monthly_review WHERE employee_id=?";
        assertEquals(1,transactions.execute(tenant,()->app.queryForObject(decisionCount,Integer.class,worker.employeeId())));
        assertEquals(0,transactions.execute(other,()->app.queryForObject(decisionCount,Integer.class,worker.employeeId())));
    }

    @Test void laterMonthlyCreditsCannotFinanceEarlierLeaveAndMonthlyBucketsDoNotBlockTheCompanyFiscalAward() {
        annual.saveRule(admin,new AnnualLeaveCycleData.RuleForm(0L,joined.getMonthValue(),BigDecimal.TEN,BigDecimal.ONE,"회계연도 기준",UUID.randomUUID()));
        var periods=accrual.preview(admin,worker.employeeId()).periods();
        accrual.post(admin,form(periods.get(1),"FULL"));
        accrual.post(admin,form(accrual.preview(admin,worker.employeeId()).periods().getFirst(),"FULL"));
        var ledger=annual.ledger(worker,joined.getYear());
        assertEquals(0,new BigDecimal("2").compareTo(ledger.availableDays()));
        var bucket=ledger.entries().getFirst().bucketId();
        var movements=transactions.execute(tenant,()->workflow.monthlyBalanceMovements(tenant,bucket));
        assertFalse(EmploymentAccrualData.covers(movements,List.of(day(periods.getFirst().accrualOn(),1),day(periods.getFirst().accrualOn().plusDays(1),1))));
        assertTrue(EmploymentAccrualData.covers(movements,List.of(day(periods.getFirst().accrualOn(),1),day(periods.get(1).accrualOn(),1))));
        assertTrue(annual.preview(admin,joined.getYear()).rows().stream().anyMatch(r -> r.employeeId().equals(worker.employeeId())));
    }

    @Test void firstReviewWithoutPolicyKeepsTheLedgerCalendarYearWhenTheFirstPolicyIsSaved() {
        accrual.post(admin,form(accrual.preview(admin,worker.employeeId()).periods().getFirst(),"FULL"));
        assertEquals(409,failure(() -> annual.saveRule(admin,new AnnualLeaveCycleData.RuleForm(0L,3,BigDecimal.TEN,BigDecimal.ONE,"첫 기준",UUID.randomUUID()))));
        annual.saveRule(admin,new AnnualLeaveCycleData.RuleForm(0L,1,BigDecimal.TEN,BigDecimal.ONE,"원장과 같은 회계연도",UUID.randomUUID()));
        assertEquals(1,annual.rule(admin).fiscalStartMonth());
    }

    @Test void countedAttendanceMatchesAConfirmedWorkdayAndAStoredDecisionDoesNotChangeAfterARevision() {
        var date = joined.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        UUID template = UUID.randomUUID(), version = UUID.randomUUID(), calendar = UUID.randomUUID(), network = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES(?,?,?,'합성 근무',?,?)",tenant,template,"FIRST",admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES(?,?,?,1,?,'합성 근무',?)",tenant,version,template,joined,admin.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES(?,?,1,true,TIME '09:00',TIME '18:00')",tenant,version);
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES(?,?,?,?,?,'합성 배정',?,?)",tenant,UUID.randomUUID(),worker.employeeId(),version,joined,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES(?,?,1,?,?,'확인 달력',?)",tenant,calendar,joined,joined.plusMonths(1),admin.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES(?,?,?,'WORKDAY','월요일','확인 달력')",tenant,calendar,date);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES(?,?,CAST('127.0.0.1/32' AS cidr),'합성',?,'합성 근거',?)",tenant,network,java.sql.Timestamp.from(joined.atStartOfDay(SEOUL).toInstant()),admin.accountId());
        for (var kind : List.of("IN","OUT")) {
            var at = java.sql.Timestamp.from(date.atTime(kind.equals("IN") ? 9 : 18,0).atZone(SEOUL).toInstant());
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                        source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                    """,tenant,UUID.randomUUID(),worker.employeeId(),kind,at,at,UUID.randomUUID(),network,date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES(?,?,?,2,0)",tenant,worker.employeeId(),date);
        var before = accrual.preview(admin,worker.employeeId()).periods().getFirst();
        assertEquals(1,before.scheduledDays());
        assertEquals(0,before.attendedDays());
        clocks.confirm(worker,new ConfirmationForm(date,2L,0L,true,true,clocks.day(worker,date).sourceFingerprint(),UUID.randomUUID()));
        worktime.recalculate(admin,worker.employeeId(),date,date,UUID.randomUUID(),"확인한 합성 근무");
        var counted = accrual.preview(admin,worker.employeeId()).periods().getFirst();
        assertEquals(1,counted.attendedDays());
        assertEquals(before.unresolvedDays()-1,counted.unresolvedDays());
        assertEquals(409,failure(() -> accrual.post(admin,form(before,"FULL"))));
        accrual.post(admin,form(counted,"FULL"));
        db.update("UPDATE tna.day_input_revision SET input_revision=input_revision+1 WHERE tenant_id=? AND employee_id=? AND work_date=?",tenant,worker.employeeId(),date);
        var frozen = accrual.preview(admin,worker.employeeId()).periods().getFirst();
        assertEquals(counted.attendedDays(),frozen.attendedDays());
        assertTrue(annual.ledger(worker,null).entries().getFirst().sourceReference().contains("출근 확인 1일"));
    }

    @Test void anOpenEndedOpeningCanBeLinkedOnceWithoutAddingAnyLeaveAndKeepsItsFullReference() {
        String source = "이관 증빙 " + "원".repeat(490);
        UUID opening = opening(worker,source,null,joined.getYear());
        var preview = accrual.preview(admin,worker.employeeId());
        assertEquals(1,preview.openingSources().size());
        assertEquals(source,preview.openingSources().getFirst().sourceReference());
        assertEquals(1,preview.openingSources().getFirst().creditedDays().intValueExact());
        var first = preview.periods().getFirst();
        var command = included(first,opening);
        assertEquals(accrual.post(admin,command),accrual.post(admin,command));
        var after = accrual.preview(admin,worker.employeeId());
        assertEquals(1,after.openingSources().getFirst().linkedDays().intValueExact());
        assertEquals(opening,after.periods().getFirst().includedBucketId());
        assertEquals("INCLUDED",after.periods().getFirst().decision());
        assertEquals(0,after.periods().getFirst().grantedDays());
        assertEquals(1,annual.ledger(worker,null).availableDays().intValueExact());
        var linked=annual.ledger(worker,null).entries().stream().filter(e -> "MONTHLY_ACCRUAL".equals(e.entryKind())).findFirst().orElseThrow();
        assertEquals(0,linked.signedQuantityDays().signum());
        assertTrue(linked.sourceReference().contains(source));
        assertEquals("유급 월 이미 전기 원장에 반영",linked.reason());
        assertEquals(409,failure(() -> accrual.post(admin,included(preview.periods().get(1),opening))));
        assertEquals(422,failure(() -> accrual.post(admin,included(after.periods().get(1),opening))));
        assertEquals(422,failure(() -> accrual.post(admin,included(after.periods().get(1),linked.bucketId()))));
        assertEquals(422,failure(() -> accrual.post(admin,new PostForm(worker.employeeId(),after.periods().get(1).accrualOn(),"FULL",
                "중복 근거 선택",after.periods().get(1).token(),UUID.randomUUID(),opening))));
        assertEquals(422,failure(() -> accrual.post(admin,included(after.periods().get(1),null))));
        assertEquals(409,failure(() -> attributions.save(admin,opening,new LeaveAttributionData.Form(1L,"CONFIRMED",
                joined.getYear()-1,true,"이관 귀속 변경","근거가 된 원장 변경",UUID.randomUUID()))));
    }

    @Test void aNewSourceOrARevisionInvalidatesThePreviewAndOnlyTheSelectedWorkersSourceIsEligible() {
        UUID own = opening(worker,"원래 기초");
        var original = accrual.preview(admin,worker.employeeId()).periods().getFirst();
        leave.adjust(admin,own,new RequestData.AdjustmentForm(new BigDecimal("0.5"),today,0L,"추가 자료","근거 개정",UUID.randomUUID()));
        assertEquals(409,failure(() -> accrual.post(admin,included(original,own))));
        UUID colleague = opening(member("W02",false,joined),"동료 이관");
        UUID otherTenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 타사')",otherTenant,"EMP-"+otherTenant);
        var otherManager = member(otherTenant,"H02",true,joined.minusYears(1));
        var otherWorker = member(otherTenant,"W02",false,joined);
        UUID otherSource = leave.openBucket(otherManager,new RequestData.BucketForm(otherWorker.employeeId(),joined,joined,null,
                BigDecimal.ONE,"타사 기초","타사 이관 근거",UUID.randomUUID()));
        attributions.save(otherManager,otherSource,new LeaveAttributionData.Form(0L,"CONFIRMED",joined.getYear(),true,"타사 귀속","근거 확인",UUID.randomUUID()));
        var revised = accrual.preview(admin,worker.employeeId());
        assertEquals(1,revised.openingSources().size());
        assertEquals(422,failure(() -> accrual.post(admin,included(revised.periods().getFirst(),colleague))));
        assertEquals(422,failure(() -> accrual.post(admin,included(revised.periods().getFirst(),otherSource))));
        opening(worker,"추가로 확인한 원천");
        assertEquals(409,failure(() -> accrual.post(admin,included(revised.periods().getFirst(),own))));
    }

    private UUID opening(TnaPrincipal owner,String source) {
        return opening(owner,source,joined.plusYears(1).minusDays(1),joined.getYear());
    }

    @Test void onlyAccrualsInTheConfirmedYearAndCoveredByTheSourceEndDateCanUseTheOpening() {
        UUID ended = opening(worker,"첫 발생일에 만료된 기초",joined.plusMonths(1),joined.getYear());
        UUID otherYear = opening(worker,"다른 귀속연도 기초",joined.plusYears(1),joined.getYear()-1);
        var preview = accrual.preview(admin,worker.employeeId());
        assertFalse(preview.openingSources().stream().anyMatch(s -> s.bucketId().equals(otherYear)));
        assertEquals(List.of(joined.plusMonths(1)),preview.openingSources().getFirst().accrualDates());
        assertEquals(422,failure(() -> accrual.post(admin,included(preview.periods().get(1),ended))));
        assertEquals(422,failure(() -> accrual.post(admin,included(preview.periods().getFirst(),otherYear))));
    }

    @Test void twoMonthsCannotConcurrentlyUseTheSameImportedDay() throws Exception {
        UUID source = opening(worker,"동시 확인 기초");
        var periods = accrual.preview(admin,worker.employeeId()).periods();
        List<Callable<Integer>> attempts = periods.subList(0,2).stream().<Callable<Integer>>map(p -> () -> {
            try { accrual.post(admin,included(p,source)); return 200; }
            catch (OrgException error) { return error.status(); }
        }).toList();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(attempts);
            assertEquals(List.of(200,409),List.of(results.getFirst().get(),results.getLast().get()).stream().sorted().toList());
        }
        var after = accrual.preview(admin,worker.employeeId());
        assertEquals(1,after.openingSources().getFirst().linkedDays().intValueExact());
        assertEquals(1,after.periods().stream().filter(p -> source.equals(p.includedBucketId())).count());
        assertEquals(1,annual.ledger(worker,null).availableDays().intValueExact());
    }

    @Test void aBindingNeedsAnOpeningAvailableByTodayOrTheLastEmployedDay() {
        UUID future = opening(worker,"다음 달부터 사용할 기초",today.plusMonths(1),null,joined.getYear());
        UUID now = opening(worker,"과거 발생분을 오늘 이관",today,null,joined.getYear());
        var active = accrual.preview(admin,worker.employeeId());
        assertEquals(List.of(now),active.openingSources().stream().map(OpeningSource::bucketId).toList());
        assertTrue(active.openingSources().getFirst().accrualDates().contains(active.periods().getFirst().accrualOn()));
        assertEquals(422,failure(() -> accrual.post(admin,included(active.periods().getFirst(),future))));
        LocalDate last = joined.plusMonths(2);
        db.update("UPDATE tna.employee SET left_on=?,active=false WHERE tenant_id=? AND id=?",last,tenant,worker.employeeId());
        var leaver = accrual.preview(admin,worker.employeeId());
        assertTrue(leaver.openingSources().isEmpty());
        assertEquals(422,failure(() -> accrual.post(admin,included(leaver.periods().getFirst(),now))));
    }

    @Test void correctedOpeningHasOnlyItsRemainingOriginalCreditAvailableToLink() {
        UUID cancelled = opening(worker,"전액 오등록 취소");
        UUID partly = opening(worker,"절반 감액한 기초");
        leave.adjust(admin,cancelled,new RequestData.AdjustmentForm(-1,today,0L,"취소 자료","오등록 취소",UUID.randomUUID()));
        leave.adjust(admin,partly,new RequestData.AdjustmentForm(new BigDecimal("-0.5"),today,0L,"감액 자료","일부 오등록",UUID.randomUUID()));
        var adjusted = accrual.preview(admin,worker.employeeId());
        assertEquals(1,adjusted.openingSources().size());
        assertEquals(0,new BigDecimal("0.5").compareTo(adjusted.openingSources().getFirst().creditedDays()));
        assertEquals(422,failure(() -> accrual.post(admin,included(adjusted.periods().getFirst(),cancelled))));
        assertEquals(422,failure(() -> accrual.post(admin,included(adjusted.periods().getFirst(),partly))));
        leave.adjust(admin,partly,new RequestData.AdjustmentForm(2,today,1L,"이후 조정","조정량을 원부여에 합산하지 않음",UUID.randomUUID()));
        var increased = accrual.preview(admin,worker.employeeId());
        assertEquals(1,increased.openingSources().getFirst().creditedDays().intValueExact());
        accrual.post(admin,included(increased.periods().getFirst(),partly));
        var after = accrual.preview(admin,worker.employeeId());
        assertEquals(422,failure(() -> accrual.post(admin,included(after.periods().get(1),partly))));
    }

    @Test void aPreviouslyLinkedOriginRemainsReadableWithZeroCapacityAfterAnImportedCorrection() {
        UUID source = opening(worker,"기존 이관 자료");
        accrual.post(admin,included(accrual.preview(admin,worker.employeeId()).periods().getFirst(),source));
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,
                    effective_on,source_reference,reason,created_by) VALUES (?,?,?,?,'ADJUSTMENT',-1,?,'과거 조정 원문','이관된 과거 감액',?)
                """,tenant,UUID.randomUUID(),source,worker.employeeId(),today,admin.accountId());
        var after = accrual.preview(admin,worker.employeeId());
        assertEquals(source,after.periods().getFirst().includedBucketId());
        assertEquals(source,after.openingSources().getFirst().bucketId());
        assertEquals(0,after.openingSources().getFirst().creditedDays().signum());
        assertEquals(1,after.openingSources().getFirst().linkedDays().intValueExact());
        assertEquals(422,failure(() -> accrual.post(admin,included(after.periods().get(1),source))));
        assertTrue(annual.ledger(worker,null).entries().stream().anyMatch(entry ->
                "INCLUDED".equals(entry.monthlyDecision()) && entry.sourceReference().contains("기존 이관 자료")));
    }

    private UUID opening(TnaPrincipal owner,String source,LocalDate last,int year) {
        return opening(owner,source,joined,last,year);
    }
    private UUID opening(TnaPrincipal owner,String source,LocalDate first,LocalDate last,int year) {
        UUID id = leave.openBucket(admin,new RequestData.BucketForm(owner.employeeId(),joined,first,last,
                BigDecimal.ONE,source,"확정한 이관 근거",UUID.randomUUID()));
        attributions.save(admin,id,new LeaveAttributionData.Form(0L,"CONFIRMED",year,true,"이관 연도","근거 확인",UUID.randomUUID()));
        return id;
    }
    private PostForm included(Period p,UUID bucket) {
        return new PostForm(worker.employeeId(),p.accrualOn(),"INCLUDED","유급 월 이미 전기 원장에 반영",p.token(),UUID.randomUUID(),bucket);
    }
    private static RequestData.RequestDay day(LocalDate date,double days) { return new RequestData.RequestDay(date,days,null,null,null,"FULL"); }
    private PostForm form(Period p,String decision) {
        return new PostForm(worker.employeeId(),p.accrualOn(),decision,"외부 출근부 확인",p.token(),UUID.randomUUID());
    }
    private int failure(Runnable command) { return assertThrows(OrgException.class,command::run).status(); }
    private TnaPrincipal member(String no, boolean manager,LocalDate hired) {
        return member(tenant,no,manager,hired);
    }
    private TnaPrincipal member(UUID company,String no, boolean manager,LocalDate hired) {
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES(?,?,?,?,?)",company,employee,no,"합성 "+no,hired);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES(?,?,?,?,'synthetic')",company,account,employee,account+"@example.test");
        var roles=manager?Set.of("EMPLOYEE","ATTENDANCE_ADMIN"):Set.of("EMPLOYEE");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES(?,?,?)",company,account,role);
        return new TnaPrincipal(company,account,employee,1,roles,Instant.now());
    }
}
