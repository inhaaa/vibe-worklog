package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.request.*;
import kr.tna.request.LeaveExitData.PostForm;
import kr.tna.request.LeaveExitData.Preview;
import kr.tna.request.RequestData.AdjustmentForm;
import kr.tna.request.RequestData.BucketForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class LeaveExitServiceIT {
    @Autowired LeaveExitService exits;
    @Autowired LeaveWorkflowService workflow;
    @Autowired EmploymentAccrualService monthlyAccrual;
    @Autowired LeaveBalanceBatchService batch;
    @Autowired LeaveAttributionService attributes;
    @Autowired AnnualLeaveCycleService annual;
    @Autowired TenantTransactions transactions;
    @Autowired javax.sql.DataSource appSource;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal manager, worker;
    LocalDate today;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void setup() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tenant = company();
        manager = member(tenant, "담당자", today.minusYears(3), null, true);
        worker = member(tenant, "신입", today.minusWeeks(2), today, false);
    }

    @Test void acceptsTheLastWorkingDayAndPostsOneImmutableReceiptForAllBuckets() {
        UUID first = bucket(worker, "2", true, today.plusDays(10));
        UUID second = bucket(worker, "1.5", true, null);
        var comparison = member(tenant, "동료", today.minusDays(9), null, false);
        bucket(comparison, "8", true, null);
        adjust(first, "0.5");
        var before = preview();
        assertTrue(before.canPost());
        assertEquals(2,before.rows().size());
        assertTrue(before.rows().stream().allMatch(r -> r.status().equals("READY")));
        days("4", before.balanceDays());
        days("4", before.settlementDays());
        days("4", annual.ledger(worker,null).availableDays());
        var command = post(before,false);
        UUID receipt = exits.post(manager,command);
        assertEquals(receipt,exits.post(manager,command));
        var after = preview();
        assertEquals(receipt,after.settlementId());
        assertFalse(after.canPost());
        assertEquals(command.reason(),after.reason());
        assertEquals(before.rows().size(),after.rows().size());
        days("4",after.balanceDays());
        days("4",after.settlementDays());
        var own = annual.ledger(worker,null);
        days("0",own.availableDays());
        var movements = own.entries().stream().filter(e -> "EXIT_SETTLEMENT".equals(e.entryKind())).toList();
        assertEquals(2,movements.size());
        days("-4",movements.stream().map(AnnualLeaveCycleData.Entry::signedQuantityDays).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertTrue(movements.stream().allMatch(e -> e.reason().equals(command.reason()) && e.effectiveOn().equals(today)));
        for (var row : before.rows()) {
            var debit=movements.stream().filter(e -> e.bucketId().equals(row.bucketId())).findFirst().orElseThrow();
            assertEquals(row.sourceReference(),debit.sourceReference());
            days(row.settlementDays().negate().toPlainString(),debit.signedQuantityDays());
        }
        days("8",annual.ledger(comparison,null).availableDays());
        status(409, () -> exits.post(manager,post(before,false)));
        status(409, () -> adjust(second,"0.5"));
        status(409, () -> bucket(worker,"1",true,null));
        status(409, () -> attributes.save(manager,first,attribution(1)));
        db.update("UPDATE tna.employee SET display_name='인사 변경' WHERE tenant_id=? AND id=?",tenant,worker.employeeId());
        assertEquals(before.employeeName(),preview().employeeName());
    }

    @Test void zeroDaysStillHaveAReceiptAndOlderImportedHistoryNeedsExplicitReconciliation() {
        var longService = member(tenant,"기존이관",today.minusYears(4),today.minusDays(1),false);
        var reviewed = exits.preview(manager,longService.employeeId());
        assertEquals(11,reviewed.missingMonths().size());
        assertTrue(reviewed.priorHistoryReviewRequired());
        assertTrue(reviewed.canPost());
        status(422, () -> exits.post(manager,post(reviewed,false)));
        var command = post(reviewed,true);
        UUID receipt=exits.post(manager,command);
        var result=exits.preview(manager,longService.employeeId());
        assertEquals(receipt,result.settlementId());
        days("0", result.settlementDays());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE tenant_id=? AND employee_id=?",
                Integer.class,tenant,longService.employeeId()));
    }

    @Test void anUnreviewedCompletedMonthBlocksARecentHireButAnUncompletedMonthDoesNot() {
        var monthly = member(tenant,"완료개월",today.minusMonths(2),today,false);
        var missing = exits.preview(manager,monthly.employeeId());
        assertFalse(missing.canPost());
        assertFalse(missing.priorHistoryReviewRequired());
        assertEquals(2,missing.missingMonths().size());
        status(409, () -> exits.post(manager,post(missing,true)));
        assertTrue(preview().canPost());
        var future=member(tenant,"퇴사예정",today.minusDays(3),today.plusMonths(2),false);
        var futurePreview=exits.preview(manager,future.employeeId());
        assertFalse(futurePreview.canPost());
        assertTrue(futurePreview.missingMonths().isEmpty());
        var employed=member(tenant,"재직자",today.minusDays(3),null,false);
        assertFalse(exits.preview(manager,employed.employeeId()).canPost());
    }

    @Test void afterTheFirstAnniversaryEvenCompleteMonthlyRecordsNeedAComparisonOfAnnualEntitlements() {
        LocalDate joined=today.minusYears(1);
        var member=member(tenant,"1주년",joined,today,false);
        UUID bucket=bucket(member,"0",true,null);
        for (var window : EmploymentAccrualData.periods(joined,today))
            insertNotFullReview(member,bucket,joined,window);
        var before=exits.preview(manager,member.employeeId());
        assertTrue(before.missingMonths().isEmpty());
        assertTrue(before.priorHistoryReviewRequired());
        assertTrue(before.canPost());
        status(422,()->exits.post(manager,post(before,false)));
        var receipt=exits.post(manager,post(before,true));
        assertEquals(receipt,exits.preview(manager,member.employeeId()).settlementId());
    }

    @Test void aChangedHireOrExitDateCannotDiscardAnAlreadyReviewedMonthlyPeriod() {
        UUID b=bucket(worker,"0",true,null);
        var previousHire=today.minusMonths(2);
        insertNotFullReview(worker,b,previousHire,EmploymentAccrualData.periods(previousHire,today).getFirst());
        var conflicting=preview();
        assertFalse(conflicting.canPost());
        assertTrue(conflicting.blockers().stream().anyMatch(text -> text.contains("재직 기간")));
        status(409,()->exits.post(manager,post(conflicting,true)));
    }

    @Test void staleOrConflictedBalancesBlockAllRowsAndOtherCompaniesCannotReview() {
        UUID confirmed=bucket(worker,"1",true,null);
        var stale=preview();
        adjust(confirmed,"0.5");
        adjust(confirmed,"-0.5");
        status(409, () -> exits.post(manager,post(stale,false)));
        bucket(worker,"2",false,null);
        bucket(worker,"0.5",true,today.minusDays(1));
        var blocked=preview();
        assertFalse(blocked.canPost());
        days("3.5",blocked.balanceDays());
        days("1",blocked.settlementDays());
        assertTrue(blocked.rows().stream().anyMatch(s -> s.status().equals("UNKNOWN")));
        assertTrue(blocked.rows().stream().anyMatch(s -> s.status().equals("EXPIRED")));
        status(409, () -> exits.post(manager,post(blocked,false)));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.leave_exit_settlement WHERE tenant_id=?",Integer.class,tenant));
        var foreign=member(company(),"외부",today.minusYears(1),null,true);
        status(404, () -> exits.preview(foreign,worker.employeeId()));
        status(403, () -> exits.preview(worker,worker.employeeId()));
        status(403, () -> exits.preview(manager,manager.employeeId()));
    }

    @Test void pendingUsageAndEffectiveFutureAbsenceBlockUntilReconciled() {
        worker=member(tenant,"기초사용 사원",today.minusMonths(1),today,false);
        UUID b=bucket(worker,"1.5",true,null);
        include(worker,b);
        UUID assignment=UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 승인자',?,?)
                """,tenant,assignment,worker.employeeId(),manager.employeeId(),today.minusWeeks(2),manager.accountId(),manager.accountId());
        UUID request=UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                    approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,'LEAVE_DAY','PENDING',?,?,0,'합성 신청',?,?,?,?,(SELECT v.id FROM tna.attendance_type_version v
                     JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id WHERE t.tenant_id=?
                         AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1))
                """,tenant,request,worker.employeeId(),worker.accountId(),manager.employeeId(),assignment,b,today.plusDays(1),today.plusDays(1),UUID.randomUUID(),tenant);
        assertFalse(preview().canPost());
        db.update("UPDATE tna.approval_request SET status='APPROVED' WHERE tenant_id=? AND id=?",tenant,request);
        UUID effect=UUID.randomUUID();
        db.update("""
                INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,effect_status,validated_input_refs,created_by)
                VALUES (?,?,?,?,'LEAVE','EFFECTIVE','[]',?)
                """,tenant,effect,request,worker.employeeId(),manager.accountId());
        UUID usage=UUID.randomUUID();
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                    request_effect_id,leave_date,reason,created_by) VALUES (?,?,?,?,'USE',-0.5,?,?,?,'합성 차감',?)
                """,tenant,usage,b,worker.employeeId(),today,effect,today.plusDays(1),manager.accountId());
        var future=preview();
        assertFalse(future.canPost());
        days("0.5",future.rows().getFirst().usedDays());
        adjust(b,"-0.5");
        adjust(b,"0.5");
        db.update("UPDATE tna.request_effect SET effect_status='CANCELED' WHERE tenant_id=? AND id=?",tenant,effect);
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                    request_effect_id,leave_date,reverses_entry_id,reason,created_by) VALUES (?,?,?,?,'RESTORE',0.5,?,?,?,?,'합성 복원',?)
                """,tenant,UUID.randomUUID(),b,worker.employeeId(),today,effect,today.plusDays(1),usage,manager.accountId());
        var repaired=preview();
        assertTrue(repaired.canPost());
        days("1.5",repaired.settlementDays());
        status(422,()->adjust(b,"-1"));
    }

    @Test void anInactiveFormerEmployeeCanBeSettledAfterPostExitAdjustmentsAreReconciled() {
        var former=member(tenant,"전일퇴사",today.minusWeeks(2),today.minusDays(1),false);
        UUID b=bucket(former,"1.5",true,null);
        adjust(b,"0.5");
        var afterExit=exits.preview(manager,former.employeeId());
        assertFalse(afterExit.canPost());
        assertEquals("AFTER_EXIT",afterExit.rows().getFirst().status());
        days("2",afterExit.balanceDays());
        adjust(b,"-0.5");
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?",tenant,former.employeeId());
        var corrected=exits.preview(manager,former.employeeId());
        assertTrue(corrected.canPost());
        days("1.5",corrected.settlementDays());
        assertNotNull(exits.post(manager,post(corrected,false)));
        status(403,()->annual.ledger(former,null));
    }

    @Test void aFailedDebitRollsBackTheReceiptAndEveryOtherBucket() {
        bucket(worker,"1",true,null);
        UUID last=bucket(worker,"0.5",true,null);
        var before=preview();
        var command=post(before,false);
        var constraint="exit_fixture_"+tenant.toString().replace("-","");
        db.execute("ALTER TABLE tna.leave_ledger ADD CONSTRAINT "+constraint+
                " CHECK (bucket_id<>'"+last+"' OR entry_kind<>'EXIT_SETTLEMENT')");
        try { status(409,()->exits.post(manager,command)); }
        finally { db.execute("ALTER TABLE tna.leave_ledger DROP CONSTRAINT "+constraint); }
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.leave_exit_settlement WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE tenant_id=? AND entry_kind='EXIT_SETTLEMENT'",Integer.class,tenant));
        assertEquals(before.token(),preview().token());
        assertNotNull(exits.post(manager,command));
    }

    @Test void independentCommandsCanOnlyPostOnceAndTheReceiptIsTenantScoped() throws Exception {
        bucket(worker,"2.5",true,null);
        var before=preview();
        var start=new CountDownLatch(1);
        Callable<Integer> attempt=()->{
            assertTrue(start.await(15,TimeUnit.SECONDS));
            try { exits.post(manager,post(before,false)); return 200; }
            catch (OrgException error) { return error.status(); }
        };
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=threads.submit(attempt);
            var second=threads.submit(attempt);
            start.countDown();
            assertEquals(List.of(200,409), java.util.stream.Stream.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)).sorted().toList());
        }
        var app=new JdbcTemplate(appSource);
        String count="SELECT count(*) FROM tna.leave_exit_settlement WHERE employee_id=?";
        assertEquals(1,transactions.execute(tenant,()->app.queryForObject(count,Integer.class,worker.employeeId())));
        assertEquals(0,transactions.execute(UUID.randomUUID(),()->app.queryForObject(count,Integer.class,worker.employeeId())));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,manager.accountId());
        status(403,()->preview());
    }

    @Test void manualReductionCannotRemoveAnAlreadyLinkedSourceDayFromExitSettlement() {
        var owner=member(tenant,"연결사원",today.minusMonths(1),today,false);
        UUID source=bucket(owner,"1",true,null);
        include(owner,source);
        adjust(source,"0.5");
        adjust(source,"-0.5");
        UUID other=bucket(worker,"1",true,null);
        adjust(other,"-1");
        var error=assertThrows(OrgException.class,()->adjust(source,"-0.5"));
        assertEquals(422,error.status());
        assertTrue(error.getMessage().contains("연결"));
        var settlement=exits.preview(manager,owner.employeeId());
        assertTrue(settlement.canPost());
        days("1",settlement.settlementDays());
        assertNotNull(exits.post(manager,post(settlement,false)));
        days("0",annual.ledger(worker,null).availableDays());
    }

    @Test void bulkRechecksAStalePreviewAndReportsTheLinkedSourceBeforeWritingAnyRows() {
        var owner=member(tenant,"일괄사원",today.minusMonths(1),today,false);
        UUID source=bucket(owner,"1",true,null);
        UUID other=bucket(worker,"1",true,null);
        var form=new LeaveBalanceBatchService.Form(UUID.randomUUID(),List.of(
                adjustmentRow("동료행","신입",other,"-1"),adjustmentRow("연결행","일괄사원",source,"-0.5")));
        assertTrue(batch.preview(manager,form).valid());
        include(owner,source);
        var latest=batch.preview(manager,form);
        assertFalse(latest.valid());
        assertTrue(latest.rows().getFirst().errors().isEmpty());
        var error=latest.rows().getLast().errors().getFirst();
        assertEquals("quantityDays",error.field());
        assertTrue(error.message().contains("연결"));
        status(422,()->batch.save(manager,form));
        days("1",annual.ledger(worker,null).availableDays());
        days("1",annual.ledger(owner,null).availableDays());
    }

    private void include(TnaPrincipal owner,UUID source) {
        var period=monthlyAccrual.preview(manager,owner.employeeId()).periods().getFirst();
        monthlyAccrual.post(manager,new EmploymentAccrualData.PostForm(owner.employeeId(),period.accrualOn(),
                "INCLUDED","첫 개근월 1일의 기초 이관 확인",period.token(),UUID.randomUUID(),source));
    }
    private LeaveBalanceBatchService.Row adjustmentRow(String row,String employeeNo,UUID bucket,String days) {
        return new LeaveBalanceBatchService.Row(row,"ADJUSTMENT",employeeNo,today.toString(),days,
                "이관 일수 정정","기초 이관 원본 확인",null,null,bucket.toString(),"0");
    }
    private Preview preview() { return exits.preview(manager,worker.employeeId()); }
    private void insertNotFullReview(TnaPrincipal employee,UUID bucket,LocalDate joined,EmploymentAccrualData.Window w) {
        db.update("""
                INSERT INTO tna.leave_monthly_review(tenant_id,employee_id,id,joined_on,month_number,period_start,
                    period_end,accrual_on,usable_to,entitlement_year,scheduled_days,attended_days,leave_days,
                    unresolved_days,decision,granted_days,bucket_id,evidence_token,reason,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,0,0,0,0,'NOT_FULL',0,?,?,'기존 월별 확인 자료',?)
                """,tenant,employee.employeeId(),UUID.randomUUID(),joined,w.monthNumber(),w.periodStart(),w.periodEnd(),w.accrualOn(),
                EmploymentAccrualData.accrualOn(joined,12).minusDays(1),w.accrualOn().getYear(),bucket,"a".repeat(64),manager.accountId());
    }
    private PostForm post(Preview p,boolean history) {
        return new PostForm(p.employeeId(),p.token(),"합성 원장과 부여·사용 대조",UUID.randomUUID(),history);
    }
    private UUID company() {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 퇴사정산 회사')",id,"EXIT-"+id);
        return id;
    }
    private TnaPrincipal member(UUID t,String number,LocalDate joined,LocalDate left,boolean admin) {
        UUID e=UUID.randomUUID(),a=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",t,e,number,number,joined,left);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')",t,a,e,a+"@example.test");
        var roles=admin?Set.of("EMPLOYEE","ATTENDANCE_ADMIN"):Set.of("EMPLOYEE");
        for(String role:roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES(?,?,?)",t,a,role);
        return new TnaPrincipal(t,a,e,1,roles,Instant.now());
    }
    private UUID bucket(TnaPrincipal owner,String quantity,boolean confirmed,LocalDate expiry) {
        UUID id=workflow.openBucket(manager,new BucketForm(owner.employeeId(),today.minusWeeks(2),today.minusWeeks(2),expiry,
                new BigDecimal(quantity),"합성 기초 "+quantity+"일 근거","합성 원장 이관",UUID.randomUUID()));
        if(confirmed) attributes.save(manager,id,attribution(0));
        return id;
    }
    private LeaveAttributionData.Form attribution(long version) {
        return new LeaveAttributionData.Form(version,"CONFIRMED",today.getYear(),true,"합성 귀속 근거","합성 확인",UUID.randomUUID());
    }
    private void adjust(UUID b,String amount) {
        long version=db.queryForObject("SELECT version FROM tna.leave_bucket WHERE tenant_id=? AND id=?",Long.class,tenant,b);
        workflow.adjust(manager,b,new AdjustmentForm(new BigDecimal(amount),today,version,"합성 보정","합성 사유",UUID.randomUUID()));
    }
    private static void status(int expected,Runnable action) { assertEquals(expected,assertThrows(OrgException.class,action::run).status()); }
    private static void days(String expected,BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual)); }
}
