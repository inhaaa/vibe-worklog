package kr.tna;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;
import kr.tna.clock.AttendanceHistory;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.DecisionForm;
import kr.tna.worktime.WorktimeData.AssessmentForm;
import kr.tna.worktime.MonthEndReviewQueries;
import kr.tna.worktime.CheckoutFollowUpService;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CheckoutOperationsWebIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    private static final String PATH = "/company/attendance";
    private static final String PRIVATE_REASON = "출력 금지 합성 정정 근거";

    @Autowired MockMvc mvc;
    @Autowired ClockService clocks;
    @Autowired LeaveWorkflowService workflow;
    @Autowired WorktimeService worktime;
    @Autowired CheckoutFollowUpService followUps;
    @Autowired MonthEndReviewQueries monthEndQueries;
    @Autowired AttendanceSettingsService attendanceSettings;
    @Autowired EmployeeService employees;
    private JdbcTemplate db;
    private UUID tenant, schedule, network;
    private LocalDate today, date;
    private TnaPrincipal admin, worker, approver, representative;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        var fixture = DatabaseFixture.start();
        props.add("spring.datasource.url", fixture::jdbcUrl);
        props.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        props.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        props.add("tna.auth.url", fixture::jdbcUrl);
        props.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        props.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void syntheticCompany() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(SEOUL);
        date = today.minusDays(1);
        tenant = company();
        admin = member(tenant, "ADMIN", "ATTENDANCE_ADMIN");
        worker = member(tenant, "CHECK-01", null);
        approver = member(tenant, "APPROVER", "APPROVER");
        representative = member(tenant, "REP", "COMPANY_REPRESENTATIVE");
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'합성 승인자',?,?)
                """, tenant, worker.employeeId(), approver.employeeId(), today.minusWeeks(1), admin.accountId(), admin.accountId());
        calendar();
        schedule = schedule(true);
        schedule(worker, schedule);
        network = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.allowed_network(tenant_id,id,label,network,valid_from,reason,created_by)
                VALUES (?,?,'합성 네트워크',CAST('127.0.0.1/32' AS cidr),?,'합성 범위',?)
                """, tenant, network, Timestamp.from(date.minusWeeks(1).atStartOfDay(SEOUL).toInstant()), admin.accountId());
    }

    @Test
    void onlyCurrentAttendanceAdministratorsReceiveTheCheckoutTabAndDateErrorsStayInTheTab() throws Exception {
        mvc.perform(get(PATH).with(as(admin)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canViewCheckout", true));
        mvc.perform(get(PATH).with(as(representative)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canViewCheckout").doesNotExist());
        mvc.perform(checkout(representative)).andExpect(status().isForbidden());
        mvc.perform(checkout(worker)).andExpect(status().isForbidden());
        mvc.perform(checkout(admin))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/checkoutOperations/date", date.toString()))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/latestDate", date.toString()));
        for (String value : new String[]{"2026-02-30", today.toString(), today.plusDays(1).toString()}) {
            mvc.perform(checkout(admin).param("date", value))
                    .andExpect(status().isUnprocessableContent()).andExpect(view().name("company/attendance"))
                    .andExpect(ReactPageAssertions.dataPresent("/checkoutOperations/error"))
                    .andExpect(jsonPath("$.data.checkoutOperations.rows").isEmpty());
        }
        mvc.perform(checkout(admin).param("status", "UNKNOWN"))
                .andExpect(status().isUnprocessableContent()).andExpect(ReactPageAssertions.dataPresent("/checkoutOperations/error"));
        UUID foreign = company();
        var other = member(foreign, "FOREIGN", "ATTENDANCE_ADMIN");
        mvc.perform(checkout(admin).param("organizationId", organization(foreign, other.accountId(), "SECRET").toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void findsOnlyWorkdaySourceInWithoutOutAndUsesTheOrganizationOnThatDate() throws Exception {
        UUID old = organization(tenant, admin.accountId(), "OLD"), current = organization(tenant, admin.accountId(), "NEW");
        assignment(worker, old, date.minusDays(1), today);
        assignment(worker, current, today, null);
        clock(worker, date, date, "IN", 9);
        clock(worker, today, today, "IN", 9);
        var complete = clockedWorker("COMPLETE", schedule);
        clock(complete, date, date, "OUT", 18);
        var duplicate = clockedWorker("DUPLICATE", schedule);
        clock(duplicate, date, date, "IN", 10);
        var overnight = member(tenant, "OVERNIGHT", null);
        schedule(overnight, schedule);
        clock(overnight, date, date.minusDays(1), "IN", 23);
        clockedWorker("DAY-OFF", schedule(false));
        var withoutSchedule = member(tenant, "NO-SCHEDULE", null);
        clock(withoutSchedule, date, date, "IN", 9);
        var unassignedOrg = clockedWorker("UNASSIGNED", schedule);

        var result = mvc.perform(checkout(admin).param("q", "check-").param("organizationId", old.toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/employeeId", worker.employeeId()))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/organizationName", "합성 OLD"))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/recordState", "MISSING"))
                .andReturn();
        var row = ReactPageAssertions.envelope(result).at("/data/checkoutOperations/rows/0");
        assertEquals(at(date, 9).toString(), row.get("sourceIn").asString());
        assertEquals(at(date, 9).toString(), row.get("effectiveIn").asString());
        assertTrue(row.get("sourceOut").isNull());
        assertTrue(row.get("effectiveOut").isNull());
        assertTrue(row.get("requestId").isNull());
        assertTrue(row.get("actualWorkMillis").isNull());
        mvc.perform(checkout(admin).param("organizationId", current.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutOperations.rows").isEmpty());
        mvc.perform(checkout(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutOperations.rows.length()").value(2))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/1/employeeId", unassignedOrg.employeeId()))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/1/organizationName", "소속 미등록"));

        approvedFullDayLeave();
        mvc.perform(checkout(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutOperations.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/employeeId", unassignedOrg.employeeId()));

        db.update("UPDATE tna.calendar_day SET kind='HOLIDAY' WHERE tenant_id=? AND work_date=?", tenant, date);
        mvc.perform(checkout(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutOperations.rows").isEmpty());
    }

    @Test
    void keepsOriginalMissingClockOutAcrossRejectionApprovalReplacementAndRecalculation() throws Exception {
        clock(worker, date, date, "IN", 9);
        var started = row("MISSING");
        assertEquals("WORK_CONFIRMATION_REQUIRED", started.get("resultState").asString());
        assertTrue(started.get("effectiveOut").isNull());

        UUID rejected = correction("18:00", null, true);
        var pending = row("PENDING");
        assertEquals(rejected.toString(), pending.get("requestId").asString());
        assertTrue(pending.get("effectiveOut").isNull());
        decide(rejected, "REJECT");
        var missing = row("MISSING");
        assertEquals(rejected.toString(), missing.get("requestId").asString());
        assertEquals("REJECTED", missing.get("requestState").asString());
        UUID withdrawn = correction("18:00", null, true);
        workflow.withdraw(worker, withdrawn, new DecisionForm("WITHDRAW", 0L, PRIVATE_REASON, UUID.randomUUID()));
        assertEquals("WITHDRAWN", row("MISSING").get("requestState").asString());

        worktime.assess(admin, new AssessmentForm(worker.employeeId(), date.minusDays(6), date.plusDays(6), true,
                "확인된 합성 근로 기준", PRIVATE_REASON, UUID.randomUUID()));
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        int prior = count("day_result");
        UUID approved = correction("18:00", null, true);
        decide(approved, "APPROVE");
        var corrected = row("CORRECTED");
        assertEquals("APPROVED", corrected.get("requestState").asString());
        assertEquals(approved.toString(), corrected.get("requestId").asString());
        assertEquals(at(date, 18).toString(), corrected.get("effectiveOut").asString());
        assertEquals("RECALCULATION_REQUIRED", corrected.get("resultState").asString());
        assertTrue(corrected.get("sourceOut").isNull());
        assertTrue(corrected.get("actualWorkMillis").isNull());
        assertTrue(corrected.get("calculatedAt").isNull());
        assertEquals(prior, count("day_result"));

        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        var fresh = row("CORRECTED");
        assertEquals("CURRENT", fresh.get("resultState").asString());
        assertEquals(28_800_000L, fresh.get("actualWorkMillis").asLong());
        assertFalse(fresh.get("calculatedAt").isNull());

        UUID replacement = correction("19:00", approved, false);
        pending = row("PENDING");
        assertEquals(replacement.toString(), pending.get("requestId").asString());
        assertEquals(at(date, 18).toString(), pending.get("effectiveOut").asString());
        assertTrue(pending.get("actualWorkMillis").isNull());
        decide(replacement, "APPROVE");
        var replaced = row("CORRECTED");
        assertEquals(replacement.toString(), replaced.get("requestId").asString());
        assertEquals(at(date, 19).toString(), replaced.get("effectiveOut").asString());
        assertEquals("WORK_CONFIRMATION_REQUIRED", replaced.get("resultState").asString());
        assertTrue(replaced.get("sourceOut").isNull());
        assertEquals(1, count("attendance_event"));
    }

    @Test
    void appliesStatusOrderAndFilteringBeforeSlicingFiftyResults() throws Exception {
        clock(worker, date, date, "IN", 9);
        UUID pending = correction("18:00", null, false);
        for (int i = 0; i < 50; i++) clockedWorker("M-%02d".formatted(i), schedule);
        mvc.perform(checkout(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutOperations.rows.length()").value(50))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/hasNext", true))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/employeeNo", "M-00"))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/49/employeeNo", "M-49"));
        mvc.perform(checkout(admin).param("page", "1")).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/checkoutOperations/hasPrevious", true))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/hasNext", false))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/requestId", pending));
        assertEquals("PENDING", row("PENDING").get("recordState").asString());
    }

    private JsonNode row(String state) throws Exception {
        var result = mvc.perform(checkout(admin).param("status", state))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(jsonPath("$.data.checkoutOperations.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/checkoutOperations/rows/0/recordState", state)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_REASON));
        assertFalse(result.getResponse().getContentAsString().contains("sourceFingerprint"));
        return ReactPageAssertions.envelope(result).at("/data/checkoutOperations/rows/0");
    }

    private MockHttpServletRequestBuilder checkout(TnaPrincipal actor) {
        return get(PATH).with(as(actor)).accept(PAGE_JSON).param("view", "checkout");
    }

    @Test
    void monthBatchBasisChangesWhenEvidenceChangesButTheVisibleReasonsStayTheSame() throws Exception {
        String month = YearMonth.from(date).toString();
        var key = new CheckoutFollowUpService.DayKey(worker.employeeId(), date);
        var before = monthEndQueries.readForBatch(admin, month, null, "CHECK-01", "ALL", 0);
        var basis = before.bases().get(key);
        assertNotNull(basis);
        assertEquals(before.page().rows().size(), before.bases().size());
        assertEquals(before.bases(), monthEndQueries.readForBatch(admin, month, null, "CHECK-01", "ALL", 0).bases());
        var labels = targetDay(reviewRows("ALL")).required("reasons");

        clock(worker, date, date, "IN", 9);
        var after = monthEndQueries.readForBatch(admin, month, null, "CHECK-01", "ALL", 0);
        assertEquals(labels, targetDay(reviewRows("ALL")).required("reasons"));
        assertNotEquals(basis, after.bases().get(key));
        assertFalse(reviewRows("ALL").toString().contains(after.bases().get(key)));
        assertTrue(monthEndQueries.readForBatch(admin, month, null, "CHECK-01", "ALL", 1).bases().isEmpty());
        assertThrows(OrgException.class, () -> monthEndQueries.readForBatch(worker, month, null, "CHECK-01", "ALL", 0));
    }

    @Test
    void monthBatchClosesTheApprovedEmployeesFollowUpAndKeepsTheOtherEmployeesOmissionVisible() throws Exception {
        clock(worker, date, date, "IN", 9);
        var other = clockedWorker("CHECK-02", schedule);
        requestFollowUp(date, "퇴근 시각과 휴게를 확인해 주세요.");
        decide(correction("18:00", null, true), "APPROVE");
        worktime.assess(admin, new AssessmentForm(worker.employeeId(), date, date, true,
                "근무 확인", PRIVATE_REASON, UUID.randomUUID()));
        assertEquals("AWAITING_CALCULATION", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());

        var command = new HashMap<String, Object>();
        command.put("month", YearMonth.from(date).toString());
        command.put("q", "CHECK-");
        command.put("reason", "ALL");
        command.put("page", 0);
        command.put("rows", List.of(new CheckoutFollowUpService.DayKey(worker.employeeId(), date),
                new CheckoutFollowUpService.DayKey(other.employeeId(), date)));
        int runs = count("calculation_run"), audit = count("org_audit_event");
        var json = JsonMapper.builder().build();
        var preview = mvc.perform(post("/admin/calculations/month-end/preview").with(as(admin)).with(csrf()).accept("application/json", PAGE_JSON)
                        .contentType("application/json").content(json.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.weekCount").value(2)).andReturn();
        assertEquals(runs, count("calculation_run"));
        assertEquals(audit, count("org_audit_event"));

        command.put("previewToken", json.readTree(preview.getResponse().getContentAsByteArray()).required("previewToken").asString());
        command.put("calculationReason", "보완 승인 반영과 남은 누락 확인");
        command.put("commandId", UUID.randomUUID());
        var response = mvc.perform(post("/admin/calculations/month-end").with(as(admin)).with(csrf()).accept("application/json", PAGE_JSON)
                        .contentType("application/json").content(json.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.rows.length()").value(2)).andReturn();
        var employees = new HashMap<UUID, JsonNode>();
        for (var row : json.readTree(response.getResponse().getContentAsByteArray()).required("rows")) {
            employees.put(UUID.fromString(row.required("employeeId").asString()), row);
            assertTrue(row.required("resultId").isString());
        }
        assertEquals(Set.of(worker.employeeId(), other.employeeId()), employees.keySet());
        var resolved = employees.get(worker.employeeId());
        assertEquals(28_800_000L, resolved.required("actualWorkMillis").asLong());
        assertTrue(resolved.required("reasons").isEmpty());
        var open = employees.get(other.employeeId());
        assertTrue(open.required("actualWorkMillis").isNull());
        assertEquals(json.readTree("[\"UNCONFIRMED\"]"), open.required("reasons"));
        assertEquals(runs + 2, count("calculation_run"));
        assertEquals(2, count("attendance_event"));
        for (var row : reviewRows("ALL")) assertNotEquals(date.toString(), row.required("workDate").asString());
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "CHECK-02")
                        .param("reason", "UNCONFIRMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows[0].workDate").value(date.toString()));
        mvc.perform(get("/me/attendance/follow-ups").with(as(worker)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutFollowUps.rows").isEmpty());
    }

    @Test
    void closedMonthKeepsConfirmedHoursAndNormalZeroAfterAFutureDepartureIsRecorded() throws Exception {
        var month = YearMonth.from(today).minusYears(2);
        date = month.atDay(2);
        var zero = month.atDay(22);
        var historical = member(tenant, "CLOSED-DAY", null);
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, historical.employeeId());
        schedule(historical, schedule(true));
        String holidays = date.datesUntil(month.atEndOfMonth().plusDays(1)).filter(day -> !day.equals(date))
                .map(day -> day + "|NON_WORKDAY|휴무일|합성 달력").collect(Collectors.joining("\n"));
        attendanceSettings.calendar(admin, new CalendarForm(date, month.atEndOfMonth(), true, "합성 근무 달력", holidays, "마감 점검", UUID.randomUUID()));
        clock(historical, date, date, "IN", 9);
        clock(historical, date, date, "OUT", 18);
        var day = clocks.day(historical, date);
        clocks.confirm(historical, new ConfirmationForm(date, day.inputRevision(), day.reviewRevision(), true, true, day.sourceFingerprint(), UUID.randomUUID()));
        worktime.recalculate(admin, historical.employeeId(), date, date, UUID.randomUUID(), "마감 전 집계");
        var saved = worktime.days(admin, historical.employeeId(), date, date).getFirst();
        assertEquals(28_800_000L, saved.result().actualWorkMillis());
        assertNull(worktime.days(admin, historical.employeeId(), zero, zero).getFirst().id());
        assertTrue(monthEndQueries.read(admin, month.toString(), null, "", "ALL", 0).rows().isEmpty());

        var command = Map.of("month", month.toString(), "action", "CLOSE", "expectedVersion", 0,
                "reason", "월 근태 확인 완료", "commandId", UUID.randomUUID());
        mvc.perform(post("/admin/attendance/month-close").with(as(admin)).with(csrf()).contentType("application/json")
                        .content(JsonMapper.builder().build().writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        var person = employees.get(admin, historical.employeeId());
        employees.update(admin, person.id(), new EmployeeForm(person.employeeNo(), person.displayName(), person.joinedOn(),
                today.plusMonths(1), true, person.version(), "향후 퇴직 예정일 등록", UUID.randomUUID()));

        var current = worktime.days(admin, person.id(), date, date).getFirst();
        assertTrue(current.current());
        assertEquals(saved.id(), current.id());
        assertEquals(28_800_000L, current.result().actualWorkMillis());
        assertTrue(worktime.detail(admin, saved.id()).day().current());
        var rest = worktime.days(admin, person.id(), zero, zero).getFirst();
        assertTrue(rest.current());
        assertNull(rest.id());
        assertEquals(0L, rest.result().actualWorkMillis());
        assertFalse(rest.pending());
        var export = worktime.prepareExport(admin, person.id(), zero, zero, UUID.randomUUID(), "마감 근거 확인");
        try (var workbook = new ZipInputStream(new ByteArrayInputStream(worktime.download(admin, export)))) {
            String sheet = null;
            for (var entry = workbook.getNextEntry(); entry != null; entry = workbook.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(workbook.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
            assertNotNull(sheet);
            assertTrue(sheet.contains("월 마감 근거로 확인"), "마감 월의 근거 있는 0시간을 출력에서도 구별한다");
        }
        assertTrue(monthEndQueries.read(admin, month.toString(), null, "", "ALL", 0).rows().isEmpty());
        long authVersion = db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?", Long.class, tenant, historical.accountId());
        var signedInAgain = new TnaPrincipal(tenant, historical.accountId(), person.id(), authVersion, historical.roles(), Instant.now());
        assertEquals(28_800_000L, worktime.attendanceHistory(signedInAgain, AttendanceHistory.Range.of(today, date, date)).getFirst().actualWorkMillis());
    }

    @Test
    void monthEndRestrictsTheTabToCurrentTenantAdministratorsAndValidMonths() throws Exception {
        mvc.perform(monthEnd(representative)).andExpect(status().isForbidden());
        mvc.perform(monthEnd(worker)).andExpect(status().isForbidden());
        mvc.perform(monthEnd(admin)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/monthEndReview/month", YearMonth.from(today).toString()))
                .andExpect(ReactPageAssertions.data("/monthEndReview/latestMonth", YearMonth.from(today).toString()));
        for (String invalid : new String[]{"0000-01", "2026-00", YearMonth.from(today).plusMonths(1).toString()}) {
            mvc.perform(monthEnd(admin).param("month", invalid)).andExpect(status().isUnprocessableContent())
                    .andExpect(ReactPageAssertions.dataPresent("/monthEndReview/error"))
                    .andExpect(jsonPath("$.data.monthEndReview.rows").isEmpty());
        }
        mvc.perform(monthEnd(admin).param("reason", "UNKNOWN")).andExpect(status().isUnprocessableContent());
        mvc.perform(monthEnd(admin).param("page", "-1")).andExpect(status().isUnprocessableContent());
        UUID foreign = company();
        var other = member(foreign, "FOREIGN", "ATTENDANCE_ADMIN");
        mvc.perform(monthEnd(admin).param("organizationId", organization(foreign, other.accountId(), "SECRET").toString()))
                .andExpect(status().isNotFound());
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "FOREIGN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows").isEmpty());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, admin.accountId());
        mvc.perform(monthEnd(admin)).andExpect(status().isUnauthorized());
        assertEquals(403, assertThrows(OrgException.class,
                () -> monthEndQueries.read(admin, null, null, "", "ALL", 0)).status());
    }

    @Test
    void monthEndUsesEmploymentAndOrganizationOnTheWorkDateAndExcludesToday() throws Exception {
        LocalDate first = date.minusDays(1);
        db.update("UPDATE tna.employee SET joined_on=?,left_on=?,active=false WHERE tenant_id=? AND id=?",
                first, today, tenant, worker.employeeId());
        var old = organization(tenant, admin.accountId(), "OLD");
        var moved = organization(tenant, admin.accountId(), "MOVED");
        assignment(worker, old, first, date);
        assignment(worker, moved, date, null);
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(first).toString()).param("q", "합성 CHECK")
                        .param("organizationId", old.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/workDate", first.toString()));
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "check-")
                        .param("organizationId", moved.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/organizationName", "합성 MOVED"))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/workDate", date.toString()));
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", first, tenant, worker.employeeId());
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "check-")
                        .param("organizationId", moved.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthEndReview.rows").isEmpty());
    }

    @Test
    void monthEndDistinguishesNormalAbsenceFromHalfDayHolidayWorkAndPendingCorrection() throws Exception {
        // Restrict the test employee's employment to a single day of the selected month.
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, worker.employeeId());
        var initial = reviewRows("ALL");
        assertEquals(1, initial.size());
        assertEquals("[\"UNCALCULATED\",\"UNCONFIRMED\"]", initial.get(0).get("reasons").toString());
        db.update("UPDATE tna.schedule_assignment SET schedule_version_id=? WHERE tenant_id=? AND employee_id=?",
                schedule(false), tenant, worker.employeeId());
        assertTrue(reviewRows("ALL").isEmpty());
        db.update("UPDATE tna.schedule_assignment SET schedule_version_id=? WHERE tenant_id=? AND employee_id=?",
                schedule, tenant, worker.employeeId());
        approvedFullDayLeave();
        assertTrue(reviewRows("ALL").isEmpty());
        db.update("UPDATE tna.leave_request_day SET quantity_days=0.5,leave_part='AM' WHERE tenant_id=?", tenant);
        assertEquals(1, reviewRows("UNCONFIRMED").size());
        db.update("UPDATE tna.calendar_day SET kind='HOLIDAY' WHERE tenant_id=? AND work_date=?", tenant, date);
        assertTrue(reviewRows("ALL").isEmpty());
        clock(worker, date, date, "IN", 9);
        assertEquals(1, reviewRows("UNCONFIRMED").size());
        db.update("UPDATE tna.calendar_day SET kind='WORKDAY' WHERE tenant_id=? AND work_date=?", tenant, date);
        UUID request = correction("18:00", null, false);
        db.update("UPDATE tna.calendar_day SET kind='HOLIDAY' WHERE tenant_id=? AND work_date=?", tenant, date);
        var pending = reviewRows("PENDING_CORRECTION").get(0);
        assertEquals("[\"UNCALCULATED\",\"UNCONFIRMED\",\"PENDING_CORRECTION\"]", pending.get("reasons").toString());
        assertEquals(request.toString(), pending.get("pendingRequestId").asString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        var current = reviewRows("UNCONFIRMED").get(0);
        assertFalse(current.get("resultId").isNull());
        assertEquals("[\"UNCONFIRMED\",\"PENDING_CORRECTION\"]", current.get("reasons").toString());
        int results = count("day_result");
        decide(request, "REJECT");
        assertEquals("[\"STALE\",\"UNCONFIRMED\"]", reviewRows("STALE").get(0).get("reasons").toString());
        assertEquals(results, count("day_result"));
    }

    @Test
    void monthEndShowsOnlyRecalculationForConfirmedEvidenceAndUsesCurrentPolicyEvenWithoutARevisionBump() throws Exception {
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, worker.employeeId());
        clock(worker, date, date, "IN", 9);
        UUID fix = correction("18:00", null, true);
        decide(fix, "APPROVE");
        assertEquals("[\"UNCALCULATED\"]", reviewRows("ALL").get(0).get("reasons").toString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        assertTrue(reviewRows("ALL").isEmpty());
        worktime.assess(admin, new AssessmentForm(worker.employeeId(), date, date, true, "합성 기준 변경",
                PRIVATE_REASON, UUID.randomUUID()));
        var stale = reviewRows("STALE");
        assertEquals(1, stale.size());
        assertEquals("[\"STALE\"]", stale.get(0).get("reasons").toString());
    }

    @Test
    void monthEndKeepsPreviouslyCalculatedWorkdaysThatNowQualifyAsNormalLeave() throws Exception {
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, worker.employeeId());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        approvedFullDayLeave();
        assertEquals("[\"STALE\"]", reviewRows("ALL").get(0).get("reasons").toString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        assertTrue(reviewRows("ALL").isEmpty());
    }

    @Test
    void monthEndUsesEmployeesOwnWorkConfirmationAndReturnsOnlyDaysInTheSelectedMonth() throws Exception {
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, worker.employeeId());
        clock(worker, date, date, "IN", 9);
        clock(worker, date, date, "OUT", 18);
        assertEquals(1, reviewRows("UNCONFIRMED").size());
        var day = clocks.day(worker, date);
        clocks.confirm(worker, new ConfirmationForm(date, day.inputRevision(), day.reviewRevision(), true, true,
                day.sourceFingerprint(), UUID.randomUUID()));
        assertEquals("[\"UNCALCULATED\"]", reviewRows("ALL").get(0).get("reasons").toString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), PRIVATE_REASON);
        assertTrue(reviewRows("ALL").isEmpty());

        var previous = YearMonth.from(today).minusMonths(1);
        var historical = member(tenant, "HIST", null);
        db.update("UPDATE tna.employee SET joined_on=?,left_on=?,active=false WHERE tenant_id=? AND id=?",
                previous.atDay(1), previous.atEndOfMonth(), tenant, historical.employeeId());
        mvc.perform(monthEnd(admin).param("q", "HIST").param("month", previous.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(previous.lengthOfMonth()))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/workDate", previous.atEndOfMonth().toString()))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/" + (previous.lengthOfMonth() - 1) + "/workDate",
                        previous.atDay(1).toString()));
        mvc.perform(monthEnd(admin).param("q", "HIST").param("month", previous.minusMonths(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows").isEmpty());
    }

    @Test
    void monthEndFiltersBeforePagingAndUsesStableEmployeeNumbersOnTheSameDate() throws Exception {
        for (int i = 0; i < 51; i++) {
            var person = member(tenant, "PAGE-%02d".formatted(i), null);
            db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date, tenant, person.employeeId());
        }
        var first = monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "PAGE-").param("reason", "UNCALCULATED");
        mvc.perform(first).andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(50))
                .andExpect(ReactPageAssertions.data("/monthEndReview/hasNext", true))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/employeeNo", "PAGE-00"))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/49/employeeNo", "PAGE-49"));
        mvc.perform(first.param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/monthEndReview/hasPrevious", true))
                .andExpect(ReactPageAssertions.data("/monthEndReview/hasNext", false))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/employeeNo", "PAGE-50"));
        db.update("UPDATE tna.employee SET employee_no='Z-PAGE-LAST' WHERE tenant_id=? AND id=?", tenant, worker.employeeId());
        clock(worker, date, date, "IN", 9);
        var pending = correction("18:00", null, false);
        mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString()).param("q", "PAGE-").param("reason", "PENDING_CORRECTION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.monthEndReview.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/monthEndReview/rows/0/pendingRequestId", pending));
    }

    @Test
    void followUpActionOnlyAppearsOnAnEligibleMissingCheckout() throws Exception {
        assertFalse(targetDay(reviewRows("ALL")).required("checkoutFollowUpEligible").asBoolean());
        clock(worker, date, date, "IN", 9);
        assertTrue(targetDay(reviewRows("ALL")).required("checkoutFollowUpEligible").asBoolean());
        correction("18:00", null, false);
        assertFalse(targetDay(reviewRows("ALL")).required("checkoutFollowUpEligible").asBoolean());
    }

    @Test
    void followUpConnectsManagerAndEmployeeUntilTheConfirmedDayIsRecalculated() throws Exception {
        clock(worker, date, date, "IN", 9);
        String reason = "퇴근 시각과 실제 휴게를 확인해 주세요.";
        requestFollowUp(date, reason);
        var initial = targetDay(reviewRows("UNCONFIRMED"));
        assertFalse(initial.required("checkoutFollowUpEligible").asBoolean());
        JsonNode followUp = initial.required("checkoutFollowUp");
        assertEquals("REQUESTED", followUp.required("status").asString());
        assertEquals(reason, followUp.required("reason").asString());
        for (String path : List.of("/me/home", "/me/attendance/follow-ups")) {
            var own = mvc.perform(get(path).with(as(worker)).accept(PAGE_JSON)).andExpect(status().isOk()).andReturn();
            var entry = ReactPageAssertions.envelope(own).at("/data/checkoutFollowUps/rows/0");
            assertEquals(followUp.required("id"), entry.required("id"));
            assertEquals(date.toString(), entry.required("workDate").asString());
            mvc.perform(get(path).with(as(approver)).accept(PAGE_JSON).param("employeeId", worker.employeeId().toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutFollowUps.rows").isEmpty());
        }

        var rejected = correction("18:00", null, true);
        decide(rejected, "REJECT");
        assertEquals("REQUESTED", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        var accepted = correction("18:00", null, true);
        var pending = targetDay(reviewRows("PENDING_CORRECTION")).required("checkoutFollowUp");
        assertEquals("PENDING_APPROVAL", pending.required("status").asString());
        assertEquals(accepted.toString(), pending.required("approvalRequestId").asString());
        worktime.assess(admin, new AssessmentForm(worker.employeeId(), date.minusDays(6), date.plusDays(6), true,
                "근무 확인", PRIVATE_REASON, UUID.randomUUID()));
        decide(accepted, "APPROVE");
        assertEquals("AWAITING_CALCULATION", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "퇴근 정정 반영 확인");
        for (var row : reviewRows("ALL")) assertNotEquals(date.toString(), row.required("workDate").asString());
        mvc.perform(get("/me/attendance/follow-ups").with(as(worker)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.checkoutFollowUps.rows").isEmpty());
        assertEquals(1, count("attendance_event"));
    }

    @Test
    void monthEndKeepsApprovedZeroWorkFollowUpUntilTheFirstDayResultIsSaved() throws Exception {
        clock(worker, date, date, "IN", 9);
        requestFollowUp(date, "잘못 찍힌 출근을 확인해 주세요.");
        var before = clocks.day(worker, date);
        var request = workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "CLEAR", "", "KEEP", "", false, false, "", null, null, false, PRIVATE_REASON, UUID.randomUUID()));
        decide(request, "APPROVE");
        approvedFullDayLeave();
        var row = targetDay(reviewRows("UNCALCULATED"));
        assertEquals("AWAITING_CALCULATION", row.at("/checkoutFollowUp/status").asString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "승인된 휴가일 확인");
        for (var item : reviewRows("ALL")) assertNotEquals(date.toString(), item.required("workDate").asString());
    }

    @Test
    void employeeCanOpenTheInboxWhenTheHomePreviewIsFull() throws Exception {
        for (int offset = 0; offset < 4; offset++) {
            var workDate = date.minusDays(offset);
            clock(worker, workDate, workDate, "IN", 9);
            requestFollowUp(workDate, "퇴근 시각 확인");
        }
        mvc.perform(get("/me/home").with(as(worker)).accept(PAGE_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutFollowUps.rows.length()").value(3))
                .andExpect(jsonPath("$.data.checkoutFollowUps.hasNext").value(true));
        mvc.perform(get("/me/attendance/follow-ups").with(as(worker)).accept(PAGE_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutFollowUps.rows.length()").value(4))
                .andExpect(jsonPath("$.data.checkoutFollowUps.hasNext").value(false));
    }

    private void requestFollowUp(LocalDate workDate, String reason) throws Exception {
        String command = JsonMapper.builder().build().writeValueAsString(Map.of(
                "employeeId", worker.employeeId(), "workDate", workDate.toString(), "commandId", UUID.randomUUID(), "reason", reason));
        mvc.perform(post("/admin/attendance/follow-ups").with(as(admin)).with(csrf())
                        .contentType("application/json").content(command))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").exists());
    }

    private JsonNode targetDay(JsonNode rows) {
        for (var row : rows) if (date.toString().equals(row.required("workDate").asString())) return row;
        throw new AssertionError("대상 근무일이 월말 목록에 없습니다.");
    }

    private MockHttpServletRequestBuilder monthEnd(TnaPrincipal actor) {
        return get(PATH).with(as(actor)).accept(PAGE_JSON).param("view", "month-end");
    }

    private JsonNode reviewRows(String reason) throws Exception {
        var result = mvc.perform(monthEnd(admin).param("month", YearMonth.from(date).toString())
                        .param("q", "CHECK-01").param("reason", reason))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.dataPresent("/monthEndReview/rows")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_REASON));
        assertFalse(result.getResponse().getContentAsString().contains("sourceFingerprint"));
        return ReactPageAssertions.envelope(result).at("/data/monthEndReview/rows");
    }

    @Test
    void followUpCommandSerializesDuplicateRequestsAndReplaysTheSameReceipt() throws Exception {
        clock(worker, date, date, "IN", 9);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (var command : java.util.List.of(first, second)) futures.add(pool.submit(() -> {
                gate.await();
                return mvc.perform(followUpCommand(admin, worker.employeeId(), date, command, "퇴근 확인"))
                        .andReturn().getResponse().getStatus();
            }));
            gate.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(200, 409), new java.util.HashSet<>(statuses));
            var committed = statuses.getFirst() == 200 ? first : second;
            var repeat = mvc.perform(followUpCommand(admin, worker.employeeId(), date, committed, "퇴근 확인"))
                    .andExpect(status().isOk()).andReturn();
            var id = new tools.jackson.databind.json.JsonMapper().readTree(repeat.getResponse().getContentAsByteArray()).required("id").asString();
            assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=? AND id=?", Integer.class, tenant, UUID.fromString(id)));
            mvc.perform(followUpCommand(admin, worker.employeeId(), date, committed, "다른 사유")).andExpect(status().isConflict());
        }
        assertEquals(1, count("checkout_follow_up"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='CHECKOUT_FOLLOW_UP_REQUEST'", Integer.class, tenant));
    }

    @Test
    void followUpUsesCurrentAuthorizationSeoulDateAndEligibilityAtWriteTime() throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(worker, worker.employeeId(), date, UUID.randomUUID(), "권한 확인")).andExpect(status().isForbidden());
        mvc.perform(followUpCommand(representative, worker.employeeId(), date, UUID.randomUUID(), "권한 확인")).andExpect(status().isForbidden());
        var another = member(company(), "FOREIGN", "ATTENDANCE_ADMIN");
        mvc.perform(followUpCommand(admin, another.employeeId(), date, UUID.randomUUID(), "대상 확인")).andExpect(status().isNotFound());
        mvc.perform(followUpCommand(admin, worker.employeeId(), today, UUID.randomUUID(), "근무일 확인")).andExpect(status().isUnprocessableContent());
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "  ")).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/attendance/follow-ups").with(as(admin)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(followUpCommand(admin, approver.employeeId(), date, UUID.randomUUID(), "미출근")).andExpect(status().isConflict());
        var pending = correction("18:00", null, true);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "이미 결재 대기")).andExpect(status().isConflict());
        decide(pending, "APPROVE");
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "퇴근 정정 완료")).andExpect(status().isConflict());
        mvc.perform(followUpCommand(admin, worker.employeeId(), today.minusYears(2), UUID.randomUUID(), "입사 전")).andExpect(status().isUnprocessableContent());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'", tenant, admin.accountId());
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "회수된 권한").accept("application/json"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        var denied = assertThrows(OrgException.class, () -> followUps.request(admin,
                new CheckoutFollowUpService.Form(worker.employeeId(), date, "권한 회수 후 등록", UUID.randomUUID())));
        assertEquals(403, denied.status());
        assertEquals(0, count("checkout_follow_up"));
    }

    @Test
    void incompleteApprovedCorrectionWaitsForConfirmationAndTheAutomaticCalculation() throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "퇴근 확인")).andExpect(status().isOk());
        var request = correction("18:00", null, false);
        decide(request, "APPROVE");
        assertEquals("NEEDS_CONFIRMATION", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "확인 전 집계");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=? AND resolved_at IS NOT NULL", Integer.class, tenant));
        var unconfirmed = clocks.day(worker, date);
        UUID command = UUID.randomUUID();
        clocks.confirm(worker, new ConfirmationForm(date, unconfirmed.inputRevision(), unconfirmed.reviewRevision(), true, true, unconfirmed.sourceFingerprint(), command));
        assertEquals("AWAITING_CALCULATION", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        worktime.calculateConfirmedDay(worker, date, command);
        assertEquals(1, db.queryForObject("""
                SELECT count(*) FROM tna.checkout_follow_up f JOIN tna.day_result r
                  ON r.tenant_id=f.tenant_id AND r.id=f.resolved_result_id AND r.employee_id=f.employee_id AND r.work_date=f.work_date
                WHERE f.tenant_id=? AND f.resolved_at IS NOT NULL AND r.actual_work_millis=28800000
                """, Integer.class, tenant));
        assertEquals(1, count("attendance_event"));
    }

    @Test
    void followUpRowsRequireMatchingTenantContextAndGrantNoDeletePrivilege() throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "테넌트별 보완")).andExpect(status().isOk());
        try (var connection = DatabaseFixture.start().appDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var tenantContext = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM tna.checkout_follow_up WHERE employee_id=?")) {
                count.setObject(1, worker.employeeId());
                for (var context : java.util.List.of("", tenant.toString(), UUID.randomUUID().toString(), "")) {
                    tenantContext.setString(1, context);
                    tenantContext.execute();
                    try (var rows = count.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(tenant.toString().equals(context) ? 1 : 0, rows.getInt(1));
                    }
                }
            } finally { connection.rollback(); }
        }
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.checkout_follow_up','DELETE')", Boolean.class));
    }

    @Test
    void teamApprovalKeepsTheFollowUpPendingUntilOfficeApprovalAndCalculation() throws Exception {
        UUID team = organization(tenant, admin.accountId(), "TEAM"), office = organization(tenant, admin.accountId(), "OFFICE");
        var officeHead = member(tenant, "OFFICE-HEAD", "APPROVER");
        db.update("UPDATE tna.organization SET approval_unit='OFFICE' WHERE tenant_id=? AND id=?", tenant, office);
        db.update("UPDATE tna.organization SET parent_id=?,approval_unit='TEAM' WHERE tenant_id=? AND id=?", office, tenant, team);
        assignment(worker, team, date, null);
        for (var entry : Map.of(team, approver.employeeId(), office, officeHead.employeeId()).entrySet()) {
            db.update("""
                    INSERT INTO tna.organization_head_assignment(tenant_id,id,organization_id,employee_id,valid_from,reason,created_by,updated_by)
                    VALUES (?,gen_random_uuid(),?,?,?,'조직장 배정',?,?)
                    """, tenant, entry.getKey(), entry.getValue(), date, admin.accountId(), admin.accountId());
        }
        UUID route = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'T2','실장까지',?,?)",
                tenant, route, admin.accountId(), admin.accountId());
        db.update("""
                INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,terminal_unit,reason,created_by)
                VALUES (?,?,?,1,'실장까지','ORGANIZATION_CHAIN','OFFICE','순차 결재',?)
                """, tenant, version, route, admin.accountId());
        db.update("""
                INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,route_version_id,reason,created_by)
                SELECT tenant_id,gen_random_uuid(),id,2,?,true,'출퇴근 정정',?,'순차 결재',? FROM tna.request_form WHERE tenant_id=? AND code='A01'
                """, today, version, admin.accountId(), tenant);
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "퇴근 확인")).andExpect(status().isOk());
        var preview = workflow.formPreview(worker, worker.employeeId(), "ATTENDANCE_CORRECTION");
        assertEquals(List.of(approver.employeeId(), officeHead.employeeId()), preview.steps().stream().map(s -> s.approverId()).toList());
        var before = clocks.day(worker, date);
        var form = new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(), "KEEP", "", "SET", date + "T18:00",
                true, true, date + "T12:00/" + date + "T13:00", null, null, false, PRIVATE_REASON, UUID.randomUUID());
        UUID request = workflow.submitCorrection(worker, form, preview.routeToken());
        workflow.decide(approver, request, new DecisionForm("APPROVE", 0L, "팀장 검토", UUID.randomUUID(), 1));
        assertEquals("PENDING_APPROVAL", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        assertEquals(1, clocks.day(worker, date).effectiveEvents().size());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "중간 결재 중 집계");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=? AND resolved_at IS NULL", Integer.class, tenant));
        workflow.decide(officeHead, request, new DecisionForm("APPROVE", 1L, "실장 최종 검토", UUID.randomUUID(), 2));
        assertEquals("AWAITING_CALCULATION", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "최종 결재 후 집계");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=? AND last_request_id=? AND resolved_result_id IS NOT NULL", Integer.class, tenant, request));
        assertEquals(1, count("attendance_event"));
    }

    @Test
    void withdrawalReopensResponseAndANewOmissionAfterCalculationGetsANewFollowUp() throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "첫 보완")).andExpect(status().isOk());
        UUID withdrawn = correction("18:00", null, true);
        workflow.withdraw(worker, withdrawn, new DecisionForm("WITHDRAW", 0L, PRIVATE_REASON, UUID.randomUUID()));
        assertEquals("REQUESTED", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/status").asString());
        UUID approved = correction("18:00", null, true);
        decide(approved, "APPROVE");
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "정상 집계");
        var before = clocks.day(worker, date);
        var clear = workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "KEEP", "", "CLEAR", "", false, false, "", approved, null, false, PRIVATE_REASON, UUID.randomUUID()));
        decide(clear, "APPROVE");
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "재발 확인")).andExpect(status().isOk());
        assertEquals(2, count("checkout_follow_up"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=? AND last_request_id=? AND resolved_at IS NOT NULL", Integer.class, tenant, approved));
        assertEquals("재발 확인", targetDay(reviewRows("ALL")).at("/checkoutFollowUp/reason").asString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FULL,true", "HOLIDAY,true", "HALF,false", "WORKDAY,false"})
    void zeroWorkRequiresAnApprovedClearAndAffirmativeCalendarOrFullLeave(String basis, boolean zeroConfirmed) throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "잘못 기록된 출근 확인")).andExpect(status().isOk());
        var before = clocks.day(worker, date);
        UUID request = workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "CLEAR", "", "KEEP", "", false, false, "", null, null, false, PRIVATE_REASON, UUID.randomUUID()));
        if (basis.equals("FULL") || basis.equals("HALF")) {
            approvedFullDayLeave();
            if (basis.equals("HALF")) db.update("UPDATE tna.leave_request_day SET quantity_days=0.5,leave_part='AM' WHERE tenant_id=?", tenant);
        }
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "최종 결재 전 집계");
        assertEquals("PENDING_APPROVAL", followUps.mine(worker, 0, 1).rows().getFirst().status());
        decide(request, "APPROVE");
        if (basis.equals("HOLIDAY")) db.update("UPDATE tna.calendar_day SET kind='HOLIDAY' WHERE tenant_id=? AND work_date=?", tenant, date);
        assertTrue(clocks.day(worker, date).effectiveEvents().isEmpty());
        assertEquals(zeroConfirmed ? "AWAITING_CALCULATION" : "REQUESTED", followUps.mine(worker, 0, 1).rows().getFirst().status());
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "무근로일 확인");
        assertEquals(zeroConfirmed, followUps.mine(worker, 0, 1).rows().isEmpty());
        assertEquals(zeroConfirmed ? 1 : 0, db.queryForObject("""
                SELECT count(*) FROM tna.checkout_follow_up f JOIN tna.day_result r ON r.tenant_id=f.tenant_id AND r.id=f.resolved_result_id
                WHERE f.tenant_id=? AND f.last_request_id=? AND f.resolved_at IS NOT NULL AND r.actual_work_millis=0
                """, Integer.class, tenant, request));
        assertEquals(1, count("attendance_event"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REJECT", "WITHDRAW"})
    void rejectedOrWithdrawnClearCannotSettleAFullLeaveDay(String decision) throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "퇴근 확인")).andExpect(status().isOk());
        var before = clocks.day(worker, date);
        UUID submitted = workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "CLEAR", "", "KEEP", "", false, false, "", null, null, false, PRIVATE_REASON, UUID.randomUUID()));
        approvedFullDayLeave();
        if (decision.equals("REJECT")) decide(submitted, decision);
        else workflow.withdraw(worker, submitted, new DecisionForm(decision, 0L, PRIVATE_REASON, UUID.randomUUID()));
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "효력 확인");
        var response = followUps.mine(worker, 0, 1).rows().getFirst();
        assertEquals("REQUESTED", response.status());
        assertEquals(submitted, response.approvalRequestId());
        assertEquals(1, clocks.day(worker, date).effectiveEvents().size());
    }

    @Test
    void unsupportedOvernightCorrectionStillRequiresAnEmployeeResponse() throws Exception {
        clock(worker, date, date, "IN", 9);
        mvc.perform(followUpCommand(admin, worker.employeeId(), date, UUID.randomUUID(), "퇴근 확인")).andExpect(status().isOk());
        var before = clocks.day(worker, date);
        UUID submitted = workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "SET", date.minusDays(1) + "T23:00", "SET", date + "T08:00", true, true, "", null, null, false, PRIVATE_REASON, UUID.randomUUID()));
        decide(submitted, "APPROVE");
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "자정 통과 확인");
        assertEquals("REQUESTED", followUps.mine(worker, 0, 1).rows().getFirst().status());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"REJECT,true", "WITHDRAW,true", "REJECT,false", "WITHDRAW,false"})
    void rejectedReplacementLinksTheEffectiveCorrectionOnBothScreensAndInTheSettledResult(String decision, boolean confirmed) throws Exception {
        clock(worker, date, date, "IN", 9);
        requestFollowUp(date, "퇴근 시각 확인");
        UUID effective = correction("18:00", null, confirmed);
        decide(effective, "APPROVE");
        UUID replacement = correction("19:00", effective, true);
        var pending = followUps.mine(worker, 0, 1).rows().getFirst();
        assertEquals("PENDING_APPROVAL", pending.status());
        assertEquals(replacement, pending.approvalRequestId());
        if (decision.equals("REJECT")) decide(replacement, decision);
        else workflow.withdraw(worker, replacement, new DecisionForm(decision, 0L, PRIVATE_REASON, UUID.randomUUID()));

        var mine = JsonMapper.builder().build().readTree(mvc.perform(get("/me/attendance/follow-ups").with(as(worker)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).at("/data/checkoutFollowUps/rows/0");
        var month = targetDay(reviewRows("ALL")).at("/checkoutFollowUp");
        var employeeDetail = JsonMapper.builder().build().readTree(mvc.perform(get("/requests/" + mine.required("approvalRequestId").asString())
                .with(as(worker)).accept(PAGE_JSON)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).at("/data/request");
        var adminDetail = JsonMapper.builder().build().readTree(mvc.perform(get("/requests/" + month.required("approvalRequestId").asString())
                .with(as(admin)).accept(PAGE_JSON)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).at("/data/request");

        var day = clocks.day(worker, date);
        assertEquals(at(date, 18), day.effectiveEvents().getLast().occurredAt());
        if (!confirmed) clocks.confirm(worker, new ConfirmationForm(date, day.inputRevision(), day.reviewRevision(), true, true,
                day.sourceFingerprint(), UUID.randomUUID()));
        worktime.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "유효한 퇴근 반영 확인");
        UUID appliedRequest = db.queryForObject("""
                SELECT f.last_request_id FROM tna.checkout_follow_up f JOIN tna.day_result r ON r.tenant_id=f.tenant_id AND r.id=f.resolved_result_id
                WHERE f.tenant_id=? AND f.employee_id=r.employee_id AND f.work_date=r.work_date AND f.resolved_at IS NOT NULL AND r.actual_work_millis=28800000
                """, UUID.class, tenant);
        String expectedStatus = confirmed ? "AWAITING_CALCULATION" : "NEEDS_CONFIRMATION";
        assertAll(
                () -> assertEquals(expectedStatus, mine.required("status").asString()),
                () -> assertEquals(expectedStatus, month.required("status").asString()),
                () -> assertEquals(effective.toString(), employeeDetail.required("id").asString(), "직원 상세"),
                () -> assertEquals("APPROVED", employeeDetail.required("status").asString()),
                () -> assertEquals(effective.toString(), adminDetail.required("id").asString(), "월말 상세"),
                () -> assertEquals("APPROVED", adminDetail.required("status").asString()),
                () -> assertEquals(effective, appliedRequest, "해소에 적용된 정정"),
                () -> assertEquals(decision.equals("REJECT") ? "REJECTED" : "WITHDRAWN", workflow.get(worker, replacement).request().status()),
                () -> assertEquals(1, count("attendance_event")));
    }

    @Test
    void selectiveDayKeepsTheOriginalEmployeeAndCrossMonthWeekAfterRecalculation() throws Exception {
        date = LocalDate.of(2026, 9, 30);
        var staff = member(tenant, "WEEKLY", null);
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", date.minusWeeks(1), tenant, staff.employeeId());
        UUID version = schedule(true);
        schedule(staff, version);
        useSelectiveGroup(staff, version, "ISO_WEEK");
        String employee = staff.employeeId().toString(), day = date.toString();
        String period = "month=2026-10&settlementPeriod=ISO_WEEK&settlementFrom=2026-09-28";
        String origin = "/admin/attendance?employeeId=" + employee + "&" + period;
        mvc.perform(get("/admin/attendance").with(as(admin)).param("employeeId",employee).param("month","2026-09")
                .param("workDate",day).param("settlementReturn",origin).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/settlementWorkDate",day))
                .andExpect(ReactPageAssertions.data("/settlementReturn",origin));
        mvc.perform(get("/admin/calendar").with(as(admin)).param("date",day).param("settlementReturn",origin).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/form/from",day))
                .andExpect(ReactPageAssertions.data("/form/to",day)).andExpect(ReactPageAssertions.data("/settlementReturn",origin));
        mvc.perform(post("/admin/calendar/versions").with(as(admin)).with(csrf()).param("from","2026-09-29")
                .param("to","2026-10-02").param("date",day).param("settlementReturn",origin).accept(PAGE_JSON)
                .param("reviewed","false").param("basis","달력 검토 중").param("exceptions","").param("reason","기간 검토")
                .param("commandId",UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(ReactPageAssertions.data("/settlementWorkDate",day))
                .andExpect(ReactPageAssertions.data("/settlementReturn",origin));
        mvc.perform(post("/admin/calculations").with(as(admin)).with(csrf()).param("employeeId",employee)
                .param("from",day).param("to",day).param("reason","선택근무 미확정일 확인").param("commandId",UUID.randomUUID().toString())
                .param("settlementReturn",origin)).andExpect(status().isSeeOther()).andExpect(redirectedUrl(origin));

        String mine = "/me/worktime?" + period;
        for (String path : List.of("/me/attendance", "/me/requests/correction")) {
            mvc.perform(get(path).with(as(staff)).param("date",day).param("settlementReturn",mine).accept(PAGE_JSON))
                    .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/settlementReturn",mine));
            mvc.perform(get(path).with(as(staff)).param("date",day).param("settlementReturn",origin).accept(PAGE_JSON))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementReturn").doesNotExist());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIXED", "CALENDAR_MONTH"})
    void fixedAndMonthlyAssignmentsDoNotRestoreAWeeklySettlement(String actualPolicy) throws Exception {
        if ("CALENDAR_MONTH".equals(actualPolicy)) useSelectiveGroup(worker, schedule, actualPolicy);
        String day = date.toString(), month = YearMonth.from(date).toString(), employee = worker.employeeId().toString();
        String period = "month=" + month + "&settlementPeriod=ISO_WEEK&settlementFrom=" + date.with(DayOfWeek.MONDAY);
        String origin = "/admin/attendance?employeeId=" + employee + "&" + period;
        mvc.perform(get("/me/attendance").with(as(worker)).param("date",day).param("settlementReturn","/me/worktime?" + period)
                .accept(PAGE_JSON)).andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementReturn").doesNotExist());
        mvc.perform(get("/admin/attendance").with(as(admin)).param("employeeId",employee).param("month",month)
                .param("workDate",day).param("settlementReturn",origin).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementReturn").doesNotExist())
                .andExpect(jsonPath("$.data.settlementWorkDate").doesNotExist());
        mvc.perform(post("/admin/calculations").with(as(admin)).with(csrf()).param("employeeId",employee)
                .param("from",day).param("to",day).param("reason","대상일 재집계").param("commandId",UUID.randomUUID().toString())
                .param("settlementReturn",origin)).andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/admin/attendance?employeeId=" + employee + "&month=" + month));
    }

    @Test
    void pendingCorrectionOpensAndWithdrawsWithinTheAuthorizedSettlement() throws Exception {
        useSelectiveGroup(worker, schedule, "CALENDAR_MONTH");
        clock(worker, date, date, "IN", 9);
        UUID request = correction("18:00", null, true);
        String period = "month=" + YearMonth.from(date) + "&settlementPeriod=CALENDAR_MONTH&settlementFrom=" + date.withDayOfMonth(1);
        String mine = "/me/worktime?" + period;
        String adminOrigin = "/admin/attendance?employeeId=" + worker.employeeId() + "&" + period;
        String path = "/requests/" + request;
        mvc.perform(get(path).with(as(worker)).param("settlementReturn",mine).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/settlementReturn",mine));
        mvc.perform(get(path).with(as(admin)).param("settlementReturn",adminOrigin).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/settlementReturn",adminOrigin));
        mvc.perform(get(path).with(as(worker)).param("settlementReturn",adminOrigin).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementReturn").doesNotExist());
        mvc.perform(get(path).with(as(admin)).param("settlementReturn",adminOrigin.replace(worker.employeeId().toString(),admin.employeeId().toString())).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementReturn").doesNotExist());
        mvc.perform(post(path + "/withdraw").with(as(worker)).with(csrf()).param("expectedVersion","0")
                .param("commandId",UUID.randomUUID().toString()).param("reason","정정 재검토").param("settlementReturn",mine))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(mine));
    }

    private MockHttpServletRequestBuilder followUpCommand(TnaPrincipal actor, UUID employee, LocalDate workDate, UUID command, String reason) {
        var payload = new tools.jackson.databind.json.JsonMapper().writeValueAsString(java.util.Map.of(
                "employeeId", employee, "workDate", workDate.toString(), "reason", reason, "commandId", command));
        return post("/admin/attendance/follow-ups").with(as(actor)).with(csrf()).contentType("application/json").content(payload);
    }

    private UUID correction(String out, UUID replaces, boolean confirmed) {
        var before = clocks.day(worker, date);
        return workflow.submitCorrection(worker, new CorrectionForm(null, date, before.inputRevision(), before.sourceFingerprint(),
                "KEEP", "", "SET", date + "T" + out, confirmed, confirmed,
                confirmed ? date + "T12:00/" + date + "T13:00" : "", replaces, null, false, PRIVATE_REASON, UUID.randomUUID()));
    }

    private void decide(UUID request, String decision) {
        workflow.decide(approver, request, new DecisionForm(decision, 0L, PRIVATE_REASON, UUID.randomUUID()));
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 퇴근 회사')", id, "CHECK-" + id);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, String role) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                company, employee, number, "합성 " + number, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                company, account, employee, account + "@example.test");
        var roles = role == null ? Set.of("EMPLOYEE") : Set.of("EMPLOYEE", role);
        for (String value : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, value);
        return new TnaPrincipal(company, account, employee, 1, roles, Instant.now());
    }

    private UUID organization(UUID company, UUID actor, String code) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                company, id, code, "합성 " + code, actor, actor);
        return id;
    }

    private void assignment(TnaPrincipal person, UUID organization, LocalDate from, LocalDate to) {
        db.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,?,'합성 소속',?,?)
                """, tenant, person.employeeId(), organization, from, to, admin.accountId(), admin.accountId());
    }

    private void calendar() {
        UUID version = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                VALUES (?,?,1,?,?,'합성 기준',?)
                """, tenant, version, date.minusWeeks(1), date.plusWeeks(1).plusDays(1), admin.accountId());
        for (LocalDate day = date.minusWeeks(1); !day.isAfter(date.plusWeeks(1)); day = day.plusDays(1)) {
            db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'WORKDAY','합성 근무일','합성 달력')",
                    tenant, version, day);
        }
    }

    private void approvedFullDayLeave() {
        UUID request = UUID.randomUUID(), bucket = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by)
                VALUES (?,?,?,?,?,'합성 종일 연차',?)
                """, tenant, bucket, worker.employeeId(), date, date, admin.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                  approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,'LEAVE_DAY','APPROVED',?,
                  (SELECT id FROM tna.approver_assignment WHERE tenant_id=? AND employee_id=?),0,'합성 연차',?,?,?,gen_random_uuid(),
                  (SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                   WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1))
                """, tenant, request, worker.employeeId(), worker.accountId(), approver.employeeId(), tenant, worker.employeeId(),
                bucket, date, date, tenant);
        db.update("""
                INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id)
                VALUES (?,gen_random_uuid(),?,?,1,?,(SELECT id FROM tna.calendar_version WHERE tenant_id=?))
                """, tenant, request, date, schedule, tenant);
        db.update("""
                INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by)
                VALUES (?,gen_random_uuid(),?,?,'LEAVE',?)
                """, tenant, request, worker.employeeId(), approver.accountId());
    }

    private UUID schedule(boolean workday) {
        UUID template = UUID.randomUUID(), version = UUID.randomUUID(), actor = admin.accountId();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 일정',?,?)",
                tenant, template, template.toString(), actor, actor);
        db.update("""
                INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by)
                VALUES (?,?,?,1,?,'합성 기준',?)
                """, tenant, version, template, date.minusWeeks(1), actor);
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,?,CAST(? AS time),CAST(? AS time))",
                    tenant, version, day, workday, workday ? "09:00" : null, workday ? "18:00" : null);
            if (workday) db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",
                    tenant, version, day);
        }
        return version;
    }

    private void schedule(TnaPrincipal person, UUID version) {
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'합성 배정',?,?)
                """, tenant, person.employeeId(), version, date.minusWeeks(1), admin.accountId(), admin.accountId());
    }

    private void useSelectiveGroup(TnaPrincipal person, UUID version, String period) {
        UUID group = db.queryForObject("SELECT template_id FROM tna.schedule_version WHERE tenant_id=? AND id=?", UUID.class, tenant, version);
        UUID code = UUID.randomUUID(), codeVersion = UUID.randomUUID();
        db.update("INSERT INTO tna.work_group(tenant_id,id) VALUES (?,?)", tenant, group);
        db.update("INSERT INTO tna.work_time_code(tenant_id,id,code) VALUES (?,?,'SEL')", tenant, code);
        db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,end_time,
                    work_mode,standard_minutes,settlement_period,reason,created_by)
                VALUES (?,?,?,1,?,true,'선택근무','09:00','18:00','SELECTIVE',480,?,'합성 정산 기준',?)
                """, tenant, codeVersion, code, date.minusWeeks(1), period, admin.accountId());
        db.update("""
                INSERT INTO tna.work_group_day(tenant_id,schedule_version_id,weekday,code_version_id)
                SELECT tenant_id,version_id,weekday,? FROM tna.schedule_day WHERE tenant_id=? AND version_id=? AND workday
                """, codeVersion, tenant, version);
        assertEquals(1, db.update("UPDATE tna.schedule_assignment SET work_group_id=? WHERE tenant_id=? AND employee_id=? AND schedule_version_id=?",
                group, tenant, person.employeeId(), version));
    }

    private TnaPrincipal clockedWorker(String number, UUID version) {
        var person = member(tenant, number, null);
        schedule(person, version);
        clock(person, date, date, "IN", 9);
        return person;
    }

    private void clock(TnaPrincipal person, LocalDate assigned, LocalDate occurred, String kind, int hour) {
        var time = Timestamp.from(at(occurred, hour));
        db.update("""
                INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date)
                VALUES (?,gen_random_uuid(),?,?,?,?,gen_random_uuid(),'IP',CAST('127.0.0.1' AS inet),?,?)
                """, tenant, person.employeeId(), kind, time, time, network, assigned);
        db.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,1,0)
                ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE SET input_revision=tna.day_input_revision.input_revision+1
                """, tenant, person.employeeId(), assigned);
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private static Instant at(LocalDate day, int hour) { return day.atTime(hour, 0).atZone(SEOUL).toInstant(); }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
