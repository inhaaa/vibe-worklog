package kr.tna;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.DecisionForm;
import kr.tna.worktime.WorktimeData.AssessmentForm;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
