package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import kr.tna.request.LeaveAttributionService;
import kr.tna.request.LeaveWorkflowService;
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
        assertTrue(c.reason().startsWith("근태 확정 전 "), c.reason());
        var e = preview.rows().get(3);
        assertEquals(2, e.serviceYear());
        days("15", e.grantDays());
        days("1", e.absenceDays());
        assertEquals("NEEDS_REVIEW", e.decision());
        assertEquals("출근율 50.0%", e.reason());
        assertEquals(2, preview.reviewCount());
        assertTrue(preview.canPost());
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
        status(422, () -> cycles.post(admin, new PostForm(2026, after.token(), "빈 값", UUID.randomUUID(),
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
        return new PostForm(preview.year(), preview.token(), "합성 법정 연차 반영", UUID.randomUUID(), List.of(reviewed));
    }
}
