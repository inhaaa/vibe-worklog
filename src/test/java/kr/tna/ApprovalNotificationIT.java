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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.request.RequestData.ReassignForm;
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

/** 결재 이력마다 받는 사람에게 알림이 1건씩 생기는지(설계 §2) 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalNotificationIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired MockMvc mvc;
    @Autowired LeaveWorkflowService flow;
    @Autowired ApproverService approvers;
    @Autowired Clock clock;
    private JdbcTemplate db;
    private UUID tenant, bucket;
    private LocalDate today;
    private TnaPrincipal manager, employee, approver, substitute;

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

    @BeforeEach void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(clock.withZone(SEOUL));
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 알림 고객사')", tenant, "NOTIFY-" + tenant);
        manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("합성 직원", Set.of("EMPLOYEE"));
        approver = member("합성 승인자", Set.of("EMPLOYEE", "APPROVER"));
        substitute = member("합성 대결자", Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(manager, new ApproverForm(employee.employeeId(), approver.employeeId(), today, 0L, "합성 승인 관계", UUID.randomUUID()));
        UUID template = UUID.randomUUID(), schedule = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 고정 일정',?,?)",
                tenant, template, template.toString(), manager.accountId(), manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 버전',?)",
                tenant, schedule, template, today, manager.accountId());
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '08:00',TIME '17:00')", tenant, schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')", tenant, schedule, day);
        }
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 배정',?,?)",
                tenant, UUID.randomUUID(), employee.employeeId(), schedule, today, manager.accountId(), manager.accountId());
        CalendarSeed.period(db, manager, today, today.plusDays(30), "합성 근로일 검토", "");
        bucket = flow.openBucket(manager, new BucketForm(employee.employeeId(), today, today, today.plusDays(30), 5,
                "합성 기초 근거", "합성 기초", UUID.randomUUID()));
    }

    @Test void submissionNotifiesTheApproverAndApprovalNotifiesTheRequester() throws Exception {
        UUID request = leave(2);
        assertEquals(List.of(Map.of("recipient", approver.employeeId(), "kind", "APPROVAL_REQUESTED")), notifications(request));
        decide(approver, request, "approve");
        assertEquals(List.of(Map.of("recipient", approver.employeeId(), "kind", "APPROVAL_REQUESTED"),
                Map.of("recipient", employee.employeeId(), "kind", "APPROVED")), notifications(request));
    }

    @Test void rejectionNotifiesTheRequesterAndWithdrawalNotifiesNobody() throws Exception {
        UUID rejected = leave(2);
        decide(approver, rejected, "reject");
        assertEquals("REJECTED", notifications(rejected).get(1).get("kind"));
        UUID withdrawn = leave(3);
        decide(employee, withdrawn, "withdraw");
        assertEquals(1, notifications(withdrawn).size(), "회수는 알리지 않는다");
    }

    @Test void reassignmentNotifiesTheNewApprover() throws Exception {
        UUID request = leave(2);
        long version = db.queryForObject("SELECT version FROM tna.approval_request WHERE tenant_id=? AND id=?", Long.class, tenant, request);
        Integer step = db.queryForObject("SELECT current_step FROM tna.approval_request WHERE tenant_id=? AND id=?", Integer.class, tenant, request);
        flow.reassign(manager, request, new ReassignForm(substitute.employeeId(), version, "합성 대결", UUID.randomUUID(), step));
        assertEquals(Map.of("recipient", substitute.employeeId(), "kind", "APPROVAL_REQUESTED"), notifications(request).get(1));
    }

    private UUID leave(int days) {
        LocalDate date = today.plusDays(days);
        return flow.submitLeave(employee, new LeaveForm(bucket, date, date, null, "합성 연차", UUID.randomUUID()));
    }

    private List<Map<String, Object>> notifications(UUID request) {
        return db.queryForList("""
            SELECT n.recipient_employee_id AS recipient, n.kind FROM tna.notification n
            JOIN tna.approval_event e ON e.tenant_id=n.tenant_id AND e.id=n.event_id
            WHERE n.tenant_id=? AND n.request_id=? ORDER BY e.created_at, e.id""", tenant, request);
    }

    private void decide(TnaPrincipal actor, UUID request, String action) throws Exception {
        long version = db.queryForObject("SELECT version FROM tna.approval_request WHERE tenant_id=? AND id=?", Long.class, tenant, request);
        mvc.perform(post("/requests/{id}/" + action, request).with(as(actor)).with(csrf())
                        .param("expectedVersion", Long.toString(version)).param("reason", "합성 결재 처리")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
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
