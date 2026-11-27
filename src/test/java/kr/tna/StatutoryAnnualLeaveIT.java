package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleData.*;
import kr.tna.request.AnnualLeaveCycleService;
import kr.tna.request.LeaveAttributionData;
import kr.tna.request.LeaveAttributionService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
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

/** 법정 연차 연간 부여(설계 2026-11-27 §3·§4.1·§5). 2026-03-10 서울 기준으로 고정한다. */
@SpringBootTest
class StatutoryAnnualLeaveIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    @TestConfiguration(proxyBeanMethods = false)
    static class Dates {
        @Bean @Primary Clock statutoryLeaveClock() { return Clock.fixed(TODAY.atTime(10, 0).atZone(SEOUL).toInstant(), SEOUL); }
    }
    @Autowired AnnualLeaveCycleService cycles;
    @Autowired LeaveWorkflowService workflow;
    @Autowired LeaveAttributionService attributions;
    @Autowired TenantTransactions transactions;
    @Autowired ClockService clocks;
    @Autowired WorktimeService worktime;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal admin;

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
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = company();
        admin = member(tenant, "H01", true, LocalDate.of(2020, 1, 1));
    }

    record Fiscal(TnaPrincipal a, TnaPrincipal b, TnaPrincipal c, TnaPrincipal e, AttendanceSeed seed) { }

    /** 1월 시작 회사. A 2025-07-01 입사(비례), B 근속 3년차, C 근태 없음, D 회계연도 시작일 입사, E 출근율 50%. */
    private Fiscal fiscal() {
        cycles.saveRule(admin, new RuleForm(0L, "FISCAL", 1, new BigDecimal("5"), "회계연도 기준", UUID.randomUUID()));
        var a = member(tenant, "A01", false, LocalDate.of(2025, 7, 1));
        var b = member(tenant, "B01", false, LocalDate.of(2023, 1, 1));
        var c = member(tenant, "C01", false, LocalDate.of(2020, 1, 2));
        member(tenant, "D01", false, LocalDate.of(2026, 1, 1));
        var e = member(tenant, "E01", false, LocalDate.of(2024, 3, 4));
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 3, 3), LocalDate.of(2025, 9, 1));
        seed.attend(a.employeeId());
        seed.attend(b.employeeId());
        seed.employee(e.employeeId(), Set.of(LocalDate.of(2025, 3, 3)), seed.workdays());
        return new Fiscal(a, b, c, e, seed);
    }

    @Test void fiscalPreviewProratesTheFirstYearAddsSeniorityAndHoldsBackUnconfirmedOrLowAttendance() {
        fiscal();
        var preview = cycles.preview(admin, 2026);
        assertEquals(LocalDate.of(2026, 1, 1), preview.periodStart());
        assertEquals(List.of("A01", "B01", "C01", "E01"), preview.rows().stream().map(Row::employeeNo).toList());
        var a = preview.rows().get(0);
        assertEquals(1, a.serviceYear());
        days("8", a.grantDays());
        assertEquals(LocalDate.of(2025, 7, 1), a.periodStart());
        assertEquals(LocalDate.of(2025, 12, 31), a.periodEnd());
        assertEquals(1, a.scheduledDays());
        assertEquals("GRANT", a.decision());
        days("100", a.attendanceRate());
        var b = preview.rows().get(1);
        assertEquals(3, b.serviceYear());
        days("16", b.grantDays());
        assertEquals(2, b.scheduledDays());
        assertEquals("GRANT", b.decision());
        var c = preview.rows().get(2);
        assertEquals(6, c.serviceYear());
        days("17", c.grantDays());
        assertEquals("NEEDS_REVIEW", c.decision());
        assertEquals("판정 불가 365일", c.reason());   // 근무조 배정이 없는 날도 판정할 수 없다(설계 §4)
        var e = preview.rows().get(3);
        assertEquals(2, e.serviceYear());
        days("15", e.grantDays());
        days("1", e.absenceDays());
        assertEquals("NEEDS_REVIEW", e.decision());
        assertEquals("출근율 50.0%", e.reason());
        assertEquals(2, preview.reviewCount());
        assertTrue(preview.canPost());
    }

    /** 일일 근태의 반일 결근은 0.5일이고, 출근율 정확히 80%는 부여 대상이다(설계 §4·§4.1). */
    @Test void halfDayAbsenceCountsAsHalfAndExactlyEightyPercentGrants() {
        cycles.saveRule(admin, new RuleForm(0L, "FISCAL", 1, BigDecimal.ZERO, "회계연도 기준", UUID.randomUUID()));
        var half = member(tenant, "F01", false, LocalDate.of(2023, 1, 1));
        var exact = member(tenant, "G01", false, LocalDate.of(2023, 1, 1));
        var mondays = List.of(LocalDate.of(2025, 3, 3), LocalDate.of(2025, 3, 10), LocalDate.of(2025, 3, 17),
                LocalDate.of(2025, 3, 24), LocalDate.of(2025, 3, 31));
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                mondays.toArray(LocalDate[]::new));
        seed.halfDayLeave(half, mondays.get(0));
        seed.employee(half.employeeId(), Set.of(mondays.get(0)), mondays);
        seed.employee(exact.employeeId(), Set.of(mondays.get(1)), mondays);
        var rows = cycles.preview(admin, 2026).rows();
        assertEquals(List.of("F01", "G01"), rows.stream().map(Row::employeeNo).toList());
        var f = rows.get(0);
        assertEquals(5, f.scheduledDays());
        days("0.5", f.absenceDays());
        days("90", f.attendanceRate());
        assertEquals("GRANT", f.decision());
        var g = rows.get(1);
        days("1", g.absenceDays());
        days("80", g.attendanceRate());
        assertEquals("GRANT", g.decision());
        assertNull(g.reason());
    }

    @Test void postingRecordsOnlyGrantRowsWithTheirEvidence() {
        var people = fiscal();
        cycles.post(admin, post(cycles.preview(admin, 2026)));
        assertEquals(2, count("annual_leave_award"));
        var award = db.queryForMap("""
                SELECT service_year,period_start,period_end,scheduled_days,absence_days,granted_days FROM tna.annual_leave_award
                WHERE tenant_id=? AND employee_id=?""", tenant, people.a().employeeId());
        assertEquals(1, award.get("service_year"));
        assertEquals(java.sql.Date.valueOf("2025-07-01"), award.get("period_start"));
        assertEquals(java.sql.Date.valueOf("2025-12-31"), award.get("period_end"));
        assertEquals(1, award.get("scheduled_days"));
        days("0", (BigDecimal) award.get("absence_days"));
        days("8", (BigDecimal) award.get("granted_days"));
        days("8", cycles.ledger(people.a(), 2026).availableDays());
        days("16", cycles.ledger(people.b(), 2026).availableDays());
        assertEquals(Boolean.FALSE, db.queryForObject("SELECT reviewed_by_admin FROM tna.annual_leave_award WHERE tenant_id=? AND employee_id=?",
                Boolean.class, tenant, people.a().employeeId()));
        var after = cycles.preview(admin, 2026);
        assertEquals(2, after.postedCount());
        assertEquals(List.of("C01", "E01"), after.rows().stream().map(Row::employeeNo).toList());
        assertTrue(after.canPost());
        status(422, () -> cycles.post(admin, post(after)));   // 확인 필요 행을 고르지 않으면 반영할 사원이 없다
    }

    @Test void reviewedRowsArePostedWithStatutoryDaysOnlyWhenTheAdministratorChecksThem() {
        var people = fiscal();
        cycles.post(admin, post(cycles.preview(admin, 2026), people.e().employeeId()));
        assertEquals(3, count("annual_leave_award"));
        var award = db.queryForMap("""
                SELECT reviewed_by_admin,service_year,scheduled_days,absence_days,granted_days FROM tna.annual_leave_award
                WHERE tenant_id=? AND employee_id=?""", tenant, people.e().employeeId());
        assertEquals(true, award.get("reviewed_by_admin"));
        assertEquals(2, award.get("service_year"));
        assertEquals(2, award.get("scheduled_days"));
        days("1", (BigDecimal) award.get("absence_days"));
        days("15", (BigDecimal) award.get("granted_days"));
        var ledger = cycles.ledger(people.e(), 2026);
        days("15", ledger.availableDays());
        assertTrue(ledger.entries().stream().anyMatch(e -> "GRANT".equals(e.entryKind())
                && e.sourceReference().contains("담당자 확인(출근율 50.0%)")));
        var after = cycles.preview(admin, 2026);
        assertEquals(List.of("C01"), after.rows().stream().map(Row::employeeNo).toList());
        status(409, () -> cycles.post(admin, post(after, people.a().employeeId())));   // 이미 반영한 사원
        status(409, () -> cycles.post(admin, post(after, UUID.randomUUID())));         // 미리보기에 없는 사원
        status(422, () -> cycles.post(admin, new PostForm(2026, null, after.token(), "빈 값", UUID.randomUUID(),
                java.util.Arrays.asList((UUID) null))));
        cycles.post(admin, post(after, people.c().employeeId()));   // 근태가 없는 사원도 담당자가 확인하면 법정 일수
        days("17", cycles.ledger(people.c(), 2026).availableDays());
        assertTrue(cycles.preview(admin, 2026).rows().isEmpty());
    }

    @Test void attendanceConfirmedAfterThePreviewRejectsThePostAndThenGrants() {
        var people = fiscal();
        var before = cycles.preview(admin, 2026);
        people.seed().attend(people.c().employeeId());
        status(409, () -> cycles.post(admin, post(before)));
        var latest = cycles.preview(admin, 2026);
        assertEquals("GRANT", latest.rows().get(2).decision());
        cycles.post(admin, post(latest));
        assertEquals(3, count("annual_leave_award"));
    }

    @Test void anotherCompanyOrAnEmployeeCannotPreviewOrPostTheseAwards() {
        var people = fiscal();
        var preview = cycles.preview(admin, 2026);
        status(403, () -> cycles.preview(people.a(), 2026));
        status(403, () -> cycles.post(people.a(), post(preview)));
        var foreign = member(company(), "X01", true, LocalDate.of(2020, 1, 1));
        cycles.saveRule(foreign, new RuleForm(0L, "FISCAL", 1, BigDecimal.ONE, "타사 기준", UUID.randomUUID()));
        var foreignPreview = cycles.preview(foreign, 2026);
        assertTrue(foreignPreview.rows().isEmpty());
        status(409, () -> cycles.post(foreign, post(preview)));
        status(409, () -> cycles.post(foreign, post(foreignPreview, people.c().employeeId())));   // 다른 회사 사원은 고를 수 없다
        assertEquals(0, count("annual_leave_award"));
    }

    record Hire(TnaPrincipal f, TnaPrincipal g, TnaPrincipal i, UUID opening) { }

    /** 입사일 기준, 이월 한도 3일. F 2025-03-02 입사(1주년 3-02), G 2024-02-29 입사(2주년 3-01, 전기 5일), H 3-20 기념일(미도래), I 2025-02-10 입사. */
    private Hire hire() {
        cycles.saveRule(admin, new RuleForm(0L, "HIRE_DATE", null, new BigDecimal("3"), "입사일 기준", UUID.randomUUID()));
        var f = member(tenant, "F01", false, LocalDate.of(2025, 3, 2));
        var g = member(tenant, "G01", false, LocalDate.of(2024, 2, 29));
        member(tenant, "H02", false, LocalDate.of(2025, 3, 20));
        var i = member(tenant, "I01", false, LocalDate.of(2025, 2, 10));
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(2025, 2, 10), LocalDate.of(2026, 3, 9),
                LocalDate.of(2025, 6, 2));
        for (var person : List.of(f, g, i)) seed.attend(person.employeeId());
        UUID opening = workflow.openBucket(admin, new BucketForm(g.employeeId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 1),
                LocalDate.of(2026, 2, 28), new BigDecimal("5"), "합성 1년차 연차", "이전 원장", UUID.randomUUID()));
        attributions.save(admin, opening, new LeaveAttributionData.Form(0L, "CONFIRMED", 2025, true, "확정 기초 자료", "합성 확인", UUID.randomUUID()));
        return new Hire(f, g, i, opening);
    }

    @Test void hireDateMonthlyPostingGrantsOnEachAnniversaryAndSettlesThePreviousYear() {
        var people = hire();
        var march = cycles.previewMonth(admin, YearMonth.of(2026, 3));
        assertEquals("2026-03", march.month());
        assertEquals(LocalDate.of(2026, 3, 1), march.periodStart());
        assertEquals(TODAY, march.periodEnd());
        assertEquals(List.of("F01", "G01"), march.rows().stream().map(Row::employeeNo).toList());
        var f = march.rows().get(0);
        assertEquals(LocalDate.of(2026, 3, 2), f.grantOn());
        assertEquals(1, f.serviceYear());
        days("15", f.grantDays());
        assertEquals(LocalDate.of(2025, 3, 2), f.periodStart());
        assertEquals(LocalDate.of(2026, 3, 1), f.periodEnd());
        assertEquals("GRANT", f.decision());
        var g = march.rows().get(1);
        assertEquals(LocalDate.of(2026, 3, 1), g.grantOn());
        assertEquals(2, g.serviceYear());
        days("5", g.previousDays());
        days("3", g.carryDays());
        days("2", g.expireDays());
        cycles.post(admin, post(march));
        var buckets = db.queryForList("""
                SELECT b.usable_from,b.usable_to,w.entitlement_year FROM tna.annual_leave_award w
                JOIN tna.leave_bucket b ON b.tenant_id=w.tenant_id AND b.id=w.bucket_id
                JOIN tna.employee e ON e.tenant_id=w.tenant_id AND e.id=w.employee_id
                WHERE w.tenant_id=? ORDER BY e.employee_no""", tenant);
        assertEquals(List.of(java.sql.Date.valueOf("2026-03-02"), java.sql.Date.valueOf("2026-03-01")), buckets.stream().map(r -> r.get("usable_from")).toList());
        assertEquals(List.of(java.sql.Date.valueOf("2027-03-01"), java.sql.Date.valueOf("2027-02-28")), buckets.stream().map(r -> r.get("usable_to")).toList());
        assertTrue(buckets.stream().allMatch(r -> Integer.valueOf(2026).equals(r.get("entitlement_year"))));
        assertEquals(-2.0, db.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=? AND entry_kind='EXPIRE'",
                Double.class, tenant, people.opening()));
        days("18", cycles.ledger(people.g(), null).availableDays());
        assertEquals(2, cycles.previewMonth(admin, YearMonth.of(2026, 3)).postedCount());
        var february = cycles.previewMonth(admin, YearMonth.of(2026, 2));
        assertEquals(List.of("I01"), february.rows().stream().map(Row::employeeNo).toList());
        status(409, () -> cycles.post(admin, post(february, people.i().employeeId())));   // 부여 대상은 담당자 확인으로 고를 수 없다
        cycles.post(admin, post(february));
        assertEquals(3, count("annual_leave_award"));
        var future = assertThrows(OrgException.class, () -> cycles.previewMonth(admin, YearMonth.of(2026, 4)));
        assertEquals(422, future.status());
        assertEquals("이번 달까지만 미리 볼 수 있습니다.", future.getMessage());
        status(422, () -> cycles.post(admin, new PostForm(null, "2026-04", march.token(), "다음 달 반영 시도", UUID.randomUUID(), null)));
        assertEquals(3, count("annual_leave_award"));
    }

    @Test void hireDateMonthIncludesAnEmployeeWhoLeftAfterTheAnniversaryButNotBefore() {
        hire();
        var stayed = member(tenant, "L01", false, LocalDate.of(2025, 2, 12));
        var gone = member(tenant, "L02", false, LocalDate.of(2025, 2, 14));
        db.update("UPDATE tna.employee SET left_on=?,active=false WHERE tenant_id=? AND id=?", LocalDate.of(2026, 2, 20), tenant, stayed.employeeId());
        db.update("UPDATE tna.employee SET left_on=?,active=false WHERE tenant_id=? AND id=?", LocalDate.of(2026, 2, 13), tenant, gone.employeeId());
        var february = cycles.previewMonth(admin, YearMonth.of(2026, 2));
        assertEquals(List.of("I01", "L01"), february.rows().stream().map(Row::employeeNo).toList());
        assertEquals(LocalDate.of(2026, 2, 12), february.rows().get(1).grantOn());
    }

    @Test void hireDateLedgerUsesTheAnniversaryYearAndTheWrongPeriodKindIsRejected() {
        var people = hire();
        var march = cycles.previewMonth(admin, YearMonth.of(2026, 3));
        cycles.post(admin, post(march));
        var ledger = cycles.ledger(people.f(), null);
        assertEquals(2026, ledger.year());
        assertEquals("HIRE_DATE", ledger.basis());
        assertEquals(LocalDate.of(2026, 3, 2), ledger.periodStart());
        assertEquals(LocalDate.of(2027, 3, 1), ledger.periodEnd());
        days("15", ledger.availableDays());
        assertTrue(ledger.entries().stream().anyMatch(e -> "GRANT".equals(e.entryKind())));
        var previous = cycles.ledger(people.f(), 2025);
        assertEquals(LocalDate.of(2025, 3, 2), previous.periodStart());
        assertEquals(LocalDate.of(2026, 3, 1), previous.periodEnd());
        status(422, () -> cycles.preview(admin, 2026));   // 입사일 기준 회사는 연도 미리보기·반영을 할 수 없다
        status(422, () -> cycles.post(admin, new PostForm(2026, march.token(), "연도 반영 시도", UUID.randomUUID())));
        status(422, () -> cycles.previewMonth(admin, YearMonth.of(1899, 12)));
        var fiscalAdmin = member(company(), "X02", true, LocalDate.of(2020, 1, 1));
        cycles.saveRule(fiscalAdmin, new RuleForm(0L, "FISCAL", 1, BigDecimal.ONE, "회계연도 기준", UUID.randomUUID()));
        status(422, () -> cycles.previewMonth(fiscalAdmin, YearMonth.of(2026, 3)));   // 회계연도 기준 회사는 월 미리보기·반영을 할 수 없다
        status(422, () -> cycles.post(fiscalAdmin, new PostForm(null, "2026-03", "0".repeat(64), "월 반영 시도", UUID.randomUUID(), null)));
        var ledgerOfFiscal = cycles.ledger(fiscalAdmin, 2026);
        assertEquals("FISCAL", ledgerOfFiscal.basis());
        assertEquals(LocalDate.of(2026, 1, 1), ledgerOfFiscal.periodStart());
        assertEquals(LocalDate.of(2026, 12, 31), ledgerOfFiscal.periodEnd());
        status(409, () -> cycles.saveRule(admin, new RuleForm(1L, "FISCAL", 1, new BigDecimal("3"), "기준 변경 시도", UUID.randomUUID())));
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 법정 연차 회사')", id, "STAT-" + id);
        return id;
    }
    private TnaPrincipal member(UUID company, String number, boolean manager, LocalDate hired) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)", company, employee, number, "합성 " + number, hired);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')", company, account, employee, account + "@example.test");
        var roles = manager ? Set.of("EMPLOYEE", "ATTENDANCE_ADMIN") : Set.of("EMPLOYEE");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, role);
        return new TnaPrincipal(company, account, employee, 1, roles, Instant.now());
    }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private static void status(int expected, Runnable action) { assertEquals(expected, assertThrows(OrgException.class, action::run).status()); }
    private static void days(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual); }
    private static PostForm post(Preview preview, UUID... reviewed) {
        return new PostForm(preview.year(), preview.month(), preview.token(), "합성 법정 연차 반영", UUID.randomUUID(), List.of(reviewed));
    }
}
