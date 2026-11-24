package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.clock.HolidayService;
import kr.tna.clock.HolidayService.HolidayProblem;
import kr.tna.clock.HolidayService.RowInput;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class HolidayServiceIT {
    @Autowired HolidayService holidays;
    @Autowired ClockService clocks;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal manager;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    private TnaPrincipal member(String number, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴일 관리')", tenant, "HOLIDAY-" + tenant);
        manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    static RowInput input(String id, String date, String name, String category, String basis) {
        return new RowInput(id, date, name, category, basis);
    }

    static HolidayService.Form form(UUID command, int year, long version, RowInput... rows) {
        return new HolidayService.Form(command, "휴일 정리", year, version, List.of(rows));
    }

    int count(String sql) { return db.queryForObject(sql, Integer.class, tenant); }

    @Test void firstSaveCoversTheYearOnceAndReplaysTheSameResult() {
        var form = form(UUID.randomUUID(), 2027, 0L,
                input("a", "2027-01-01", "1월 1일", "PUBLIC", "공휴일"),
                input("b", "2027-03-02", "창립기념일", "COMPANY", " "));
        var first = holidays.save(manager, form);
        assertEquals(first, holidays.save(manager, form));
        assertEquals(1, first.version());
        assertEquals(2, first.added());
        assertEquals(365, first.applied().size());
        assertEquals(List.of("PERIOD|2027-01-01|2028-01-01"), db.queryForList(
                "SELECT scope||'|'||valid_from||'|'||valid_to FROM tna.calendar_version WHERE tenant_id=?", String.class, tenant));
        assertEquals("HOLIDAY|창립기념일|휴일 관리 반영", db.queryForObject("""
                SELECT kind||'|'||name||'|'||basis FROM tna.calendar_effective_day WHERE tenant_id=? AND work_date=DATE '2027-03-02'
                """, String.class, tenant));
        assertNull(db.queryForObject("SELECT basis FROM tna.holiday WHERE tenant_id=? AND holiday_date=DATE '2027-03-02'", String.class, tenant));
        assertEquals(1, count("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='HOLIDAY_SAVE'"));
        var page = holidays.page(manager, 2027);
        assertEquals(1, page.version());
        assertEquals("합성 시트 사원", page.applied().by());
        assertEquals(List.of("1월 1일", "창립기념일"), page.rows().stream().map(HolidayService.Row::name).toList());
        assertTrue(page.builtIn());
        assertFalse(holidays.page(manager, 2030).builtIn());
    }

    @Test void onlyDatesWhoseKindOrNameChangedGetANewSourceFingerprint() {
        CalendarSeed.period(db, manager, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "합성 달력", "");
        var dates = List.of(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 6));
        var before = dates.stream().map(date -> clocks.day(manager, date).sourceFingerprint()).toList();

        var saved = holidays.save(manager, form(UUID.randomUUID(), 2026, 0L, input("a", "2026-05-05", "어린이날", "PUBLIC", null)));
        assertEquals(List.of(LocalDate.of(2026, 5, 5)), saved.applied());
        var after = dates.stream().map(date -> clocks.day(manager, date).sourceFingerprint()).toList();
        assertEquals(before.get(0), after.get(0));
        assertNotEquals(before.get(1), after.get(1));
        assertEquals(before.get(2), after.get(2));
        assertEquals("HOLIDAY", clocks.day(manager, dates.get(1)).dayKind());

        var removed = holidays.save(manager, form(UUID.randomUUID(), 2026, 1L));
        assertEquals(1, removed.deleted());
        assertEquals(List.of(LocalDate.of(2026, 5, 5)), removed.applied());
        assertEquals("WORKDAY", clocks.day(manager, dates.get(1)).dayKind());
        assertEquals(1, count("SELECT count(*) FROM tna.calendar_version WHERE tenant_id=? AND scope='PERIOD'"));
        assertEquals(2, count("SELECT count(*) FROM tna.calendar_version WHERE tenant_id=? AND scope='DAY'"));
    }

    @Test void rejectsAStaleVersionAndReportsEveryRowErrorWithoutWriting() {
        var stale = assertThrows(OrgException.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2027, 3L)));
        assertEquals(409, stale.status());
        assertEquals("최신 내용을 확인한 뒤 다시 저장하세요.", stale.getMessage());
        var problem = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2027, 0L,
                input("a", "2027-05-05", "어린이날", "PUBLIC", null),
                input("b", "2027-05-05", "중복", "COMPANY", null),
                input("c", "2026-12-31", "다른 해", "COMPANY", null),
                input("d", "2027-06-01", " ", "COMPANY", null),
                input("e", "2027-06-02", "구분 오류", "HOLIDAY", null),
                input("f", "2027-06-03", "근거 초과", "COMPANY", "x".repeat(501)),
                input("g", "2027-13-01", "형식", "COMPANY", null))));
        assertEquals(422, problem.status());
        assertEquals(List.of("a:date", "b:date", "c:date", "d:name", "e:category", "f:basis", "g:date"),
                problem.errors().stream().map(error -> error.clientRowId() + ":" + error.field()).sorted().toList());
        assertEquals("2027년 날짜만 입력할 수 있습니다.", problem.errors().stream().filter(e -> "c".equals(e.clientRowId())).findFirst().orElseThrow().message());
        for (String table : List.of("holiday", "holiday_year", "calendar_version")) {
            assertEquals(0, count("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?"), table);
        }
    }

    @Test void closedMonthsKeepTheirHolidaysAndCalendar() {
        holidays.save(manager, form(UUID.randomUUID(), 2026, 0L, input("a", "2026-01-02", "시무식 휴무", "COMPANY", null)));
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,DATE '2026-01-01',1,'CLOSED','합성 월마감',?,'합성 담당자')
                """, tenant, UUID.randomUUID(), manager.accountId());

        var deleted = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2026, 1L)));
        assertNull(deleted.errors().getFirst().clientRowId());
        assertEquals("2026-01-02: 2026-01 근태가 마감돼 바꿀 수 없습니다.", deleted.errors().getFirst().message());
        var added = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2026, 1L,
                input("a", "2026-01-02", "시무식 휴무", "COMPANY", null), input("b", "2026-01-05", "추가 휴무", "COMPANY", null))));
        assertEquals(List.of("b:date"), added.errors().stream().map(e -> e.clientRowId() + ":" + e.field()).toList());

        var kept = holidays.save(manager, form(UUID.randomUUID(), 2026, 1L,
                input("a", "2026-01-02", "시무식 휴무", "COMPANY", null), input("c", "2026-03-03", "창립기념일", "COMPANY", null)));
        assertEquals(List.of(LocalDate.of(2026, 3, 3)), kept.applied());
        assertEquals(List.of("2026-01"), holidays.page(manager, 2026).closedMonths());
    }
}
