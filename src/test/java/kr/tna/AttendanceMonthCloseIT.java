package kr.tna;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.DecisionForm;
import kr.tna.worktime.AttendanceMonthCloseService;
import kr.tna.worktime.MonthEndReviewMapper;
import kr.tna.worktime.MonthEndReviewQueries;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class AttendanceMonthCloseIT {
    private static final String PATH = "/admin/attendance/month-close", MONTH = "2024-03";
    private static final String ACCEPT = "application/json, application/vnd.worklog.page+json;q=0.9";
    private static final LocalDate LAST = LocalDate.of(2024, 3, 31);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired AttendanceMonthCloseService months;
    @Autowired MonthEndReviewQueries reviews;
    @Autowired WorktimeService worktime;
    @Autowired kr.tna.clock.ClockService clocks;
    @Autowired AttendanceSettingsService settings;
    @Autowired LeaveWorkflowService workflow;
    @MockitoSpyBean MonthEndReviewMapper reviewMapper;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void companyCreatedAfterTheTestMonth() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 마감 회사')",
                tenant, "CLOSE-" + tenant);
        admin = account("담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    @Test
    void statusIsLightweightAndCheckCountsEmployeeDaysAcrossEveryPage() throws Exception {
        for (String name : List.of("사원 가", "사원 나")) employee(name, LAST.withDayOfMonth(1), LAST);
        var initial = body(statusRequest(admin, MONTH), 200);
        assertEquals("OPEN", initial.get("status").asString());
        assertEquals(0, initial.get("version").asLong());
        assertTrue(initial.get("history").isEmpty());
        assertFalse(initial.has("reviewDays"));
        verify(reviewMapper, never()).candidates(any(), any(), any(), any(), any(), any());
        var check = check(MONTH);
        assertEquals(2, check.get("employeeCount").asInt());
        assertEquals(62, check.get("reviewDays").asInt());
        assertEquals(62, check.get("unconfirmedDays").asInt());
        assertEquals(62, check.get("uncalculatedDays").asInt());
        for (var reason : Map.of("ALL", "reviewDays", "UNCALCULATED", "uncalculatedDays",
                "UNCONFIRMED", "unconfirmedDays", "STALE", "staleDays", "PENDING_CORRECTION", "pendingCorrectionDays").entrySet()) {
            var dates = new HashSet<String>();
            int page = 0;
            MonthEndReviewQueries.Page result;
            do {
                result = reviews.read(admin, MONTH, null, "", reason.getKey(), page++);
                for (var row : result.rows()) assertTrue(dates.add(row.employeeId() + "/" + row.workDate()));
            } while (result.hasNext());
            assertEquals(check.get(reason.getValue()).asInt(), dates.size(), reason.getKey());
        }
        assertEquals(0, check.get("staleDays").asInt());
        assertFalse(check.get("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "대상 전체 확인"), 409);
        assertEquals(0, count("attendance_month_closure"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test
    void checkIdentifiesTheFirstOutOfEmploymentFollowUpAndKeepsAllOfThemBlocking() throws Exception {
        UUID worker = knownRestDay();
        UUID leaver = employee("퇴직 사원", LAST.minusDays(3), LAST.minusDays(3));
        db.update("UPDATE tna.employee SET employee_no='FOLLOW-A' WHERE tenant_id=? AND id=?", tenant, worker);
        db.update("UPDATE tna.employee SET employee_no='FOLLOW-B' WHERE tenant_id=? AND id=?", tenant, leaver);
        db.update("""
                INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,reason,requested_by)
                VALUES (?,?,?,?,'이전 달 보완',?),(?,?,?,?,'입사 전 보완',?),(?,?,?,?,'재직기간 내 보완',?),
                       (?,?,?,?,'퇴사 후 보완',?)
                """, tenant, UUID.randomUUID(), worker, LAST.minusMonths(1), admin.accountId(),
                tenant, UUID.randomUUID(), worker, LAST.minusDays(1), admin.accountId(),
                tenant, UUID.randomUUID(), worker, LAST, admin.accountId(),
                tenant, UUID.randomUUID(), leaver, LAST.minusDays(1), admin.accountId());
        var first = check(MONTH);
        assertEquals(3, first.get("openFollowUps").asInt());
        var followUps = body(blockers(admin, MONTH, "OPEN_FOLLOW_UP", 0), 200);
        assertEquals(first.get("openFollowUps").asLong(), followUps.path("total").asLong());
        assertEquals(3, followUps.get("rows").size());
        assertEquals("CHECKOUT_FOLLOW_UP", followUps.at("/rows/0/kind").asString());
        assertFalse(followUps.at("/rows/0/outOfEmployment").asBoolean());
        assertTrue(followUps.at("/rows/1/outOfEmployment").asBoolean());
        assertTrue(followUps.at("/rows/2/outOfEmployment").asBoolean());
        assertEquals("FOLLOW-A", followUps.at("/rows/1/employeeNo").asString());
        assertEquals("FOLLOW-B", followUps.at("/rows/2/employeeNo").asString());
        assertFalse(first.get("canClose").asBoolean());
        var hint = first.path("outOfEmploymentFollowUp");
        assertEquals("FOLLOW-A", hint.path("employeeNo").asString(""));
        assertEquals(LAST.minusDays(1).toString(), hint.path("workDate").asString(""));
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", LAST.minusDays(1), tenant, worker);
        assertEquals("FOLLOW-B", check(MONTH).path("outOfEmploymentFollowUp").path("employeeNo").asString(""));
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", LAST.minusDays(1), tenant, leaver);
        var repaired = check(MONTH);
        assertFalse(repaired.hasNonNull("outOfEmploymentFollowUp"));
        assertEquals(3, repaired.get("openFollowUps").asInt());
        assertFalse(repaired.get("canClose").asBoolean());
    }

    @Test
    void aCrossMonthLeaveAndAnUnresolvedFollowUpInvalidateAnEarlierCleanCheck() throws Exception {
        UUID worker = knownRestDay();
        assertTrue(check(MONTH).get("canClose").asBoolean());
        UUID assignment = UUID.randomUUID(), bucket = UUID.randomUUID(), request = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 지정',?,?)
                """, tenant, assignment, worker, admin.employeeId(), LAST, admin.accountId(), admin.accountId());
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by)
                VALUES (?,?,?,?,?,'합성 기초',?)
                """, tenant, bucket, worker, LAST, LAST, admin.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,current_approver_id,
                    approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,
                    client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,'LEAVE_DAY',?,?,0,'월경계 연차',?,?,?,?,(SELECT v.id FROM tna.attendance_type_version v
                    JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                    WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1))
                """, tenant, request, worker, admin.accountId(), admin.employeeId(), assignment, bucket, LAST,
                LAST.plusDays(1), UUID.randomUUID(), tenant);
        var pending = check(MONTH);
        assertEquals(1, pending.get("pendingRequests").asInt());
        for (String month : List.of(MONTH, "2024-04")) {
            var list = body(blockers(admin, month, "PENDING_REQUEST", 0), 200);
            assertEquals(1, list.path("total").asInt());
            assertEquals(1, list.get("rows").size());
            assertEquals(request.toString(), list.at("/rows/0/id").asString());
            assertEquals(worker.toString(), list.at("/rows/0/employeeId").asString());
            assertEquals("LEAVE_DAY", list.at("/rows/0/kind").asString());
            assertEquals("2024-03-31", list.at("/rows/0/fromDate").asString());
            assertEquals("2024-04-01", list.at("/rows/0/toDate").asString());
        }
        assertTrue(body(blockers(admin, "2024-02", "PENDING_REQUEST", 0), 200).get("rows").isEmpty());
        String source = "/company/attendance?view=month-end&month=2024-04&q=abc&page=2";
        String back = "/company/attendance?view=close-blockers&month=2024-04&kind=PENDING_REQUEST&page=1&source="
                + URLEncoder.encode(source, StandardCharsets.UTF_8);
        assertEquals(back, body(get("/requests/" + request).with(as(admin)).accept("application/vnd.worklog.page+json")
                .param("returnTo", back), 200).at("/data/monthCloseReturnTo").asString());
        for (String invalid : List.of(back.replace("2024-04", "2024-05"),
                back.replace("view%3Dmonth-end", "view%3Dcheckout"), back.replace("PENDING_REQUEST", "OPEN_FOLLOW_UP"),
                back + "&page=2", "https://example.test" + back)) {
            assertTrue(body(get("/requests/" + request).with(as(admin)).accept("application/vnd.worklog.page+json")
                    .param("returnTo", invalid), 200).at("/data/monthCloseReturnTo").isMissingNode());
        }
        assertEquals(0, pending.get("reviewDays").asInt());
        assertFalse(pending.get("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "이전 점검 뒤 연차 대기"), 409);
        db.update("UPDATE tna.approval_request SET status='WITHDRAWN' WHERE tenant_id=? AND id=?", tenant, request);
        assertTrue(check(MONTH).get("canClose").asBoolean());
        assertTrue(body(blockers(admin, MONTH, "PENDING_REQUEST", 0), 200).get("rows").isEmpty());
        worktime.recalculate(admin, worker, LAST, LAST, UUID.randomUUID(), "확정 휴무 산정");
        db.update("INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,reason,requested_by) VALUES (?,?,?,?,'합성 보완',?)",
                tenant, UUID.randomUUID(), worker, LAST, admin.accountId());
        var followUp = check(MONTH);
        assertEquals(1, followUp.get("openFollowUps").asInt());
        assertEquals(0, followUp.get("reviewDays").asInt());
        assertEquals(0, followUp.get("pendingRequests").asInt());
        assertFalse(followUp.get("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "이전 점검 뒤 미해소 보완"), 409);
        assertEquals(0, count("attendance_month_closure"));
    }

    @Test
    void closesReopensAndReplaysTheImmutableOriginalReceipt() throws Exception {
        UUID worker = knownRestDay();
        worktime.recalculate(admin, worker, LAST, LAST, UUID.randomUUID(), "휴무일 산정");
        assertTrue(check(MONTH).get("canClose").asBoolean());
        UUID command = UUID.randomUUID();
        var closed = body(change(admin, "CLOSE", 0, command, "  월말 확인 완료  "), 200);
        assertEquals("CLOSED", closed.get("status").asString());
        assertEquals(1, closed.get("version").asInt());
        assertEquals("담당자", closed.get("actorName").asString());
        assertEquals("월말 확인 완료", closed.get("reason").asString());
        Instant.parse(closed.get("changedAt").asString());
        db.update("UPDATE tna.employee SET display_name='담당자 새 이름' WHERE tenant_id=? AND id=?", tenant, admin.employeeId());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", LAST.plusMonths(4), tenant, worker);
        assertEquals(0, check(MONTH).get("staleDays").asInt());
        assertTrue(reviews.read(admin, MONTH, null, "", "ALL", 0).rows().isEmpty());
        assertNotNull(reviews.read(admin, MONTH, null, "", "INVALID", 0).error());
        body(change(admin, "CLOSE", 1, UUID.randomUUID(), "이미 완료"), 409);
        body(change(admin, "REOPEN", 0, UUID.randomUUID(), "오래된 버전"), 409);
        body(change(admin, "REOPEN", 1, UUID.randomUUID(), "  "), 422);
        var reopened = body(change(admin, "REOPEN", 1, UUID.randomUUID(), "재직 정보 정정"), 200);
        assertEquals("OPEN", reopened.get("status").asString());
        assertEquals(2, reopened.get("version").asInt());
        assertEquals("담당자 새 이름", reopened.get("actorName").asString());
        assertEquals(1, check(MONTH).get("staleDays").asInt());
        assertEquals(1, reviews.read(admin, MONTH, null, "", "STALE", 0).rows().size());
        var history = body(statusRequest(admin, MONTH), 200).get("history");
        assertEquals(2, history.size());
        assertEquals("담당자", history.get(1).get("actorName").asString());
        assertEquals("재직 정보 정정", history.get(0).get("reason").asString());
        assertEquals(closed, body(change(admin, "CLOSE", 0, command, "월말 확인 완료"), 200));
        body(change(admin, "CLOSE", 0, command, "변경된 사유"), 409);
        assertEquals(2, count("attendance_month_closure"));
    }

    @Test
    void historyShowsTheMostRecentTwentyEventsInVersionOrder() throws Exception {
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                SELECT ?,gen_random_uuid(),DATE '2024-03-01',n,CASE WHEN n%2=1 THEN 'CLOSED' ELSE 'OPEN' END,
                    '합성 사유',?,'당시 담당자' FROM generate_series(1,22) n
                """, tenant, admin.accountId());
        var status = body(statusRequest(admin, MONTH), 200);
        assertTrue(status.get("hasMoreHistory").asBoolean());
        assertEquals("OPEN", status.get("status").asString());
        assertEquals(22, status.get("version").asInt());
        var history = status.get("history");
        assertEquals(20, history.size());
        for (int i = 0; i < history.size(); i++) assertEquals(22 - i, history.get(i).get("version").asInt());
    }

    @Test
    void currentAndFutureMonthsAndEmptyHistoricalMonthsCannotClose() throws Exception {
        var now = YearMonth.now(ZoneId.of("Asia/Seoul"));
        for (var month : List.of(now.toString(), now.plusMonths(1).toString(), MONTH)) {
            var check = check(month);
            assertEquals(month.equals(MONTH), check.get("pastMonth").asBoolean());
            assertFalse(check.get("canClose").asBoolean());
            body(postJson(admin, PATH, command(month, "CLOSE", 0, UUID.randomUUID(), "기간 확인")).with(csrf()), 409);
        }
        assertEquals(0, check(MONTH).get("employeeCount").asInt());
        for (String month : List.of("", "2024-3", "0000-01", "2024-13")) {
            body(statusRequest(admin, month), 422);
            body(postJson(admin, PATH + "/check", Map.of("month", month)).with(csrf()), 422);
        }
        body(change(admin, "REOPEN", 0, UUID.randomUUID(), "미마감 월"), 409);
        body(change(admin, "WRONG", 0, UUID.randomUUID(), "잘못된 명령"), 422);
        body(change(admin, "CLOSE", -1, UUID.randomUUID(), "잘못된 버전"), 422);
    }

    @Test
    void simultaneousCommandsAppendOnlyOneVersionAndCannotCrossTenantsOrReplayAfterRoleRevocation() throws Exception {
        knownRestDay();
        UUID firstCommand = UUID.randomUUID();
        var same = together(() -> body(change(admin, "CLOSE", 0, firstCommand, "동시 마감"), 200),
                () -> body(change(admin, "CLOSE", 0, firstCommand, "동시 마감"), 200));
        assertEquals(same.getFirst(), same.getLast());
        assertEquals(1, count("org_audit_event"));
        var distinct = together(() -> code(change(admin, "REOPEN", 1, UUID.randomUUID(), "동시 재개")),
                () -> code(change(admin, "REOPEN", 1, UUID.randomUUID(), "동시 재개")));
        assertEquals(List.of(200, 409), distinct.stream().sorted().toList());
        assertEquals(2, count("attendance_month_closure"));
        var database = DatabaseFixture.start();
        var app = new JdbcTemplate(database.appDataSource());
        var tx = new TenantTransactions(database.appDataSource());
        assertEquals(0, tx.execute(UUID.randomUUID(), () ->
                app.queryForObject("SELECT count(*) FROM tna.attendance_month_closure WHERE tenant_id=?", Integer.class, tenant)));
        assertThrows(DataAccessException.class, () -> tx.execute(UUID.randomUUID(), () -> app.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,DATE '2024-02-01',1,'CLOSED','합성 검사',?,'담당자')
                """, tenant, UUID.randomUUID(), admin.accountId())));
        assertThrows(DataAccessException.class, () -> tx.execute(tenant, () ->
                app.update("UPDATE tna.attendance_month_closure SET reason='수정' WHERE tenant_id=?", tenant)));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.attendance_month_closure','DELETE')", Boolean.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_auth','tna.attendance_month_closure','SELECT')", Boolean.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_operator','tna.attendance_month_closure','SELECT')", Boolean.class));
        for (String role : List.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role=?", tenant, admin.accountId(), role);
            assertEquals(401, code(statusRequest(admin, MONTH)));
            assertEquals(401, code(postJson(admin, PATH + "/check", Map.of("month", MONTH)).with(csrf())));
            assertEquals(401, code(change(admin, "CLOSE", 0, firstCommand, "동시 마감")));
            assertEquals(403, assertThrows(OrgException.class, () -> months.status(admin, MONTH)).status());
            assertEquals(403, assertThrows(OrgException.class, () -> months.check(admin, new AttendanceMonthCloseService.CheckForm(MONTH))).status());
            var replay = new AttendanceMonthCloseService.Command(MONTH, "CLOSE", 0L, "동시 마감", firstCommand);
            assertEquals(403, assertThrows(OrgException.class, () -> months.change(admin, replay)).status());
            db.update("UPDATE tna.role_grant SET active=true WHERE tenant_id=? AND account_id=? AND role=?", tenant, admin.accountId(), role);
        }
    }

    @Test
    void bothPostsRequireCsrfAndAllRoutesRequireAnAttendanceAdministrator() throws Exception {
        var employee = account("직원", Set.of("EMPLOYEE"));
        var representative = account("대표", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"));
        Object close = command(MONTH, "CLOSE", 0, UUID.randomUUID(), "권한 확인");
        for (var path : List.of(PATH, PATH + "/check")) {
            Object payload = path.equals(PATH) ? close : Map.of("month", MONTH);
            assertEquals(403, code(postJson(admin, path, payload)));
            for (var actor : List.of(employee, representative)) {
                assertEquals(403, code(postJson(actor, path, payload).with(csrf())));
                assertEquals(403, code(statusRequest(actor, MONTH)));
            }
        }
    }

    @Test
    void blockersPaginateByCaseAndIsolateOtherCompaniesAndRoles() throws Exception {
        for (int i = 0; i < 2; i++) {
            UUID id = employee("합성 보완 " + i, LAST.withDayOfMonth(1), null);
            db.update("""
                    INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,reason,requested_by)
                    SELECT ?,gen_random_uuid(),?,DATE '2024-03-01'+n,'보완 요청',? FROM generate_series(0,25) n
                    """, tenant, id, admin.accountId());
        }
        var owner = admin;
        companyCreatedAfterTheTestMonth();
        for (String kind : List.of("PENDING_REQUEST", "OPEN_FOLLOW_UP")) {
            var isolated = body(blockers(admin, MONTH, kind, 0).param("tenantId", owner.tenantId().toString()), 200);
            assertEquals(0, isolated.get("total").asInt());
            assertTrue(isolated.get("rows").isEmpty());
            for (var actor : List.of(account("일반", Set.of("EMPLOYEE")), account("대표", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE")))) {
                assertEquals(403, code(blockers(actor, MONTH, kind, 0)));
                assertEquals(403, code(get("/company/attendance").with(as(actor)).param("view", "close-blockers")
                        .param("month", MONTH).param("kind", kind).accept("application/vnd.worklog.page+json")));
            }
        }
        assertEquals(422, code(blockers(owner, MONTH, "UNKNOWN", 0)));
        assertEquals(422, code(blockers(owner, MONTH, "OPEN_FOLLOW_UP", -1)));
        assertEquals(422, code(blockers(owner, "2024-3", "PENDING_REQUEST", 0)));
        var first = body(blockers(owner, MONTH, "OPEN_FOLLOW_UP", 0), 200);
        var second = body(blockers(owner, MONTH, "OPEN_FOLLOW_UP", 1), 200);
        var beyond = body(blockers(owner, MONTH, "OPEN_FOLLOW_UP", 2), 200);
        assertEquals(52, first.get("total").asInt());
        assertEquals(52, beyond.get("total").asInt());
        assertEquals(50, first.get("rows").size());
        assertEquals(2, second.get("rows").size());
        assertTrue(first.get("hasNext").asBoolean());
        assertFalse(second.get("hasNext").asBoolean());
        assertTrue(beyond.get("rows").isEmpty());
        var ids = new HashSet<String>();
        for (var page : List.of(first, second)) for (var row : page.get("rows")) assertTrue(ids.add(row.get("id").asString()));
        var envelope = body(get("/company/attendance").with(as(owner)).param("view", "close-blockers")
                .param("month", MONTH).param("kind", "OPEN_FOLLOW_UP").param("page", "1")
                .accept("application/vnd.worklog.page+json"), 200);
        assertEquals(second, envelope.at("/data/monthCloseBlockers"));
        assertTrue(envelope.at("/data/canViewCheckout").asBoolean());
    }

    private static MockHttpServletRequestBuilder blockers(TnaPrincipal actor, String month, String kind, int page) {
        return get(PATH + "/blockers").with(as(actor)).param("month", month).param("kind", kind)
                .param("page", String.valueOf(page)).header("Accept", ACCEPT);
    }

    private UUID knownRestDay() {
        UUID worker = employee("월말 입사자", LAST, null);
        restSchedule(worker);
        UUID calendar = UUID.randomUUID();
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",
                tenant, calendar, LAST, LAST.plusDays(1), admin.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'NON_WORKDAY','휴무','합성 달력')",
                tenant, calendar, LAST);
        return worker;
    }

    private UUID restSchedule(UUID worker) {
        UUID template = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'REST','휴무 일정',?,?)",
                tenant, template, admin.accountId(), admin.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'휴무 일정',?)",
                tenant, version, template, LAST, admin.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday) VALUES (?,?,7,false)", tenant, version);
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'휴무 배정',?,?)",
                tenant, UUID.randomUUID(), worker, version, LAST, admin.accountId(), admin.accountId());
        return version;
    }

    private void useSelectiveCode(UUID version) {
        UUID code = UUID.randomUUID(), codeVersion = UUID.randomUUID();
        db.update("INSERT INTO tna.work_time_code(tenant_id,id,code) VALUES (?,?,'FL')", tenant, code);
        db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,end_time,
                    work_mode,standard_minutes,settlement_period,reason,created_by)
                VALUES (?,?,?,1,?,true,'선택근무',TIME '07:00',TIME '20:00','SELECTIVE',421,'CALENDAR_MONTH','합성',?)
                """, tenant, codeVersion, code, LAST, admin.accountId());
        db.update("UPDATE tna.schedule_day SET workday=true,start_time=TIME '07:00',end_time=TIME '20:00' WHERE tenant_id=? AND version_id=?", tenant, version);
        db.update("INSERT INTO tna.work_group_day(tenant_id,schedule_version_id,weekday,code_version_id) VALUES (?,?,7,?)", tenant, version, codeVersion);
    }

    private UUID selectiveFullDayLeaveWorker() {
        UUID worker = knownRestDay();
        UUID version = db.queryForObject("SELECT schedule_version_id FROM tna.schedule_assignment WHERE tenant_id=? AND employee_id=?", UUID.class, tenant, worker);
        UUID calendar = db.queryForObject("SELECT id FROM tna.calendar_version WHERE tenant_id=?", UUID.class, tenant);
        useSelectiveCode(version);
        db.update("UPDATE tna.calendar_day SET kind='WORKDAY' WHERE tenant_id=? AND work_date=?", tenant, LAST);
        UUID assignment = UUID.randomUUID(), bucket = UUID.randomUUID(), request = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성',?,?)
                """, tenant, assignment, worker, admin.employeeId(), LAST, admin.accountId(), admin.accountId());
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,?,?,'합성',?)",
                tenant, bucket, worker, LAST, LAST, admin.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                    approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,
                    client_command_id,attendance_type_version_id)
                SELECT ?,?,?,?,'LEAVE_DAY','APPROVED',?,?,0,'합성',?,?,?,?,v.id FROM tna.attendance_type_version v
                    JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                    WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1
                """, tenant, request, worker, admin.accountId(), admin.employeeId(), assignment, bucket, LAST, LAST, UUID.randomUUID(), tenant);
        db.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id) VALUES (?,?,?,?,1,?,?)",
                tenant, UUID.randomUUID(), request, LAST, version, calendar);
        db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by) VALUES (?,?,?,?,'LEAVE',?)",
                tenant, UUID.randomUUID(), request, worker, admin.accountId());
        return worker;
    }

    @Test
    void selectiveFullDayLeaveRequiresCalculationAndClosedSettlementKeepsItsOriginalInputs() throws Exception {
        UUID worker = selectiveFullDayLeaveWorker();
        var open = selectivePeriod(worker);
        assertEquals(421, open.path("targetMinutes").asInt());
        assertEquals(1, open.path("unconfirmedDays").asInt());
        assertEquals(0L, open.get("remainingMillis").asLong());
        assertTrue(open.path("differenceMillis").isNull());
        assertEquals(1, check(MONTH).path("uncalculatedDays").asInt());
        worktime.recalculate(admin, worker, LAST, LAST, UUID.randomUUID(), "선택근무 휴가 정산");
        var calculated = selectivePeriod(worker);
        assertEquals(0, calculated.path("unconfirmedDays").asInt());
        assertEquals(421 * 60_000L, calculated.path("paidLeaveMillis").asLong());
        assertEquals(0, calculated.path("confirmedWorkMillis").asLong());
        assertEquals(0, calculated.path("differenceMillis").asLong());
        assertTrue(check(MONTH).path("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "선택근무 마감"), 200);
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", LAST.plusDays(1), tenant, worker);
        db.update("UPDATE tna.work_time_code_version SET standard_minutes=360 WHERE tenant_id=? AND work_mode='SELECTIVE'", tenant);
        assertEquals(calculated, selectivePeriod(worker));
    }

    @Test
    void confirmedWorkConflictingWithApprovedFullDayLeaveHoldsTheSelectiveMonthOpen() throws Exception {
        UUID worker = selectiveFullDayLeaveWorker(), account = UUID.randomUUID(), network = UUID.randomUUID();
        db.update("UPDATE tna.work_time_code_version SET core_start=TIME '10:00',core_end=TIME '11:00' WHERE tenant_id=? AND work_mode='SELECTIVE'",tenant);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')",
                tenant, account, worker, account + "@example.test");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",tenant,account);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성',?,'합성',?)",
                tenant,network,java.sql.Timestamp.from(LAST.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),admin.accountId());
        for (String kind:List.of("IN","OUT")) {
            var at=java.sql.Timestamp.from(LAST.atTime("IN".equals(kind)?9:10,0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                        source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                    """,tenant,UUID.randomUUID(),worker,kind,at,at,UUID.randomUUID(),network,LAST);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",tenant,worker,LAST);
        var employee = new TnaPrincipal(tenant,account,worker,1,Set.of("EMPLOYEE"),Instant.now());
        var source=clocks.day(employee,LAST);
        clocks.confirm(employee,new kr.tna.clock.ClockData.ConfirmationForm(LAST,2L,0L,true,true,source.sourceFingerprint(),UUID.randomUUID()));
        worktime.recalculate(admin,worker,LAST,LAST,UUID.randomUUID(),"종일휴가 일자의 실근로 확인");
        var period=selectivePeriod(worker);
        assertEquals(3_600_000L,period.path("confirmedWorkMillis").asLong());
        assertEquals(421*60_000L,period.path("paidLeaveMillis").asLong());
        assertEquals(1,period.path("unconfirmedDays").asInt());
        assertTrue(period.path("differenceMillis").isNull());
        assertEquals(1,check(MONTH).path("unconfirmedDays").asInt());
        assertEquals("LEAVE_WORK_CONFLICT",period.at("/warnings/0").asString());
        body(change(admin,"CLOSE",0,UUID.randomUUID(),"종일휴가와 실근로 충돌 점검"),409);
        db.update("UPDATE tna.request_effect SET effect_status='CANCELED' WHERE tenant_id=? AND employee_id=? AND effect_kind='LEAVE'",tenant,worker);
        worktime.recalculate(admin,worker,LAST,LAST,UUID.randomUUID(),"휴가 취소 반영");
        var resolved=selectivePeriod(worker);
        assertEquals("SELECTIVE_CORE_REVIEW",resolved.at("/warnings/0").asString());
        assertEquals(-361*60_000L,resolved.path("differenceMillis").asLong());
        assertTrue(check(MONTH).path("canClose").asBoolean());
    }

    private JsonNode selectivePeriod(UUID worker) throws Exception {
        var result=body(get(PATH + "/selective-settlements").with(as(admin)).param("month", MONTH)
                .param("employeeId",worker.toString()).accept("application/json"), 200);
        assertEquals(1, result.path("periods").size());
        return result.at("/periods/0");
    }

    @Test
    void approvedSelectiveCorrectionNeedsAKnownCalendarBeforeTheMonthCanClose() throws Exception {
        var employee = account("선택근무 사원", Set.of("EMPLOYEE"));
        var approver = account("승인자", Set.of("EMPLOYEE", "APPROVER"));
        UUID worker = employee.employeeId();
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", LAST, tenant, worker);
        useSelectiveCode(restSchedule(worker));
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 지정',?,?)
                """, tenant, UUID.randomUUID(), worker, approver.employeeId(), LAST, admin.accountId(), admin.accountId());

        var before = clocks.day(employee, LAST);
        assertNull(before.calendarVersionId());
        UUID correction = workflow.submitCorrection(employee, new CorrectionForm(null, LAST, before.inputRevision(),
                before.sourceFingerprint(), "SET", LAST + "T09:00", "SET", LAST + "T10:00", true, true, "",
                null, null, false, "누락 기록 정정", UUID.randomUUID()));
        workflow.decide(approver, correction, new DecisionForm("APPROVE", 0L, "실근로 확인", UUID.randomUUID()));
        worktime.recalculate(admin, worker, LAST, LAST, UUID.randomUUID(), "승인 정정 반영");
        var pending = selectivePeriod(worker);
        assertEquals(3_600_000L, pending.get("confirmedWorkMillis").asLong());
        assertTrue(pending.get("targetMinutes").isNull());
        assertEquals(1, pending.get("unconfirmedDays").asInt());
        var beforeClose = check(MONTH);
        assertEquals(1, beforeClose.get("unconfirmedDays").asInt());
        assertFalse(beforeClose.get("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "달력 미등록"), 409);

        settings.calendar(admin, new CalendarForm(LAST, LAST, true, "근로일 확인", "", "달력 등록", UUID.randomUUID()));
        var registered = clocks.day(employee, LAST);
        clocks.confirm(employee, new ConfirmationForm(LAST, registered.inputRevision(), registered.reviewRevision(),
                true, true, registered.sourceFingerprint(), UUID.randomUUID()));
        worktime.recalculate(admin, worker, LAST, LAST, UUID.randomUUID(), "등록된 달력과 근무 확인 반영");
        var settled = selectivePeriod(worker);
        assertEquals(421, settled.get("targetMinutes").asInt());
        assertEquals(0, settled.get("unconfirmedDays").asInt());
        assertTrue(check(MONTH).get("canClose").asBoolean());
        body(change(admin, "CLOSE", 0, UUID.randomUUID(), "달력 확인 후 마감"), 200);
    }

    private UUID employee(String name, LocalDate joined, LocalDate left) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                tenant, id, id.toString(), name, joined, left);
        return id;
    }

    private TnaPrincipal account(String name, Set<String> roles) {
        UUID employee = employee(name, LAST.plusDays(1), null), account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')",
                tenant, account, employee, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employee, 1, roles, Instant.now());
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private JsonNode check(String month) throws Exception {
        return body(postJson(admin, PATH + "/check", Map.of("month", month)).with(csrf()), 200);
    }

    private static MockHttpServletRequestBuilder statusRequest(TnaPrincipal actor, String month) {
        return get(PATH).with(as(actor)).param("month", month).header("Accept", ACCEPT);
    }

    private static MockHttpServletRequestBuilder change(TnaPrincipal actor, String action, long version, UUID id, String reason) {
        return postJson(actor, PATH, command(MONTH, action, version, id, reason)).with(csrf());
    }

    private static Map<String, Object> command(String month, String action, long version, UUID id, String reason) {
        return Map.of("month", month, "action", action, "expectedVersion", version, "commandId", id, "reason", reason);
    }

    private static MockHttpServletRequestBuilder postJson(TnaPrincipal actor, String path, Object payload) {
        return post(path).with(as(actor)).contentType("application/json").header("Accept", ACCEPT).content(JSON.writeValueAsString(payload));
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    private JsonNode body(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return JSON.readTree(response.getContentAsByteArray());
    }

    private int code(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
        }
    }
}
