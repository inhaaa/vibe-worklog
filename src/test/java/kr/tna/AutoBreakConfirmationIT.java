package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import kr.tna.common.web.ReactPageViewResolver;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.WorktimeService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
    @Autowired kr.tna.common.tenant.TenantTransactions transactions;
    // 스텁하지 않은 테스트에서는 실제 동작 그대로다. 스텁은 테스트마다 초기화된다.
    @MockitoSpyBean WorktimeService worktime;
    @MockitoSpyBean OrgCommands orgCommands;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal manager, employee;

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
        manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
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
        assertEquals(1, outEvents());
    }

    @Test void replayingTheSameClockOutDoesNotDuplicate() throws Exception {
        clockIn(8, 0);
        UUID command = UUID.randomUUID();
        clockOut(17, 0, command);
        clockOut(17, 1, command);
        assertEquals(1, confirmations());
        assertEquals(1, outEvents());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='WORKTIME_AUTO_CALCULATE'",
                Integer.class, tenant));
    }

    @Test void failedCalculationKeepsTheClockOutAndItsAutoConfirmation() throws Exception {
        doThrow(new OrgException(503, "합성 계산 실패")).when(worktime).calculateConfirmedDay(any(), any(), any());
        clockIn(8, 0);
        clockOut(17, 0, UUID.randomUUID());
        assertEquals(1, outEvents());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=? AND method='AUTO_SCHEDULE'",
                Integer.class, tenant, employee.employeeId()));
    }

    @Test void clockOutJustBeforeMidnightIsCalculatedForTheRecordedWorkDate() throws Exception {
        clockIn(8, 0);
        // 기록 직후 계산 시점에는 이미 다음 날이 된 경우.
        doReturn(DAY.plusDays(1)).when(orgCommands).today();
        UUID command = UUID.randomUUID();
        clock.now.set(DAY.atTime(23, 59, 59, 900_000_000).atZone(SEOUL).toInstant());
        send("OUT", command);
        verify(worktime).calculateConfirmedDay(any(), eq(DAY), eq(command));
    }

    @Test void closedMonthIsNotAutoConfirmed() throws Exception {
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,?,1,'CLOSED','합성 마감',?,'합성 담당자')""", tenant, UUID.randomUUID(), DAY.withDayOfMonth(1), manager.accountId());
        clockIn(8, 0);
        clockOut(17, 0, UUID.randomUUID());
        assertEquals(0, confirmations());
        assertEquals(1, outEvents());
    }

    @Test void employeeScreensShowMissingClockInAndThenLateness() throws Exception {
        String period = "/me/attendance?view=period&from=" + DAY + "&to=" + DAY;
        clock.now.set(DAY.atTime(9, 0).atZone(SEOUL).toInstant());
        page("/me/home").andExpect(jsonPath("$.data.workspaceHome.week[0].attendanceLabel").value("미출근"));
        page(period).andExpect(jsonPath("$.data.attendancePeriod.rows[0].attendanceLabel").value("미출근"));
        clockIn(8, 7);
        clockOut(17, 0, UUID.randomUUID());
        page("/me/home").andExpect(jsonPath("$.data.workspaceHome.week[0].attendanceLabel").value("지각 7분"));
        page(period).andExpect(jsonPath("$.data.attendancePeriod.rows[0].attendanceLabel").value("지각 7분"));
        page("/me/schedule?month=2026-11").andExpect(jsonPath("$.data.workspaceCalendar.days[?(@.snapshot.workDate == '" + DAY + "')].attendanceLabel")
                .value(org.hamcrest.Matchers.contains("지각 7분")));
        page(manager, "/company/attendance?employeeId=" + employee.employeeId() + "&month=2026-11")
                .andExpect(jsonPath("$.data.companyAttendance.days[?(@.workDate == '" + DAY + "')].attendanceLabel")
                        .value(org.hamcrest.Matchers.contains("지각 7분")));
    }

    @Test void homeAndCompanyDrillDownDropTheStoredLatenessOnceTheResultIsStale() throws Exception {
        clockIn(8, 7);
        clockOut(17, 0, UUID.randomUUID());
        page("/me/home").andExpect(jsonPath("$.data.workspaceHome.week[0].attendanceLabel").value("지각 7분"));
        db.update("UPDATE tna.day_input_revision SET input_revision=input_revision+1 WHERE tenant_id=? AND employee_id=? AND work_date=?",
                tenant, employee.employeeId(), DAY);
        page("/me/home").andExpect(jsonPath("$.data.workspaceHome.week[0].attendanceLabel").value(org.hamcrest.Matchers.nullValue()));
        page(manager, "/company/attendance?employeeId=" + employee.employeeId() + "&month=2026-11")
                .andExpect(jsonPath("$.data.companyAttendance.days[?(@.workDate == '" + DAY + "')].attendanceLabel")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test void homeShowsMissingClockInEvenWhenTodayHasAStoredResultWithoutJudgement() throws Exception {
        clock.now.set(DAY.atTime(9, 0).atZone(SEOUL).toInstant());
        assertEquals(1, transactions.execute(tenant, () -> worktime.calculateDatesAsSystem(tenant, null, employee.employeeId(), List.of(DAY))));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=? AND late_millis IS NULL AND early_leave_millis IS NULL AND absence IS NULL",
                Integer.class, tenant, employee.employeeId(), DAY));
        page("/me/home").andExpect(jsonPath("$.data.workspaceHome.week[0].attendanceLabel").value("미출근"));
    }

    private ResultActions page(String url) throws Exception { return page(employee, url); }
    private ResultActions page(TnaPrincipal who, String url) throws Exception {
        var actor = new TnaPrincipal(tenant, who.accountId(), who.employeeId(), 1, who.roles(), clock.instant());
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return mvc.perform(get(url).with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities)))
                .accept(ReactPageViewResolver.MEDIA_TYPE)).andExpect(status().isOk());
    }

    private void clockIn(int hour, int minute) throws Exception { event("IN", hour, minute, UUID.randomUUID()); }
    private void clockOut(int hour, int minute, UUID command) throws Exception { event("OUT", hour, minute, command); }
    private void event(String kind, int hour, int minute, UUID command) throws Exception {
        clock.now.set(DAY.atTime(hour, minute).atZone(SEOUL).toInstant());
        send(kind, command);
    }
    private void send(String kind, UUID command) throws Exception {
        // 세션 검증은 로그인 시각이 (시험) 시계보다 앞서고 12시간 안이어야 한다.
        var actor = new TnaPrincipal(tenant, employee.accountId(), employee.employeeId(), 1, employee.roles(), clock.instant());
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        mvc.perform(post("/me/attendance/events").with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities)))
                        .param("eventKind", kind).param("commandId", command.toString()))
                .andExpect(status().isSeeOther());
    }
    private List<Interval> breaks() { return clocks.day(employee, DAY).confirmation().breaks(); }
    private int outEvents() {
        return db.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=? AND event_kind='OUT'",
                Integer.class, tenant, employee.employeeId());
    }
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
