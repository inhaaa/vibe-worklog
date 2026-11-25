package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.LeaveForm;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 결재 처리·정정 제출 직후 대상 사원의 재집계 필요 날짜가 같은 요청 안에서 다시 계산되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalRecalculationIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired WorktimeService worktime;
    @Autowired LeaveWorkflowService flow;
    @Autowired ClockService clocks;
    @Autowired ApproverService approvers;
    private JdbcTemplate db;
    private UUID tenant;
    private LocalDate today, past;
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

    @BeforeEach void absentPastWorkday() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(SEOUL);
        past = today.minusDays(2);
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 결재 재집계 고객사')", tenant, "RECALC-" + tenant);
        manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("합성 직원", Set.of("EMPLOYEE"));
        approver = member("합성 승인자", Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(manager, new ApproverForm(employee.employeeId(), approver.employeeId(), today, 0L, "합성 승인 관계", UUID.randomUUID()));
        UUID template = UUID.randomUUID(), schedule = UUID.randomUUID();
        LocalDate from = today.minusDays(14);
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 고정 일정',?,?)",
                tenant, template, template.toString(), manager.accountId(), manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 버전',?)",
                tenant, schedule, template, from, manager.accountId());
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '08:00',TIME '17:00')", tenant, schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')", tenant, schedule, day);
        }
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 배정',?,?)",
                tenant, UUID.randomUUID(), employee.employeeId(), schedule, from, manager.accountId(), manager.accountId());
        CalendarSeed.period(db, manager, from, today.plusDays(30), "합성 근로일 검토", "");

        worktime.recalculate(manager, employee.employeeId(), past, past, UUID.randomUUID(), "합성 결근 계산");
        assertEquals("FULL", latest().get("absence"));
    }

    @Test void approvingFullDayLeaveReplacesTheStoredAbsence() throws Exception {
        UUID bucket = flow.openBucket(manager, new BucketForm(employee.employeeId(), past.minusDays(7), past.minusDays(7), today.plusDays(30), 2,
                "합성 기초 근거", "합성 기초", UUID.randomUUID()));
        // 지난 날짜는 신청할 수 없으므로 미래 날짜로 신청한 뒤 같은 일정·캘린더 버전의 지난 날짜로 옮긴다.
        LocalDate future = today.plusDays(2);
        UUID request = flow.submitLeave(employee, new LeaveForm(bucket, future, future, null, "합성 연차", UUID.randomUUID()));
        db.update("UPDATE tna.approval_request SET from_date=?,to_date=? WHERE tenant_id=? AND id=?", past, past, tenant, request);
        db.update("UPDATE tna.leave_request_day SET leave_date=? WHERE tenant_id=? AND request_id=?", past, tenant, request);

        decide(approver, request, "approve");

        var row = latest();
        assertNull(row.get("absence"));
        assertTrue(row.get("reason_codes").toString().contains("APPROVED_LEAVE_NO_ATTENDANCE"), row.get("reason_codes").toString());
    }

    @Test void correctionSubmissionRejectionAndWithdrawalRecalculateTheDay() throws Exception {
        UUID first = submitCorrection();
        var pending = latest();
        assertNull(pending.get("absence"), "대기 정정이 있는 무기록일은 결근이 아닙니다.");
        assertTrue(pending.get("reason_codes").toString().contains("PENDING_CORRECTION"), pending.get("reason_codes").toString());

        int before = results();
        decide(approver, first, "reject");
        assertEquals(before + 1, results());
        assertEquals("FULL", latest().get("absence"));

        UUID second = submitCorrection();
        assertNull(latest().get("absence"));
        before = results();
        decide(employee, second, "withdraw");
        assertEquals(before + 1, results());
        assertEquals("FULL", latest().get("absence"));
    }

    private UUID submitCorrection() throws Exception {
        var day = clocks.day(employee, past);
        var response = mvc.perform(post("/me/requests/correction").with(as(employee)).with(csrf())
                        .param("workDate", past.toString()).param("baseInputRevision", Long.toString(day.inputRevision()))
                        .param("sourceFingerprint", day.sourceFingerprint())
                        .param("inAction", "SET").param("requestedInAt", past + "T08:00")
                        .param("outAction", "SET").param("requestedOutAt", past + "T17:00")
                        .param("presenceAsWork", "true").param("breaksConfirmed", "true")
                        .param("actualBreaks", past + "T12:00/" + past + "T13:00")
                        .param("exceptional", "false").param("reason", "합성 정정").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        String url = response.getRedirectedUrl();
        assertNotNull(url);
        return UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
    }

    private void decide(TnaPrincipal actor, UUID request, String action) throws Exception {
        long version = db.queryForObject("SELECT version FROM tna.approval_request WHERE tenant_id=? AND id=?", Long.class, tenant, request);
        mvc.perform(post("/requests/{id}/" + action, request).with(as(actor)).with(csrf())
                        .param("expectedVersion", Long.toString(version)).param("reason", "합성 결재 처리")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
    }

    private int results() {
        return db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?",
                Integer.class, tenant, employee.employeeId(), past);
    }

    private Map<String, Object> latest() {
        return db.queryForMap("""
                SELECT absence, reason_codes::text AS reason_codes FROM tna.day_result
                WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1""",
                tenant, employee.employeeId(), past);
    }

    private TnaPrincipal member(String name, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, "E-" + employeeId, name, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",
                tenant, account, employeeId, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
