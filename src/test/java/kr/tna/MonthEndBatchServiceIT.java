package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsData.NetworkForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.worktime.AttendanceMonthCloseService;
import kr.tna.worktime.CheckoutFollowUpService;
import kr.tna.worktime.CheckoutFollowUpService.DayKey;
import kr.tna.worktime.MonthEndBatchService;
import kr.tna.worktime.MonthEndBatchService.Command;
import kr.tna.worktime.MonthEndBatchService.Selection;
import kr.tna.worktime.WorktimeMapper;
import kr.tna.worktime.WorktimeData.AssessmentForm;
import kr.tna.worktime.WorktimeData.PolicyForm;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MonthEndBatchServiceIT {
    private static final LocalDate DATE = LocalDate.of(2024, 3, 1);
    private static final String PATH = "/admin/calculations/month-end";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired MonthEndBatchService batches;
    @Autowired WorktimeService worktime;
    @Autowired ClockService clocks;
    @Autowired AttendanceSettingsService settings;
    @Autowired CheckoutFollowUpService followUps;
    @Autowired LeaveWorkflowService requests;
    @Autowired ApproverService approvers;
    @Autowired EmployeeService employees;
    @Autowired AttendanceMonthCloseService months;
    @MockitoSpyBean WorktimeMapper mapper;
    private JdbcTemplate db;
    private TnaPrincipal manager;
    private UUID tenant, first, second;
    private Selection selection;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void syntheticHistoricalEmployeeDays() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 월말 회사')",
                tenant, "MONTH-" + tenant);
        manager = account("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        first = employee("BATCH-A", DATE.plusDays(1));
        second = employee("BATCH-B", DATE.plusDays(1));
        selection = new Selection("2024-03", null, "BATCH", "ALL", 0, List.of(
                new DayKey(second, DATE), new DayKey(first, DATE), new DayKey(first, DATE.plusDays(1))));
    }

    @Test
    void concurrentRetriesShareTheOriginalImmutableReceiptAcrossLaterRunsButStillRequireCurrentAuthority() throws Exception {
        var preview = batches.preview(manager, selection);
        assertEquals(2, preview.weekCount());
        assertEquals(0, count("calculation_run"));
        assertEquals(0, count("org_audit_event"));
        var command = command(preview.previewToken(), UUID.randomUUID(), "합성 월말 확인");
        var pair = together(() -> batches.execute(manager, command), () -> batches.execute(manager, command));
        var firstReceipt = pair.getFirst();
        assertEquals(firstReceipt, pair.getLast());
        assertEquals(3, firstReceipt.rows().size());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(3, count("org_audit_event"));
        for (var row : firstReceipt.rows()) {
            assertEquals(List.of("UNCONFIRMED"), row.reasons());
            assertNull(row.actualWorkMillis());
            assertEquals(row.resultId(), db.queryForObject("""
                    SELECT id FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?
                    ORDER BY result_revision DESC LIMIT 1
                    """, UUID.class, tenant, row.employeeId(), row.workDate()));
        }
        worktime.recalculate(manager, first, DATE, DATE, UUID.randomUUID(), "후속 재집계");
        assertEquals(firstReceipt, batches.execute(manager, command));
        assertEquals(3, count("calculation_run"));
        var changed = command(preview.previewToken(), command.commandId(), "다른 사유");
        assertEquals(409, assertThrows(OrgException.class, () -> batches.execute(manager, changed)).status());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, manager.accountId());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.preview(manager, selection)).status());
    }

    @Test
    void concurrentDistinctCommandsCannotReuseTheSamePreviewAfterItsResultChanges() throws Exception {
        String token = batches.preview(manager, selection).previewToken();
        var firstCommand = command(token, UUID.randomUUID(), "동시 요청");
        var secondCommand = command(token, UUID.randomUUID(), "동시 요청");
        var statuses = together(() -> executeStatus(firstCommand), () -> executeStatus(secondCommand));
        assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(3, count("org_audit_event"));
    }

    @Test
    void replayRequiresTheEmployeeGrantEvenWhileTheAttendanceAdministratorGrantIsStillActive() {
        var preview = batches.preview(manager, selection);
        var command = command(preview.previewToken(), UUID.randomUUID(), "기본 권한 확인");
        assertEquals(3, batches.execute(manager, command).rows().size());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",
                tenant, manager.accountId());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.preview(manager, selection)).status());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        assertEquals(2, count("calculation_run"));
    }

    @Test
    void failureOnTheSecondEmployeeRollsBackTheFirstEmployeesRunResultsAndAudits() {
        String token = batches.preview(manager, selection).previewToken();
        doReturn(0).when(mapper).insertRun(eq(tenant), any(), eq(second), any(), any(), any(), any(), any());
        var command = command(token, UUID.randomUUID(), "실패 시 전부 취소");
        assertEquals(409, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        verify(mapper, times(7)).nextResult(eq(tenant), eq(first), any());
        assertEquals(0, count("calculation_run"));
        assertEquals(0, count("day_result"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test
    void acceptsAllFiftyRowsOnOnePageAndRecalculatesEightEmployeeWeeks() {
        extendEmploymentThroughMarch();
        var keys = new ArrayList<DayKey>();
        for (int day = 7; day <= 31; day++) {
            keys.add(new DayKey(first, DATE.withDayOfMonth(day)));
            keys.add(new DayKey(second, DATE.withDayOfMonth(day)));
        }
        selection = new Selection("2024-03", null, "BATCH", "ALL", 0, keys);
        var preview = batches.preview(manager, selection);
        assertEquals(50, preview.rows().size());
        assertEquals(8, preview.weekCount());
        var result = batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "현재 페이지 전체"));
        assertEquals(50, result.rows().size());
        assertEquals(8, count("calculation_run"));
        assertEquals(56, count("day_result"));
    }

    @Test
    void skipsIntermediateWeeksForTwoNonconsecutiveSelectionsOfOneEmployee() {
        extendEmploymentThroughMarch();
        selection = new Selection("2024-03", null, "BATCH-A", "ALL", 0,
                List.of(new DayKey(first, DATE), new DayKey(first, DATE.plusWeeks(3))));
        var preview = batches.preview(manager, selection);
        assertEquals(2, preview.weekCount());
        var result = batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "두 주만 재집계"));
        assertEquals(2, result.rows().size());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND work_date BETWEEN DATE '2024-03-04' AND DATE '2024-03-17'
                """, Integer.class, tenant));
    }

    @Test
    void requestedRangeContainsOnlySelectedMarchDaysWhenTheWeekStartsInFebruary() {
        var preview = batches.preview(manager, selection);
        batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "월 경계 선택일 확인"));
        assertEquals(List.of(DATE, DATE), db.queryForList("""
                SELECT from_date FROM tna.calculation_run WHERE tenant_id=? ORDER BY employee_id
                """, LocalDate.class, tenant));
        assertEquals(List.of(DATE, DATE.plusDays(1)), db.queryForList("""
                SELECT to_date FROM tna.calculation_run WHERE tenant_id=? ORDER BY to_date
                """, LocalDate.class, tenant));
        assertEquals(14, count("day_result"));
    }

    @Test
    void adjacentClosedMonthKeepsItsLastResultWhileMarchBatchAndEmployeeCalculationsContinue() throws Exception {
        LocalDate leapDay = DATE.minusDays(1);
        var worker = employeeWithOneFebruaryRestDay(leapDay);
        UUID originalCommand = UUID.randomUUID();
        UUID originalRun = worktime.recalculate(manager, worker.employeeId(), leapDay, leapDay, originalCommand, "2월 휴무 확인");
        var frozen = worktime.days(manager, worker.employeeId(), leapDay, leapDay).getFirst();
        var before = batches.preview(manager, selection);
        changeFebruary("CLOSE", 0);

        assertEquals(originalRun, worktime.recalculate(manager, worker.employeeId(), leapDay, leapDay, originalCommand, "2월 휴무 확인"));
        assertClosed(() -> worktime.recalculate(manager, worker.employeeId(), leapDay, DATE, UUID.randomUUID(), "두 달 재집계"));
        assertClosed(() -> worktime.calculateConfirmedDay(worker, leapDay, UUID.randomUUID()));
        var stale = command(before.previewToken(), UUID.randomUUID(), "마감 전 미리보기");
        assertEquals(409, assertThrows(OrgException.class, () -> batches.execute(manager, stale)).status());
        var preview = batches.preview(manager, selection);
        assertTrue(preview.rows().stream().allMatch(row -> row.weekStart().equals(DATE)
                && row.weekEnd().equals(DATE.plusDays(2))));
        assertEquals(3, batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "3월 첫 주")).rows().size());
        worktime.calculateConfirmedDay(worker, DATE, UUID.randomUUID());
        assertEquals(frozen.id(), worktime.days(manager, worker.employeeId(), leapDay, leapDay).getFirst().id());
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND work_date<? AND run_id<>?
                """, Integer.class, tenant, DATE, originalRun));

        assertClosed(() -> batches.preview(manager, new Selection("2024-02", null, "", "ALL", 0,
                List.of(new DayKey(worker.employeeId(), leapDay)))));
        changeFebruary("REOPEN", 1);
        worktime.recalculate(manager, worker.employeeId(), leapDay, leapDay, UUID.randomUUID(), "재개 후 재집계");
        assertNotEquals(frozen.id(), worktime.days(manager, worker.employeeId(), leapDay, leapDay).getFirst().id());
        changeFebruary("CLOSE", 2);
        assertClosed(() -> worktime.recalculate(manager, worker.employeeId(), leapDay, leapDay, UUID.randomUUID(), "재마감 후 직접 요청"));
    }

    @Test
    void aClosedMonthBlocksSourceChangesAndNewRequestsButStillAllowsTodaysClockAndLeaveBalances() throws Exception {
        LocalDate leapDay = DATE.minusDays(1);
        var worker = employeeWithOneFebruaryRestDay(leapDay);
        changeFebruary("CLOSE", 0);
        var facts = clocks.day(worker, leapDay);
        assertClosed(() -> clocks.confirm(worker, new ConfirmationForm(leapDay, facts.inputRevision(), facts.reviewRevision(),
                true, true, facts.sourceFingerprint(), UUID.randomUUID())));
        assertClosed(() -> requests.submitCorrection(worker, new CorrectionForm(null, leapDay, facts.inputRevision(),
                facts.sourceFingerprint(), "SET", leapDay + "T09:00", "SET", leapDay + "T18:00", true, true, "",
                null, null, false, "휴무일 실제 근무", UUID.randomUUID())));
        var bucket = requests.openBucket(manager, new BucketForm(worker.employeeId(), leapDay, leapDay, DATE.plusDays(2),
                1, "기초 원장", "잔여 등록", UUID.randomUUID()));
        assertClosed(() -> requests.submitLeave(worker, new LeaveForm(bucket, leapDay, DATE, null, "두 달 사용 기간", UUID.randomUUID())));
        assertClosed(() -> requests.submitOvertime(worker, leapDay, LocalTime.of(18, 0), LocalTime.of(19, 0),
                "마감일 연장", "근무 기준", "결재선", UUID.randomUUID()));
        assertClosed(() -> followUps.request(manager, new CheckoutFollowUpService.Form(worker.employeeId(), leapDay, "누락 점검", UUID.randomUUID())));
        assertClosed(() -> worktime.reviewPolicy(manager, new PolicyForm(leapDay.minusMonths(1), null, true, "검토", "소급 기산일", UUID.randomUUID())));
        assertClosed(() -> worktime.assess(manager, new AssessmentForm(worker.employeeId(), leapDay, DATE, true, "검토", "소급 적용", UUID.randomUUID())));
        settings.addNetwork(manager, new NetworkForm("127.0.0.1/32", "로컬", false, "원천 출근", UUID.randomUUID()));
        assertNotNull(clocks.record(worker, "IN", UUID.randomUUID(), "127.0.0.1"));
        assertEquals(1, count("attendance_event"));
        assertEquals(0, count("approval_request"));
        assertEquals(1, count("leave_ledger"));
    }

    @Test
    void correctionPreviewRejectsTheClosedMonthAndBecomesEligibleAfterReopening() throws Exception {
        LocalDate date = DATE.minusDays(1);
        var worker = employeeWithOneFebruaryRestDay(date);
        assignApprover(worker);
        var facts = clocks.day(worker, date);
        String command = UUID.randomUUID().toString();
        var auth = UsernamePasswordAuthenticationToken.authenticated(worker, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        var preview = post("/me/requests/preview").with(authentication(auth)).with(csrf()).accept("application/vnd.worklog.page+json")
                .param("year", String.valueOf(date.getYear())).param("kind", "ATTENDANCE_CORRECTION")
                .param("workDate", date.toString()).param("baseInputRevision", String.valueOf(facts.inputRevision()))
                .param("sourceFingerprint", facts.sourceFingerprint()).param("inAction", "SET").param("requestedInAt", date + "T09:00")
                .param("outAction", "SET").param("requestedOutAt", date + "T18:00").param("presenceAsWork", "true")
                .param("breaksConfirmed", "true").param("reason", "휴무일 실제 근무").param("commandId", command);
        changeFebruary("CLOSE", 0);
        var blocked = mvc.perform(preview).andExpect(status().isConflict()).andReturn();
        assertTrue(ReactPageAssertions.envelope(blocked).at("/data/error").asString().contains("마감"));
        changeFebruary("REOPEN", 1);
        mvc.perform(preview).andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/compose/preview/eligible", true))
                .andExpect(ReactPageAssertions.data("/compose/correctionForm/commandId", command));
        assertEquals(0, count("approval_request"));
    }

    @Test
    void assignedApproverCanRejectARetiredEmployeesPendingCorrectionAndClearTheMonth() throws Exception {
        LocalDate date = DATE.minusDays(1);
        var worker = employeeWithOneFebruaryRestDay(date);
        var approver = assignApprover(worker);
        var facts = clocks.day(worker, date);
        var form = new CorrectionForm(null, date, facts.inputRevision(), facts.sourceFingerprint(), "SET", date + "T09:00",
                "SET", date + "T18:00", true, true, "", null, null, false, "휴무일 근무 정정", UUID.randomUUID());
        var preview = requests.previewCorrection(worker, form);
        UUID id = requests.submitCorrection(worker, form, preview.approval().routeToken());
        var before = employees.get(manager, worker.employeeId());
        employees.update(manager, before.id(), new EmployeeForm(before.employeeNo(), before.displayName(), before.joinedOn(),
                date, true, before.version(), "마지막 재직일 기록", UUID.randomUUID()));

        var check = new AttendanceMonthCloseService.CheckForm("2024-02");
        assertEquals(1, months.check(manager, check).pendingRequests());
        var auth = authentication(UsernamePasswordAuthenticationToken.authenticated(approver, null,
                approver.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
        String page = "application/vnd.worklog.page+json";
        mvc.perform(get("/approvals").with(auth).accept(page)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/requests/0/id", id.toString()));
        mvc.perform(get("/requests/{id}", id).with(auth).accept(page)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/canDecide", true));
        var denied = mvc.perform(post("/requests/{id}/approve", id).with(auth).with(csrf()).accept(page)
                .param("expectedVersion", "0").param("expectedStep", "1").param("reason", "일반 승인")
                .param("commandId", UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity()).andReturn();
        assertTrue(ReactPageAssertions.envelope(denied).at("/data/error").asString().contains("재직"));
        assertEquals(1, months.check(manager, check).pendingRequests());
        String reason = "재직 종료 후 정정 근거 미충족";
        mvc.perform(post("/requests/{id}/reject", id).with(auth).with(csrf()).accept(page)
                .param("expectedVersion", "0").param("expectedStep", "1").param("reason", reason)
                .param("commandId", UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        var rejected = requests.get(approver, id);
        assertEquals("REJECTED", rejected.request().status());
        assertTrue(rejected.history().stream().anyMatch(event -> "REJECTED".equals(event.eventKind())
                && reason.equals(event.reason()) && approver.accountId().equals(event.actorAccountId())));
        assertEquals(0, months.check(manager, check).pendingRequests());
        assertEquals(0, count("request_effect"));
        assertEquals(0, count("leave_ledger"));
        assertEquals(0, count("attendance_event"));
        changeFebruary("CLOSE", 0);
    }

    private TnaPrincipal assignApprover(TnaPrincipal worker) {
        var approver = account("APPROVER", Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(manager, new ApproverForm(worker.employeeId(), approver.employeeId(),
                LocalDate.now(ZoneId.of("Asia/Seoul")), 0L, "합성 결재자 지정", UUID.randomUUID()));
        return approver;
    }

    @Test
    void racingPolicyChangeAndCloseCannotCommitBothAStaleDayAndAClosedMonth() throws Exception {
        LocalDate leapDay = DATE.minusDays(1);
        var worker = employeeWithOneFebruaryRestDay(leapDay);
        worktime.recalculate(manager, worker.employeeId(), leapDay, leapDay, UUID.randomUUID(), "2월 휴무 확인");
        var statuses = together(() -> mvc.perform(februaryChange("CLOSE", 0)).andReturn().getResponse().getStatus(), () -> {
            try {
                worktime.reviewPolicy(manager, new PolicyForm(leapDay, leapDay, true, "검토", "2월 기산점", UUID.randomUUID()));
                return 200;
            } catch (OrgException error) {
                return error.status();
            }
        });
        assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
        assertEquals(1, count("work_policy_version") + count("attendance_month_closure"));
    }

    private TnaPrincipal employeeWithOneFebruaryRestDay(LocalDate date) {
        var worker = account("BOUNDARY", Set.of("EMPLOYEE"));
        UUID template = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, worker.employeeId());
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 휴무',?,?)",
                tenant, template, template.toString(), manager.accountId(), manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 휴무일',?)",
                tenant, version, template, date, manager.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday) VALUES (?,?,?,false)",
                tenant, version, date.getDayOfWeek().getValue());
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'입사일 배정',?,?)
                """, tenant, UUID.randomUUID(), worker.employeeId(), version, date, manager.accountId(), manager.accountId());
        settings.calendar(manager, new CalendarForm(date, date, true, "합성 휴무일", "", "2월 근무일 검토", UUID.randomUUID()));
        return worker;
    }

    private void changeFebruary(String action, long version) throws Exception {
        mvc.perform(februaryChange(action, version)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder februaryChange(String action, long version) {
        var body = java.util.Map.of("month", "2024-02", "action", action, "expectedVersion", version,
                "reason", "윤년 월경계 검증", "commandId", UUID.randomUUID());
        return request("/admin/attendance/month-close", manager, body).with(csrf());
    }

    private static void assertClosed(Runnable action) {
        var failure = assertThrows(OrgException.class, action::run);
        assertEquals(409, failure.status());
        assertTrue(failure.getMessage().contains("마감"), failure::getMessage);
    }

    @Test
    void bothPostsRequireCsrfAndCurrentAdministratorAndExcludeOtherTenantsAndPages() throws Exception {
        var employee = account("EMP", Set.of("EMPLOYEE"));
        var representative = account("REP", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"));
        var token = batches.preview(manager, selection).previewToken();
        for (String path : List.of(PATH, PATH + "/preview")) {
            Object body = path.equals(PATH) ? command(token, UUID.randomUUID(), "담당자 재집계") : selection;
            mvc.perform(request(path, manager, body)).andExpect(status().isForbidden());
            mvc.perform(request(path, employee, body).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(request(path, representative, body).with(csrf())).andExpect(status().isForbidden());
        }
        UUID foreignTenant = UUID.randomUUID(), foreignEmployee = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 월말 회사')",
                foreignTenant, "FOREIGN-" + foreignTenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'BATCH-X','다른 합성 사원',?)",
                foreignTenant, foreignEmployee, DATE);
        var invalid = List.of(new Selection("2024-03", null, "BATCH", "ALL", 1, selection.rows()),
                new Selection("2024-03", null, "BATCH", "ALL", 0, List.of(new DayKey(first, DATE), new DayKey(foreignEmployee, DATE))),
                new Selection("2024-03", null, "BATCH-B", "ALL", 0, selection.rows()),
                new Selection("2024-02", null, "BATCH", "ALL", 0, selection.rows()));
        for (Selection candidate : invalid) {
            mvc.perform(request(PATH + "/preview", manager, candidate).with(csrf())).andExpect(status().isConflict());
            var command = new Command(candidate.month(), candidate.organizationId(), candidate.q(), candidate.reason(), candidate.page(),
                    candidate.rows(), token, "대상 재검증", UUID.randomUUID());
            mvc.perform(request(PATH, manager, command).with(csrf())).andExpect(status().isConflict());
        }
        assertEquals(0, count("day_result"));
        assertEquals(0, count("org_audit_event"));
    }

    private MockHttpServletRequestBuilder request(String path, TnaPrincipal actor, Object body) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        return post(path).with(authentication(auth)).contentType("application/json").accept("application/json")
                .content(JSON.writeValueAsString(body));
    }

    private void extendEmploymentThroughMarch() {
        db.update("UPDATE tna.employee SET left_on=DATE '2024-03-31' WHERE tenant_id=? AND id IN (?,?)", tenant, first, second);
    }

    private TnaPrincipal account(String number, Set<String> roles) {
        UUID account = UUID.randomUUID(), employee = employee(number, null);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                tenant, account, employee, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employee, 1, roles, Instant.now());
    }

    private UUID employee(String number, LocalDate leftOn) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                tenant, id, number, "합성 " + number, DATE, leftOn);
        return id;
    }

    private Command command(String token, UUID id, String reason) {
        return new Command(selection.month(), null, selection.q(), selection.reason(), selection.page(), selection.rows(), token, reason, id);
    }

    private int executeStatus(Command command) {
        try {
            batches.execute(manager, command);
            return 200;
        } catch (OrgException error) {
            return error.status();
        }
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { ready.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { ready.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
        }
    }
}
