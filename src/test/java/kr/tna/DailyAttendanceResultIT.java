package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.worktime.MonthEndReviewQueries;
import kr.tna.worktime.WorktimeCalculator;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 재집계가 지각·조퇴·결근 판정을 CALC-2 결과에 저장하고 조회 경로가 그대로 돌려주는지 확인한다. */
@SpringBootTest
class DailyAttendanceResultIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 고정 날짜는 실행일(2026-11-25 이후) 기준 항상 과거라 실제 시계로 '날이 지났다'. 월말 점검은 2026-11을 조회한다.
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23), TUESDAY = MONDAY.plusDays(1);

    @Autowired WorktimeService worktime;
    @Autowired ClockService clocks;
    @Autowired ApproverService approvers;
    @Autowired MonthEndReviewQueries monthEnd;
    private JdbcTemplate db;
    private UUID tenant, schedule, calendar, network;
    private TnaPrincipal manager, employee, approver;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void fixedScheduleAndWorkdayCalendar() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 근태판정 고객사')", tenant, "ATTEND-" + tenant);
        manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("합성 직원", Set.of("EMPLOYEE"));
        approver = member("합성 승인자", Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(manager, new ApproverForm(employee.employeeId(), approver.employeeId(), LocalDate.now(SEOUL), 0L, "합성 승인 관계", UUID.randomUUID()));
        UUID template = UUID.randomUUID();
        schedule = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 고정 일정',?,?)",
                tenant, template, template.toString(), manager.accountId(), manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 버전',?)",
                tenant, schedule, template, MONDAY.minusDays(7), manager.accountId());
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '08:00',TIME '17:00')", tenant, schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')", tenant, schedule, day);
        }
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 배정',?,?)",
                tenant, UUID.randomUUID(), employee.employeeId(), schedule, MONDAY.minusDays(7), manager.accountId(), manager.accountId());
        calendar = CalendarSeed.period(db, manager, MONDAY.minusDays(7), MONDAY.plusDays(14), "합성 근로일 검토", "");
        network = UUID.randomUUID();
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 근거',?)",
                tenant, network, java.sql.Timestamp.from(MONDAY.minusDays(7).atStartOfDay(SEOUL).toInstant()), manager.accountId());
    }

    @Test void recalculationStoresLatenessAndAbsence() {
        record(MONDAY, 8, 7, 17, 0);
        var before = clocks.day(employee, MONDAY);
        clocks.confirm(employee, new ConfirmationForm(MONDAY, 2L, 0L, true, true, before.sourceFingerprint(), UUID.randomUUID()));

        worktime.recalculate(manager, employee.employeeId(), MONDAY, TUESDAY, UUID.randomUUID(), "합성 재집계");

        var rows = db.queryForList("""
                SELECT DISTINCT ON(work_date) work_date, late_millis, early_leave_millis, absence, rule_version, actual_work_millis
                FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date IN (?, ?)
                ORDER BY work_date, result_revision DESC""", tenant, employee.employeeId(), MONDAY, TUESDAY);
        assertEquals(420_000L, rows.get(0).get("late_millis"));
        assertEquals(0L, rows.get(0).get("early_leave_millis"));
        assertNull(rows.get(0).get("absence"));
        assertEquals("CALC-2", rows.get(0).get("rule_version"));
        assertEquals("FULL", rows.get(1).get("absence"));
        assertNull(rows.get(1).get("late_millis"));
        assertEquals(0L, rows.get(1).get("actual_work_millis"));
        assertEquals("CALC-2", db.queryForObject(
                "SELECT engine_version FROM tna.calculation_run WHERE tenant_id=? AND employee_id=? ORDER BY started_at DESC LIMIT 1",
                String.class, tenant, employee.employeeId()));

        var days = worktime.days(manager, employee.employeeId(), MONDAY, TUESDAY);
        assertEquals(420_000L, days.get(0).result().attendance().lateMillis());
        assertEquals(0L, days.get(0).result().attendance().earlyLeaveMillis());
        assertEquals("FULL", days.get(1).result().attendance().absence());
        assertEquals("CALC-2", days.get(0).ruleVersion());
    }

    @Test void reviewWorkbookAppendsLatenessEarlyLeaveAndAbsenceColumns() throws Exception {
        record(MONDAY, 8, 7, 17, 0);
        var before = clocks.day(employee, MONDAY);
        clocks.confirm(employee, new ConfirmationForm(MONDAY, 2L, 0L, true, true, before.sourceFingerprint(), UUID.randomUUID()));
        worktime.recalculate(manager, employee.employeeId(), MONDAY, TUESDAY, UUID.randomUUID(), "합성 재집계");

        UUID snapshot = worktime.prepareExport(manager, employee.employeeId(), MONDAY, TUESDAY.plusDays(1), UUID.randomUUID(), "합성 판정 열");
        String sheet = null;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(worktime.download(manager, snapshot)), java.nio.charset.StandardCharsets.UTF_8)) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertNotNull(sheet);
        var cell = java.util.regex.Pattern.compile("<c r=\"[A-Z]+\\d+\" t=\"(?:n|inlineStr)\"><(?:v|is><t[^>]*)>(.*?)</(?:v|t></is)></c>");
        var rows = new java.util.ArrayList<List<String>>();
        for (String row : sheet.split("<row ")) {
            var cells = new java.util.ArrayList<String>();
            var m = cell.matcher(row);
            while (m.find()) cells.add(m.group(1));
            rows.add(cells);
        }
        var header = rows.stream().filter(r -> r.contains("유급휴가 인정(ms)")).findFirst().orElseThrow();
        assertEquals(List.of("지각(분)", "조퇴(분)", "결근"), header.subList(header.size() - 3, header.size()));
        assertEquals(List.of("7", "0", "미확정"), lastThree(rows, MONDAY));
        assertEquals("결근", lastThree(rows, TUESDAY).get(2));
        assertEquals(List.of("미확정", "미확정", "미확정"), lastThree(rows, TUESDAY.plusDays(1)));
    }

    private static List<String> lastThree(List<List<String>> rows, LocalDate date) {
        var row = rows.stream().filter(r -> r.size() > 40 && r.get(2).equals(date.toString())).findFirst().orElseThrow();
        return row.subList(row.size() - 3, row.size());
    }

    @Test void halfDayLeavePartReachesTheCalculator() {
        approvedHalfDayLeave(MONDAY, "AM");
        record(MONDAY, 13, 5, 17, 0);

        worktime.recalculate(manager, employee.employeeId(), MONDAY, MONDAY, UUID.randomUUID(), "합성 반차 재집계");

        var row = db.queryForMap("""
                SELECT late_millis, early_leave_millis, absence FROM tna.day_result
                WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1""",
                tenant, employee.employeeId(), MONDAY);
        assertEquals(300_000L, row.get("late_millis"), "오전 반차의 출근 기준은 휴게 종료 13:00입니다.");
        assertEquals(0L, row.get("early_leave_millis"));
        assertNull(row.get("absence"));
    }

    @Test void halfDayLeaveWithoutRecordsIsAHalfAbsence() {
        approvedHalfDayLeave(TUESDAY, "AM");

        worktime.recalculate(manager, employee.employeeId(), TUESDAY, TUESDAY, UUID.randomUUID(), "합성 반차 결근");

        var row = latestRow(TUESDAY);
        assertEquals("HALF", row.get("absence"));
        assertEquals(0L, row.get("actual_work_millis"));
        assertNull(row.get("late_millis"));
    }

    @Test void afternoonHalfDayEndsAtTheBreakStart() {
        approvedHalfDayLeave(MONDAY, "PM");
        record(MONDAY, 8, 0, 11, 50);

        worktime.recalculate(manager, employee.employeeId(), MONDAY, MONDAY, UUID.randomUUID(), "합성 오후 반차");

        var row = latestRow(MONDAY);
        assertEquals(0L, row.get("late_millis"));
        assertEquals(600_000L, row.get("early_leave_millis"), "오후 반차의 퇴근 기준은 휴게 시작 12:00입니다.");
        assertNull(row.get("absence"));
    }

    @Test void monthEndReviewListsOnlyAbsencesThatStillNeedCalculation() {
        LocalDate uncalculated = MONDAY.minusWeeks(1);
        // 11-23이 속한 주만 계산한다. 앞 주 월요일(11-16)은 저장 결과가 없다.
        worktime.recalculate(manager, employee.employeeId(), MONDAY, MONDAY, UUID.randomUUID(), "합성 결근 계산");
        assertEquals("FULL", latestRow(MONDAY).get("absence"));

        var rows = reviewRows();
        assertFalse(rows.containsKey(MONDAY), "저장된 현재 결근 결과는 월말 점검 대상이 아닙니다.");
        assertEquals(List.of("UNCALCULATED"), rows.get(uncalculated), "저장 결과 없는 결근일은 계산 대상으로 나와야 합니다.");

        // 결근 판정 전에 저장된 CALC-1 무기록 결과: 입력은 같아도 다시 계산해야 한다.
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,policy_version_id,assessment_id,rule_version,status,scheduled_millis,leave_days,reason_codes,input_refs)
                SELECT tenant_id,gen_random_uuid(),run_id,employee_id,work_date,result_revision+1,input_revision,review_revision,
                  source_fingerprint,policy_version_id,assessment_id,'CALC-1','REVIEW_REQUIRED',scheduled_millis,leave_days,
                  '["MISSING_CLOCK_IN","MISSING_CLOCK_OUT"]',input_refs
                FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1
                """, tenant, employee.employeeId(), MONDAY);
        assertEquals(List.of("STALE"), reviewRows().get(MONDAY));
        var old = worktime.days(manager, employee.employeeId(), MONDAY, MONDAY).getFirst();
        assertEquals("CALC-1", old.ruleVersion());
        assertEquals(WorktimeCalculator.Attendance.NONE, old.result().attendance(), "판정 열이 NULL인 옛 결과는 판정 없음입니다.");
    }

    private java.util.Map<LocalDate, List<String>> reviewRows() {
        String employeeNo = db.queryForObject("SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id=?", String.class, tenant, employee.employeeId());
        var page = monthEnd.read(manager, "2026-11", null, employeeNo, "ALL", 0);
        assertNull(page.error());
        var rows = new java.util.HashMap<LocalDate, List<String>>();
        for (var row : page.rows()) if (row.employeeId().equals(employee.employeeId())) rows.put(row.workDate(), row.reasons());
        return rows;
    }

    private java.util.Map<String, Object> latestRow(LocalDate date) {
        return db.queryForMap("""
                SELECT late_millis, early_leave_millis, absence, actual_work_millis FROM tna.day_result
                WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1""",
                tenant, employee.employeeId(), date);
    }

    private void record(LocalDate date, int inHour, int inMinute, int outHour, int outMinute) {
        for (String kind : List.of("IN", "OUT")) {
            Instant at = (kind.equals("IN") ? date.atTime(inHour, inMinute) : date.atTime(outHour, outMinute)).atZone(SEOUL).toInstant();
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                        source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                    """, tenant, UUID.randomUUID(), employee.employeeId(), kind, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
                    UUID.randomUUID(), network, date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",
                tenant, employee.employeeId(), date);
    }

    private void approvedHalfDayLeave(LocalDate date, String part) {
        UUID bucket = UUID.randomUUID(), request = UUID.randomUUID();
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,?,?,'합성',?)",
                tenant, bucket, employee.employeeId(), date, date, manager.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                  approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                SELECT ?,?,?,?,'LEAVE_DAY','APPROVED',?,a.id,0,'합성 반차',?,?,?,?,v.id FROM tna.approver_assignment a
                  JOIN tna.attendance_type t ON t.tenant_id=a.tenant_id AND t.system_kind='ANNUAL_LEAVE'
                  JOIN tna.attendance_type_version v ON v.tenant_id=t.tenant_id AND v.type_id=t.id AND v.revision=1
                  WHERE a.tenant_id=? AND a.employee_id=?
                """, tenant, request, employee.employeeId(), employee.accountId(), approver.employeeId(), bucket, date, date,
                UUID.randomUUID(), tenant, employee.employeeId());
        db.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,leave_part,schedule_version_id,calendar_version_id) VALUES (?,?,?,?,0.5,?,?,?)",
                tenant, UUID.randomUUID(), request, date, part, schedule, calendar);
        db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by) VALUES (?,?,?,?,'LEAVE',?)",
                tenant, UUID.randomUUID(), request, employee.employeeId(), manager.accountId());
    }

    private TnaPrincipal member(String name, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, "E-" + employeeId, name, MONDAY.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",
                tenant, account, employeeId, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }
}
