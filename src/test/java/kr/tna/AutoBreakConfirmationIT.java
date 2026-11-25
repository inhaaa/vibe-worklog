package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.tna.clock.ClockData.Interval;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 퇴근 기록이 예정 휴게를 자동 확인하고 같은 요청에서 근무시간을 계산하는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AutoBreakConfirmationIT.Time.class)
class AutoBreakConfirmationIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);

    @Autowired MockMvc mvc;
    @Autowired ClockService clocks;
    @Autowired TestClock clock;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal employee;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void fixedScheduleCalendarAndNetwork() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 자동확인 고객사')", tenant, "AUTO-" + tenant);
        var manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("합성 직원", Set.of("EMPLOYEE"));
        UUID template = UUID.randomUUID(), schedule = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 고정 일정',?,?)",
                tenant, template, template.toString(), manager.accountId(), manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 버전',?)",
                tenant, schedule, template, DAY.minusDays(7), manager.accountId());
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '08:00',TIME '17:00')", tenant, schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')", tenant, schedule, day);
        }
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 배정',?,?)",
                tenant, UUID.randomUUID(), employee.employeeId(), schedule, DAY.minusDays(7), manager.accountId(), manager.accountId());
        CalendarSeed.period(db, manager, DAY.minusDays(7), DAY.plusDays(7), "합성 근로일 검토", "");
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 근거',?)",
                tenant, UUID.randomUUID(), java.sql.Timestamp.from(DAY.minusDays(7).atStartOfDay(SEOUL).toInstant()), manager.accountId());
    }

    @Test void clockOutConfirmsScheduledBreaksAndCalculatesImmediately() throws Exception {
        clockIn(8, 0);
        clockOut(17, 0, UUID.randomUUID());

        var c = db.queryForMap("SELECT method, confirmed_by FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=?",
                tenant, employee.employeeId());
        assertEquals("AUTO_SCHEDULE", c.get("method"));
        assertEquals(employee.accountId(), c.get("confirmed_by"));
        assertEquals(List.of(interval(12, 0, 13, 0)), breaks());
        assertEquals(28_800_000L, db.queryForObject("""
                SELECT actual_work_millis FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?
                ORDER BY result_revision DESC LIMIT 1""", Long.class, tenant, employee.employeeId(), DAY));
    }

    @Test void breaksOutsidePresenceAreClipped() throws Exception {
        clockIn(12, 30);
        clockOut(17, 0, UUID.randomUUID());
        assertEquals(List.of(interval(12, 30, 13, 0)), breaks());
    }

    @Test void morningHalfDayArrivalAppliesNoBreak() throws Exception {
        clockIn(13, 0);
        clockOut(17, 0, UUID.randomUUID());
        assertEquals("AUTO_SCHEDULE", db.queryForObject("SELECT method FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=?",
                String.class, tenant, employee.employeeId()));
        assertEquals(List.of(), breaks());
    }

    @Test void autoConfirmationIsSkippedWhenRecordsAreIncomplete() throws Exception {
        clockOut(17, 0, UUID.randomUUID());
        assertEquals(0, confirmations());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=? AND event_kind='OUT'",
                Integer.class, tenant, employee.employeeId()));
    }

    @Test void replayingTheSameClockOutDoesNotDuplicate() throws Exception {
        clockIn(8, 0);
        UUID command = UUID.randomUUID();
        clockOut(17, 0, command);
        clockOut(17, 1, command);
        assertEquals(1, confirmations());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='WORKTIME_AUTO_CALCULATE'",
                Integer.class, tenant));
    }

    private void clockIn(int hour, int minute) throws Exception { event("IN", hour, minute, UUID.randomUUID()); }
    private void clockOut(int hour, int minute, UUID command) throws Exception { event("OUT", hour, minute, command); }
    private void event(String kind, int hour, int minute, UUID command) throws Exception {
        clock.now.set(DAY.atTime(hour, minute).atZone(SEOUL).toInstant());
        // 세션 검증은 로그인 시각이 (시험) 시계보다 앞서고 12시간 안이어야 한다.
        var actor = new TnaPrincipal(tenant, employee.accountId(), employee.employeeId(), 1, employee.roles(), clock.instant());
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        mvc.perform(post("/me/attendance/events").with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities)))
                        .param("eventKind", kind).param("commandId", command.toString()))
                .andExpect(status().isSeeOther());
    }
    private List<Interval> breaks() { return clocks.day(employee, DAY).confirmation().breaks(); }
    private int confirmations() {
        return db.queryForObject("SELECT count(*) FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=?",
                Integer.class, tenant, employee.employeeId());
    }
    private static Interval interval(int fromHour, int fromMinute, int toHour, int toMinute) {
        return new Interval(DAY.atTime(fromHour, fromMinute).atZone(SEOUL).toInstant(), DAY.atTime(toHour, toMinute).atZone(SEOUL).toInstant());
    }

    private TnaPrincipal member(String name, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, "E-" + employeeId, name, DAY.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",
                tenant, account, employeeId, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }

    @TestConfiguration
    static class Time {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(DAY.atTime(8, 0).atZone(SEOUL).toInstant());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
