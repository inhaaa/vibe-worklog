package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.schedule.ScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EmployeeOnboardingIT {
    private static final String PATH = "/admin/employees/onboarding";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired MockMvc mvc;
    @Autowired InvitationDelivery delivery;
    @MockitoSpyBean ScheduleMapper schedules;
    private JdbcTemplate database;
    private TnaPrincipal manager;
    private UUID organization;
    private UUID schedule;

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
    void seedCompany() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        manager = member(UUID.randomUUID(), "ATTENDANCE_ADMIN");
        UUID tenant = manager.tenantId(), actor = manager.accountId();
        organization = UUID.randomUUID();
        schedule = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        database.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES(?,?,?,?,?,?)",
                tenant, organization, "ORG", "합성 조직", actor, actor);
        database.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES(?,?,?,?,?,?)",
                tenant, template, "REGULAR", "합성 고정 근무", actor, actor);
        database.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES(?,?,?,1,?,?,?)",
                tenant, schedule, template, TODAY, "합성 준비", actor);
    }

    @Test
    void plannedRecordCreatesNothingElseUntilAnIdempotentConfirmation() throws Exception {
        var draft = create(TODAY.plusDays(1));
        assertEquals(1, count("employee"));
        assertEquals(0, count("employee_assignment"));
        assertEquals(0, count("schedule_assignment"));
        UUID command = UUID.randomUUID();
        mvc.perform(confirm(draft, command)).andExpect(status().isSeeOther());
        mvc.perform(confirm(draft, command)).andExpect(status().isSeeOther());
        mvc.perform(confirm(draft, UUID.randomUUID())).andExpect(status().isConflict());
        assertEquals(2, count("employee"));
        assertEquals(1, count("tenant_account"));
        assertEquals(1, count("employee_assignment"));
        assertEquals(1, count("schedule_assignment"));
        assertEquals(1L, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Long.class, manager.tenantId(), command));
        assertEquals(TODAY.plusDays(1), database.queryForObject("SELECT valid_from FROM tna.schedule_assignment WHERE tenant_id=?",
                LocalDate.class, manager.tenantId()));

        UUID employee = database.queryForObject("SELECT employee_id FROM tna.employee_onboarding WHERE tenant_id=? AND id=?",
                UUID.class, manager.tenantId(), draft);
        String number = database.queryForObject("SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id=?",
                String.class, manager.tenantId(), employee);
        mvc.perform(auth(get(PATH + "/{id}", draft), manager)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/onboarding/inviteEligible", false));
        mvc.perform(auth(post("/admin/employees/{id}/invitations", employee), manager)
                        .param("email", employee + "@example.test").param("expectedVersion", "0")
                        .param("reason", "미래 입사 전 초대 반례").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(auth(post("/admin/employees/{id}/changes", employee), manager)
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "확정일 수정 반례")
                        .param("expectedVersion", "0").param("employeeNo", number).param("displayName", "합성 입사자")
                        .param("joinedOn", TODAY.toString()).param("active", "true"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(auth(post("/admin/employees/bulk"), manager)
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "확정일 일괄 수정 반례")
                        .param("rows", """
                                [{"clientRowId":"%s","id":"%s","expectedVersion":0,"operation":"UPDATE",
                                  "employeeNo":"%s","displayName":"합성 입사자","joinedOn":"%s","active":true}]
                                """.formatted(UUID.randomUUID(), employee, number, TODAY)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void transitionAndDateAndTenantFailuresPreserveDrafts() throws Exception {
        mvc.perform(draft(post(PATH), TODAY.minusDays(1))).andExpect(status().isUnprocessableContent());
        UUID draft = create(TODAY);
        mvc.perform(draft(post(PATH + "/{id}/changes", draft), TODAY).param("expectedVersion", "0"))
                .andExpect(status().isSeeOther());
        mvc.perform(confirm(draft, UUID.randomUUID())).andExpect(status().isConflict());
        mvc.perform(auth(post(PATH + "/{id}/withdraw", draft), manager)
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "합성 철회")
                        .param("expectedVersion", "1"))
                .andExpect(status().isSeeOther());
        assertEquals("WITHDRAWN", database.queryForObject("SELECT state FROM tna.employee_onboarding WHERE tenant_id=? AND id=?",
                String.class, manager.tenantId(), draft));
        assertEquals(1, count("employee"));
        mvc.perform(auth(get(PATH + "/{id}", draft), member(UUID.randomUUID(), "ATTENDANCE_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get(PATH), member(UUID.randomUUID(), "COMPANY_REPRESENTATIVE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void failureAfterWritingEmployeeAndOrganizationRollsBackTheCommand() throws Exception {
        UUID draft = create(TODAY);
        UUID command = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("합성 근무 배정 실패")).when(schedules)
                .insertAssignment(eq(manager.tenantId()), any(), any(), eq(schedule), eq(TODAY), any(), any());
        mvc.perform(confirm(draft, command)).andExpect(status().isConflict());
        assertEquals(1, count("employee"));
        assertEquals(0, count("employee_assignment"));
        assertEquals(0, count("schedule_assignment"));
        assertEquals("PLANNED", database.queryForObject("SELECT state FROM tna.employee_onboarding WHERE tenant_id=? AND id=?",
                String.class, manager.tenantId(), draft));
        assertEquals(0L, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Long.class, manager.tenantId(), command));
    }

    @Test
    void firstDayUsesTheExistingInvitationAndShowsTheInitialAppointmentBeforeClockIn() throws Exception {
        UUID draft = create(TODAY);
        mvc.perform(confirm(draft, UUID.randomUUID())).andExpect(status().isSeeOther());
        UUID employee = database.queryForObject("SELECT employee_id FROM tna.employee_onboarding WHERE tenant_id=? AND id=?",
                UUID.class, manager.tenantId(), draft);
        String employeeNo = database.queryForObject("SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id=?",
                String.class, manager.tenantId(), employee);
        database.update("""
                INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time)
                VALUES(?,?,?,true,TIME '09:00',TIME '18:00')
                """, manager.tenantId(), schedule, TODAY.getDayOfWeek().getValue());
        String email = employee + "@example.test";
        String password = "합성 onboarding acceptance 41!";
        mvc.perform(auth(post("/admin/employees/{id}/invitations", employee), manager)
                        .param("email", email).param("expectedVersion", "0").param("reason", "최초 초대 테스트")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
        var invitation = delivery.messages().stream().filter(message -> message.email().equals(email)).findFirst().orElseThrow();
        mvc.perform(post("/invite/{companyCode}/accept", invitation.companyCode()).with(csrf())
                        .param("email", email).param("token", invitation.token())
                        .param("password", password).param("confirmation", password))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?invited"));
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("companyCode", invitation.companyCode()).param("email", employeeNo).param("password", password))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);

        mvc.perform(get("/me/profile").session(session)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/profile/employeeNo", employeeNo))
                .andExpect(ReactPageAssertions.data("/personalDetails/appointments/0/organizationName", "합성 조직"))
                .andExpect(ReactPageAssertions.data("/personalDetails/appointments/0/initial", true))
                .andExpect(result -> {
                    var appointment = ReactPageAssertions.envelope(result).at("/data/personalDetails/appointments/0");
                    assertTrue(appointment.path("reason").isMissingNode());
                    assertTrue(appointment.path("decidedBy").isMissingNode());
                });
        mvc.perform(get("/me/schedule").session(session).param("date", TODAY.toString())).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/selectedDay/scheduleVersionId", schedule.toString()));
        mvc.perform(auth(post("/admin/networks/changes"), manager).param("network", "127.0.0.1/32")
                        .param("label", "합성 허용망").param("_wideConfirmed", "on").param("reason", "첫날 출근 검증")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
        mvc.perform(post("/me/attendance/events").session(session).with(csrf()).with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }).param("eventKind", "IN").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/me/attendance"));
        assertEquals(1L, database.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=?",
                Long.class, manager.tenantId(), employee));
    }

    private UUID create(LocalDate joinedOn) throws Exception {
        String location = mvc.perform(draft(post(PATH), joinedOn)).andExpect(status().isSeeOther())
                .andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private MockHttpServletRequestBuilder draft(MockHttpServletRequestBuilder request, LocalDate joinedOn) {
        return auth(request, manager).param("displayName", "합성 입사자").param("joinedOn", joinedOn.toString())
                .param("commandId", UUID.randomUUID().toString()).param("reason", "합성 예정");
    }

    private MockHttpServletRequestBuilder confirm(UUID id, UUID command) {
        return auth(post(PATH + "/{id}/confirm", id), manager).param("expectedVersion", "0")
                .param("organizationId", organization.toString()).param("expectedOrganizationVersion", "0")
                .param("scheduleVersionId", schedule.toString()).param("expectedTemplateVersion", "0")
                .param("commandId", command.toString()).param("reason", "합성 확정");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, TnaPrincipal actor) {
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities))).with(csrf());
    }

    private long count(String table) {
        return database.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Long.class, manager.tenantId());
    }

    private TnaPrincipal member(UUID tenant, String role) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES(?,?,?)", tenant,
                "ONB-" + tenant.toString().toUpperCase(Locale.ROOT), "합성 입사 검증");
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES(?,?,?,?,?)",
                tenant, employee, "ADMIN", "합성 담당자", TODAY.minusYears(1));
        database.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES(?,?,?,?,?)",
                tenant, account, employee, account + "@example.test", "synthetic-onboarding-hash");
        for (String grant : Set.of("EMPLOYEE", role)) {
            database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES(?,?,?)", tenant, account, grant);
        }
        return new TnaPrincipal(tenant, account, employee, 1, Set.of("EMPLOYEE", role), Instant.now());
    }
}
