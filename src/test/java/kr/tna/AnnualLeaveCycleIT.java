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
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleData.*;
import kr.tna.request.AnnualLeaveCycleService;
import kr.tna.request.AnnualRequestData.Filter;
import kr.tna.request.AnnualRequestService;
import kr.tna.request.LeaveAttributionData.Form;
import kr.tna.request.LeaveAttributionService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.AdjustmentForm;
import kr.tna.request.RequestData.BucketForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AnnualLeaveCycleIT {
    @Autowired AnnualLeaveCycleService cycles;
    @Autowired LeaveWorkflowService workflow;
    @Autowired LeaveAttributionService attributions;
    @Autowired AnnualRequestService annual;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource appSource;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal admin, worker;
    LocalDate today, start;
    int year;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        start = today.withDayOfMonth(1);
        year = start.getYear();
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = company();
        admin = member(tenant, "담당", true, start.minusYears(3));
        worker = member(tenant, "직원", false, start);
    }

    @Test void previewConsumesOnlyConfirmedFinitePreviousResidualAndPreservesEveryLedger() {
        UUID one = bucket(worker, start.minusYears(1), start.minusDays(1), "2.5", year-1);
        UUID two = bucket(worker, start.minusMonths(6), start.minusDays(1), "4", year-1);
        UUID uncertain = bucket(worker, start.minusYears(1), start.minusDays(1), "5", null);
        UUID open = bucket(worker, start.minusYears(1), null, "1", year-1);
        UUID earlier = bucket(worker, start.minusYears(1), start.minusDays(2), "8", year-1);
        var existing = member(tenant, "기존", false, start);
        var existingBucket = bucket(existing, start, start.plusYears(1).minusDays(1), "3", year);
        member(tenant, "입사예정", false, start.plusDays(1));
        var left = member(tenant, "퇴직", false, start.minusYears(1));
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", start.minusDays(1), tenant, left.employeeId());
        var inactive = member(tenant, "비활성", false, start.minusYears(1));
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, inactive.employeeId());
        member(company(), "타사", false, start.minusYears(1));
        save("12.5", "3.5");
        var preview = cycles.preview(admin, year);
        assertTrue(preview.canPost());
        assertEquals(start, preview.periodStart());
        assertEquals(start.plusYears(1).minusDays(1), preview.periodEnd());
        assertEquals(1, preview.existingCount());
        assertEquals(1, preview.rows().size());
        var row = preview.rows().getFirst();
        assertEquals(worker.employeeId(), row.employeeId());
        days("6.5", row.previousDays());
        days("3.5", row.carryDays());
        days("3", row.expireDays());
        assertEquals(0, count("annual_leave_award"));
        var command = post(preview);
        var result = cycles.post(admin, command);
        assertEquals(result, cycles.post(admin, command));
        assertEquals(1, count("annual_leave_award"));
        assertFalse(cycles.preview(admin, year).canPost());
        assertEquals(1, cycles.preview(admin, year).postedCount());
        status(409, () -> cycles.post(admin, post(preview)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ANNUAL_LEAVE_POST'", Integer.class, tenant));
        var ledger = cycles.ledger(worker, year);
        days("17", ledger.availableDays());
        assertEquals(5, ledger.entries().stream().filter(e -> Set.of("GRANT","CARRY_IN","CARRY_OUT","EXPIRE").contains(e.entryKind())).count());
        assertTrue(ledger.entries().stream().allMatch(e -> "직원".equals(e.employeeNo())));
        assertTrue(ledger.entries().stream().filter(e -> "EXPIRE".equals(e.entryKind())).allMatch(e -> e.entitlementYear()==year-1));
        days("0", ledger.entries().stream().filter(e -> e.effectiveOn().equals(start) && e.entryKind().startsWith("CARRY"))
                .map(Entry::signedQuantityDays).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(0, balance(one));
        assertEquals(0, balance(two));
        assertEquals(5, balance(uncertain));
        assertEquals(1, balance(open));
        assertEquals(8, balance(earlier));
        assertEquals(3, balance(existingBucket));
        var target = ledger.entries().stream().filter(e -> "GRANT".equals(e.entryKind())).findFirst().orElseThrow().bucketId();
        assertEquals(16, balance(target));
        days("16", annual.view(worker, new Filter(year,null,"",0,0),null,null).annual().registeredDays());
        status(409, () -> attributions.save(admin, target, attribution(1, year-1)));
        status(409, () -> attributions.save(admin, one, attribution(1, year)));
        assertTrue(cycles.ledger(worker, year-1).entries().stream().anyMatch(e -> e.bucketId().equals(one) && e.entryKind().equals("CARRY_OUT")));
        assertTrue(cycles.ledger(existing, year).entries().stream().noneMatch(e -> e.bucketId().equals(target)));
    }

    @Test void policyRevisionsAndYearSnapshotKeepBothAdministratorsFromAwardingThemselves() {
        var other = member(tenant, "다른담당", true, start);
        var form = rule(0, start.getMonthValue(), "0", "0");
        var saved = cycles.saveRule(admin, form);
        assertEquals(saved, cycles.saveRule(admin, form));
        status(409, () -> cycles.saveRule(admin, rule(0, 1, "9", "0")));
        var preview = cycles.preview(admin, year);
        assertEquals(Set.of(other.employeeId(), worker.employeeId()),
                Set.copyOf(preview.rows().stream().map(Row::employeeId).toList()));
        status(409, () -> cycles.post(other, post(preview)));
        cycles.post(admin, post(preview));
        status(409, () -> cycles.saveRule(admin, rule(1, start.getMonthValue()%12+1, "9", "5")));
        cycles.saveRule(admin, rule(1, start.getMonthValue(), "8", "4"));
        assertEquals(2, cycles.rule(other).version());
        var skipped = cycles.preview(other, year);
        assertEquals(1, skipped.rule().version());
        assertEquals(List.of(admin.employeeId()), skipped.rows().stream().map(Row::employeeId).toList());
        cycles.post(other, post(skipped));
        assertEquals(3, count("annual_leave_award"));
        assertFalse(cycles.preview(other, year).canPost());
        assertEquals(0, cycles.ledger(admin, year).availableDays().signum());
        assertEquals(1, cycles.ledger(worker, year).entries().size());
        status(403, () -> cycles.preview(worker, year));
        status(403, () -> cycles.rule(worker));
        status(403, () -> cycles.saveRule(worker, rule(2, 2, "0", "0")));
        status(403, () -> cycles.post(worker, post(skipped)));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,admin.accountId());
        status(403, () -> cycles.post(admin, post(preview)));
    }

    @Test void retroactiveGrantsSkipOnlyEmployeesWhoseLaterYearWasAlreadyApplied() {
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", start.minusYears(2),tenant,worker.employeeId());
        save("2","1");
        cycles.post(admin,post(cycles.preview(admin,year)));
        var lateHireRecord=member(tenant,"이력확인",false,start.minusYears(2));
        var prior=cycles.preview(admin,year-1);
        assertEquals(1,prior.laterCount());
        assertEquals(List.of(lateHireRecord.employeeId()),prior.rows().stream().map(Row::employeeId).toList());
        cycles.post(admin,post(prior));
        assertEquals(2,count("annual_leave_award"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.annual_leave_award WHERE tenant_id=? AND employee_id=? AND entitlement_year=?",
                Integer.class,tenant,worker.employeeId(),year-1));
    }

    @Test void retryReturnsTheOriginallySavedRuleEvenAfterAnotherAdministratorRevisesIt() {
        var other=member(tenant,"둘째담당",true,start);
        var first=rule(0,1,"12","2.5");
        UUID saved=cycles.saveRule(admin,first);
        var receipt=cycles.savedRule(admin,saved);
        assertEquals(1,receipt.version());
        days("12",receipt.grantDays());
        cycles.saveRule(other,rule(1,1,"15","1"));
        assertEquals(2,cycles.rule(admin).version());
        assertEquals(receipt,cycles.savedRule(admin,cycles.saveRule(admin,first)));
        assertEquals(2,count("annual_leave_rule"));
        status(403,()->cycles.savedRule(worker,saved));
        var foreignAdmin=member(company(),"타사담당",true,start);
        status(404,()->cycles.savedRule(foreignAdmin,saved));
    }

    @Test void staleSourceEvenAtSameBalanceRuleOrAudienceInvalidatesThePreview() {
        save("2","1");
        UUID source = bucket(worker, start.minusYears(1), start.minusDays(1), "3", year-1);
        var before = cycles.preview(admin, year);
        adjust(source, "0.5");
        adjust(source, "-0.5");
        status(409, () -> cycles.post(admin, post(before)));
        var revised = cycles.preview(admin, year);
        cycles.saveRule(admin, rule(1, start.getMonthValue(), "2.5", "1"));
        status(409, () -> cycles.post(admin, post(revised)));
        var policy = cycles.preview(admin, year);
        member(tenant, "추가", false, start);
        status(409, () -> cycles.post(admin, post(policy)));
        assertEquals(0, count("annual_leave_award"));
        var latest = cycles.preview(admin, year);
        cycles.post(admin, post(latest));
        adjust(source, "0.5");
        days("0.5", db.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=?", BigDecimal.class, tenant, source));
        assertFalse(cycles.preview(admin, year).canPost());
        days("3.5", cycles.ledger(worker, year).availableDays());
    }

    @Test void duplicateConcurrentPostsWriteOneSetOfEntriesAndOneAudit() throws Exception {
        save("5", "2.5");
        var preview = cycles.preview(admin, year);
        var startBoth = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            assertTrue(startBoth.await(15, TimeUnit.SECONDS));
            try { cycles.post(admin, post(preview)); return 200; }
            catch (OrgException conflict) { return conflict.status(); }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(attempt);
            var two = pool.submit(attempt);
            startBoth.countDown();
            assertEquals(List.of(200,409), java.util.stream.Stream.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, count("annual_leave_award"));
        assertEquals(1, count("leave_ledger"));
    }

    @Test void invalidRulesAndFutureYearsCannotPost() {
        assertNull(cycles.rule(admin));
        assertNull(cycles.preview(admin, year).rule());
        assertFalse(cycles.preview(admin, year).canPost());
        for (String quantity : List.of("-0.5","0.25","100000.5")) {
            status(422, () -> cycles.saveRule(admin, rule(0,1,quantity,"0")));
            status(422, () -> cycles.saveRule(admin, rule(0,1,"0",quantity)));
        }
        status(422, () -> cycles.saveRule(admin, rule(0,13,"1","0")));
        save("100000.0", "0");
        var future = cycles.preview(admin, year+1);
        assertFalse(future.canPost());
        status(422, () -> cycles.post(admin, post(future)));
        status(422, () -> cycles.preview(admin, 1899));
        status(422, () -> cycles.ledger(worker, 9999));
        assertEquals(year,cycles.ledger(worker,null).year());
        if (today.getMonthValue()<12) {
            cycles.saveRule(admin, rule(1,today.getMonthValue()+1,"0","0"));
            assertEquals(year-1,cycles.ledger(worker,null).year());
        }
    }

    @Test void aLedgerFailureRollsBackTheYearAwardsAttributionsAndAuditAsAUnit() {
        save("3","1");
        bucket(worker, start.minusYears(1), start.minusDays(1), "2", year-1);
        var preview = cycles.preview(admin, year);
        var id = "cycle_test_" + tenant.toString().replace("-","");
        db.execute("ALTER TABLE tna.leave_ledger ADD CONSTRAINT " + id + " CHECK (tenant_id<>'"+tenant+"' OR entry_kind<>'CARRY_IN')");
        try { status(409, () -> cycles.post(admin, post(preview))); }
        finally { db.execute("ALTER TABLE tna.leave_ledger DROP CONSTRAINT " + id); }
        assertEquals(0, count("annual_leave_cycle"));
        assertEquals(0, count("annual_leave_award"));
        assertEquals(1, count("leave_bucket"));
        assertEquals(1, count("leave_ledger"));
        assertEquals(1, count("leave_bucket_attribution"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ANNUAL_LEAVE_POST'",Integer.class,tenant));
        assertEquals(preview.token(),cycles.preview(admin,year).token());
    }

    @Test void negativeImportedSourceRequiresReviewAndCreatesNoNewAwards() {
        save("1","1");
        UUID source=bucket(worker,start.minusYears(1),start.minusDays(1),"0.5",year-1);
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                    source_reference,reason,created_by) VALUES (?,?,?,?,'ADJUSTMENT',-1,?,'합성 이전 이력','음수 원장 검증',?)
                """,tenant,UUID.randomUUID(),source,worker.employeeId(),start.minusDays(1),admin.accountId());
        status(409,()->cycles.preview(admin,year));
        status(409,()->cycles.post(admin,new PostForm(year,"0".repeat(64),"음수 자료 검증",UUID.randomUUID())));
        assertEquals(0,count("annual_leave_award"));
    }

    @Test void annualTablesForceTenantIsolationAndOnlyAcceptNewRows() {
        save("0","0");
        cycles.post(admin,post(cycles.preview(admin,year)));
        var app = new JdbcTemplate(appSource);
        for (String table : List.of("annual_leave_rule","annual_leave_cycle","annual_leave_award")) {
            assertTrue(db.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=?::regclass", Boolean.class,"tna."+table));
            String count = "SELECT count(*) FROM tna."+table;
            assertEquals(1,transactions.execute(tenant,()->app.queryForObject(count,Integer.class)));
            assertEquals(0,transactions.execute(UUID.randomUUID(),()->app.queryForObject(count,Integer.class)));
            assertEquals(0,app.queryForObject(count,Integer.class));
            assertThrows(DataAccessException.class,()->transactions.execute(tenant,()->app.update("DELETE FROM tna."+table)));
            assertThrows(DataAccessException.class,()->transactions.execute(tenant,()->app.update("UPDATE tna."+table+" SET tenant_id=tenant_id")));
        }
        var foreign = new TnaPrincipal(company(),admin.accountId(),admin.employeeId(),1,admin.roles(),Instant.now());
        status(403,()->cycles.ledger(foreign,year));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",tenant,worker.accountId());
        status(403,()->cycles.ledger(worker,year));
    }

    @Test void movementReferencesAndHalfDaySignsAreEnforcedByPostgres() {
        UUID previous=bucket(worker,start.minusYears(1),start.minusDays(1),"0.5",year-1);
        save("0","0");
        cycles.post(admin,post(cycles.preview(admin,year)));
        UUID award=db.queryForObject("SELECT bucket_id FROM tna.annual_leave_award WHERE tenant_id=? AND employee_id=?",UUID.class,tenant,worker.employeeId());
        invalidMovement(previous,award,"CARRY_IN","1");
        invalidMovement(award,award,"CARRY_OUT","-1");
        invalidMovement(award,award,"CARRY_IN","-0.5");
        invalidMovement(award,award,"CARRY_IN","0.25");
        invalidMovement(previous,null,"EXPIRE","-1");
        assertEquals(3,count("leave_ledger"));
    }

    private void invalidMovement(UUID bucket,UUID award,String kind,String quantity) {
        var app=new JdbcTemplate(appSource);
        var error=assertThrows(DataAccessException.class,()->transactions.execute(tenant,()->app.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                    annual_award_bucket_id,source_reference,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),bucket,worker.employeeId(),kind,new BigDecimal(quantity),start,award,"합성 근거","조건 검증",admin.accountId())));
        assertEquals("23514",((java.sql.SQLException)error.getMostSpecificCause()).getSQLState());
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 연차 회사')",id,"CYCLE-"+id);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, boolean manager, LocalDate hired) {
        UUID employee=UUID.randomUUID(), account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",company,employee,number,"합성 "+number,hired);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')",company,account,employee,account+"@example.test");
        var roles=manager ? Set.of("EMPLOYEE","ATTENDANCE_ADMIN") : Set.of("EMPLOYEE");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",company,account,role);
        return new TnaPrincipal(company,account,employee,1,roles,Instant.now());
    }

    private UUID bucket(TnaPrincipal owner, LocalDate from, LocalDate to, String days, Integer attributed) {
        UUID bucket=workflow.openBucket(admin,new BucketForm(owner.employeeId(),from,from,to,new BigDecimal(days),"합성 기초","이전 원장",UUID.randomUUID()));
        if (attributed!=null) attributions.save(admin,bucket,attribution(0,attributed));
        return bucket;
    }

    private void adjust(UUID bucket,String value) {
        long version=db.queryForObject("SELECT version FROM tna.leave_bucket WHERE tenant_id=? AND id=?",Long.class,tenant,bucket);
        workflow.adjust(admin,bucket,new AdjustmentForm(new BigDecimal(value),today,version,"합성 조정","합성 후속 근거",UUID.randomUUID()));
    }
    private double balance(UUID bucket) {
        return db.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=?",Double.class,tenant,bucket);
    }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM tna."+table+" WHERE tenant_id=?",Integer.class,tenant); }
    private static Form attribution(long revision,int year) { return new Form(revision,"CONFIRMED",year,true,"확정 기초 자료","합성 확인",UUID.randomUUID()); }
    private static void status(int expected,Runnable action) { assertEquals(expected,assertThrows(OrgException.class,action::run).status()); }
    private static void days(String expected,BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual),()->expected+" != "+actual); }
    private static RuleForm rule(long version,int month,String grant,String cap) {
        return new RuleForm(version,month,new BigDecimal(grant),new BigDecimal(cap),"합성 회사 기준",UUID.randomUUID());
    }
    private void save(String grant,String cap) { cycles.saveRule(admin,rule(0,start.getMonthValue(),grant,cap)); }
    private static PostForm post(Preview preview) { return new PostForm(preview.year(),preview.token(),"합성 기장 확인",UUID.randomUUID()); }
}
