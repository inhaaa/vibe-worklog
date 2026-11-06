package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.AccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AccountAccessWebIT {
    private static final String PASSWORD = "Synthetic access password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired InvitationDelivery delivery;
    private JdbcTemplate admin;
    private String remoteAddress;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var db = DatabaseFixture.start();
        properties.add("spring.datasource.url", db::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", db::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void identities() {
        DatabaseFixture.start().seedIdentity(HASH);
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
        remoteAddress = "198.18.0." + ADDRESS.getAndIncrement();
    }

    @Test
    void administratorCanOpenAccountAccess() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        mvc.perform(get("/admin/employees/{id}/access", DatabaseFixture.EMPLOYEE_A).session(session))
                .andExpect(status().isOk()).andExpect(view().name("org/access"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(ReactPageAssertions.dataPresent("/rolesForm/roles"))
                .andExpect(ReactPageAssertions.page());
    }

    @Test
    void ordinaryEmployeeCannotReadOrMutateAccountAccess() throws Exception {
        var session = login(DatabaseFixture.COMPANY_B, DatabaseFixture.EMAIL);
        mvc.perform(get("/admin/employees/{id}/access", DatabaseFixture.EMPLOYEE_B).session(session))
                .andExpect(status().isForbidden());
        mvc.perform(invitation(session, DatabaseFixture.EMPLOYEE_B, email(), 0))
                .andExpect(status().isForbidden());
        mvc.perform(roles(session, DatabaseFixture.EMPLOYEE_B, 0, "EMPLOYEE", "APPROVER"))
                .andExpect(status().isForbidden());
        mvc.perform(approver(session, DatabaseFixture.EMPLOYEE_B, DatabaseFixture.EMPLOYEE_A, 0))
                .andExpect(status().isForbidden());
    }

    @Test
    void invitationRoleAssignmentAndPublicAcceptanceRequireCsrf() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        for (String path : List.of("/admin/employees/" + employee + "/invitations",
                "/admin/employees/" + employee + "/roles", "/admin/approver-assignments")) {
            mvc.perform(post(path).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/invite/{companyCode}/accept", DatabaseFixture.COMPANY_A))
                .andExpect(status().isForbidden());
        assertEquals(0, accountCount(employee));
    }

    @Test
    void authenticatedTenantOverridesForgedFieldsAndForeignEmployeesStayHidden() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        String email = email();
        mvc.perform(invitation(session, employee, email, 0)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString())
                        .param("tenantId", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isSeeOther());
        assertEquals(DatabaseFixture.TENANT_A, admin.queryForObject(
                "SELECT tenant_id FROM tna.tenant_account WHERE employee_id=?", UUID.class, employee));
        mvc.perform(get("/admin/employees/{id}/access", DatabaseFixture.EMPLOYEE_B).session(session)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isNotFound()).andExpect(view().name("org/problem"))
                .andExpect(content().string(not(containsString("직원 B"))));
        mvc.perform(invitation(session, DatabaseFixture.EMPLOYEE_B, email(), 0))
                .andExpect(status().isNotFound());
        mvc.perform(approver(session, employee, DatabaseFixture.EMPLOYEE_B, 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitationCreatesPendingAccountAndMockMessageWithoutLeakingSecrets() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        String email = email();
        var message = invite(session, employee, email);
        assertTrue(delivery.enabled());
        assertEquals(DatabaseFixture.COMPANY_A, message.companyCode());
        assertEquals(43, message.token().length());
        assertFalse(message.toString().contains(message.token()));
        var row = admin.queryForMap("SELECT active,credential_set FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                DatabaseFixture.TENANT_A, employee);
        assertEquals(false, row.get("active"));
        assertEquals(false, row.get("credential_set"));
        mvc.perform(get("/admin/employees/{id}/access", employee).session(session).param("invited", ""))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(content().string(not(containsString(message.token()))));
        String audit = admin.queryForObject("""
                SELECT string_agg(before_state::text || after_state::text, '') FROM tna.org_audit_event
                WHERE tenant_id=? AND target_id=?
                """, String.class, DatabaseFixture.TENANT_A, employee);
        assertNotNull(audit);
        assertFalse(audit.contains(message.token()));
        String tokenHash = admin.queryForObject("SELECT token_hash FROM tna.account_token WHERE tenant_id=? AND email=?",
                String.class, DatabaseFixture.TENANT_A, email);
        assertFalse(audit.contains(tokenHash));
    }

    @Test
    void publicInvitationFormDoesNotResolveCompanyOrExposeExistingAccount() throws Exception {
        for (String company : List.of(DatabaseFixture.COMPANY_A, "UNKNOWN-COMPANY")) {
            mvc.perform(get("/invite/{companyCode}", company))
                    .andExpect(status().isOk()).andExpect(view().name("invite"))
                    .andExpect(ReactPageAssertions.csrf())
                    .andExpect(content().string(containsString("no-referrer")))
                    .andExpect(content().string(not(containsString(DatabaseFixture.EMAIL))))
                    .andExpect(content().string(not(containsString("직원 A"))));
        }
    }

    @Test
    void acceptedInvitationRedirectsToManualLoginAndEnablesOnlyInvitedAccount() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        String email = email();
        var message = invite(session, employee, email);
        mvc.perform(loginPost(DatabaseFixture.COMPANY_A, email))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(acceptance(message.companyCode(), email, message.token()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?invited"))
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        var accepted = login(DatabaseFixture.COMPANY_A, email);
        mvc.perform(get("/me/profile").session(accepted)).andExpect(status().isOk());
        assertEquals(1, admin.queryForObject("""
                SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=? AND active AND credential_set
                """, Integer.class, DatabaseFixture.TENANT_A, employee));
    }

    @Test
    void invalidAndReusedAcceptanceNeverReflectPasswordOrToken() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        String email = email();
        var message = invite(session, employee, email);
        for (var request : List.of(acceptance(DatabaseFixture.COMPANY_B, email, message.token()),
                acceptance(message.companyCode(), email(), message.token()),
                acceptance(message.companyCode(), email, message.token()).with(httpRequest -> {
                    httpRequest.setParameter("confirmation", "different confirmation");
                    return httpRequest;
                }))) {
            mvc.perform(request).andExpect(status().is(422)).andExpect(view().name("invite"))
                    .andExpect(ReactPageAssertions.dataPresent("/error"))
                    .andExpect(content().string(not(containsString(message.token()))))
                    .andExpect(content().string(not(containsString(PASSWORD))));
        }
        mvc.perform(acceptance(message.companyCode(), email, message.token())).andExpect(status().isSeeOther());
        mvc.perform(acceptance(message.companyCode(), email, message.token()))
                .andExpect(status().is(422)).andExpect(content().string(not(containsString(message.token()))))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void confirmationMismatchLogsFixedFailureWithoutReflectingCredentials(CapturedOutput output) throws Exception {
        String email = "confirmation-only@example.test";
        String token = "x".repeat(43);
        mvc.perform(acceptance("CONFIRMATION-ONLY", email, token).with(request -> {
            request.setParameter("confirmation", "different confirmation");
            return request;
        })).andExpect(status().is(422)).andExpect(view().name("invite"));
        assertTrue(output.getAll().contains("event=invitation_accept outcome=rejected"));
        for (String secret : List.of(email, token, PASSWORD, "different confirmation", "CONFIRMATION-ONLY")) {
            assertFalse(output.getAll().contains(secret));
        }
    }

    @Test
    void employeeRoleIsRequiredAndValidationPreservesChosenOptionalRole() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        Member member = member("역할 검증", false);
        var invalid = mvc.perform(roles(session, member.employee(), 0, "APPROVER"))
                .andExpect(status().is(422)).andExpect(view().name("org/access"))
                .andExpect(ReactPageAssertions.dataPresent("/error")).andReturn();
        var form = (AccountData.RolesForm) invalid.getModelAndView().getModel().get("rolesForm");
        assertEquals(Set.of("APPROVER"), form.roles());
        assertEquals(0, admin.queryForObject("""
                SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role='APPROVER' AND active
                """, Integer.class, DatabaseFixture.TENANT_A, member.account()));
    }

    @Test
    void roleGrantRevokesOldSessionAndFreshLoginUsesNewRole() throws Exception {
        var manager = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        Member member = member("역할 변경", false);
        var old = login(DatabaseFixture.COMPANY_A, member.email());
        mvc.perform(roles(manager, member.employee(), 0, "EMPLOYEE", "APPROVER"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(access(member.employee())));
        mvc.perform(get("/me/profile").session(old)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        var fresh = login(DatabaseFixture.COMPANY_A, member.email());
        mvc.perform(get("/me/profile").session(fresh)).andExpect(status().isOk());
        assertTrue(admin.queryForObject("""
                SELECT active FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role='APPROVER'
                """, Boolean.class, DatabaseFixture.TENANT_A, member.account()));
    }

    @Test
    void pendingEmployeeCanReceiveAndReplaceApproverOnTheSameDay() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        invite(session, employee, email());
        Member first = member("첫 승인자", true);
        Member next = member("다음 승인자", true);
        mvc.perform(approver(session, employee, first.employee(), 1))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(access(employee)));
        mvc.perform(approver(session, employee, next.employee(), 2)).andExpect(status().isSeeOther());
        var page = mvc.perform(get(access(employee)).session(session)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page()).andReturn();
        var current = (AccountData.ApproverAssignment) page.getModelAndView().getModel().get("currentAssignment");
        assertEquals(next.employee(), current.approverEmployeeId());
        assertTrue(current.eligible());
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM tna.approver_assignment WHERE tenant_id=? AND employee_id=?",
                Integer.class, DatabaseFixture.TENANT_A, employee));
    }

    @Test
    void revokedApproverRemainsVisibleAsIneligibleUntilExplicitlyCleared() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        UUID employee = employee(session);
        Member approver = member("회수 승인자", true);
        mvc.perform(approver(session, employee, approver.employee(), 0)).andExpect(status().isSeeOther());
        mvc.perform(roles(session, approver.employee(), 0, "EMPLOYEE")).andExpect(status().isSeeOther());
        mvc.perform(get(access(employee)).session(session)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page())
                .andExpect(selectedOption(approver.employee()));
        mvc.perform(approver(session, employee, null, 1)).andExpect(status().isSeeOther());
        mvc.perform(get(access(employee)).session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("currentAssignment", org.hamcrest.Matchers.nullValue()))
                .andExpect(ReactPageAssertions.page());
        assertEquals(1, admin.queryForObject("""
                SELECT count(*) FROM tna.approver_assignment WHERE tenant_id=? AND employee_id=? AND superseded_at IS NOT NULL
                """, Integer.class, DatabaseFixture.TENANT_A, employee));
    }

    @Test
    void staleAndInvalidFormsPreserveInputAndMalformedIdentifiersStaySafe() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
        Member candidate = member("자기 지정 검증", true);
        mvc.perform(approver(session, candidate.employee(), candidate.employee(), 0))
                .andExpect(status().is(422)).andExpect(view().name("org/access"))
                .andExpect(selectedOption(candidate.employee()));
        UUID employee = employee(session);
        String email = email();
        invite(session, employee, email);
        var stale = mvc.perform(invitation(session, employee, email, 0)).andExpect(status().isConflict())
                .andExpect(view().name("org/access"))
                .andExpect(content().string(containsString(email))).andReturn();
        assertEquals(email, ((AccountData.InvitationForm) stale.getModelAndView().getModel().get("invitationForm")).email());
        mvc.perform(get("/admin/employees/not-a-uuid/access").session(session))
                .andExpect(status().isBadRequest()).andExpect(view().name("org/problem"))
                .andExpect(content().string(not(containsString("java.lang"))));
    }

    private MockHttpSession login(String company, String email) throws Exception {
        return (MockHttpSession) mvc.perform(loginPost(company, email)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder loginPost(String company, String email) {
        return post("/login").with(csrf()).with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                .param("companyCode", company).param("email", email).param("password", PASSWORD);
    }

    private UUID employee(MockHttpSession session) throws Exception {
        var result = mvc.perform(post("/admin/employees").session(session).with(csrf())
                        .param("employeeNo", UUID.randomUUID().toString()).param("displayName", "합성 초대 사원")
                        .param("joinedOn", "2026-01-01").param("active", "true").param("reason", "웹 검증 등록")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther()).andReturn();
        String redirect = result.getResponse().getRedirectedUrl();
        assertNotNull(redirect);
        return UUID.fromString(redirect.substring(redirect.lastIndexOf('/') + 1));
    }

    private InvitationDelivery.Envelope invite(MockHttpSession session, UUID employee, String email) throws Exception {
        mvc.perform(invitation(session, employee, email, 0)).andExpect(status().isSeeOther())
                .andExpect(redirectedUrl(access(employee) + "?invited"));
        return delivery.messages().stream().filter(message -> message.email().equals(email)).findFirst().orElseThrow();
    }

    private MockHttpServletRequestBuilder invitation(MockHttpSession session, UUID employee, String email, long version) {
        return post("/admin/employees/{id}/invitations", employee).session(session).with(csrf())
                .param("email", email).param("expectedVersion", Long.toString(version)).param("reason", "웹 검증 초대")
                .param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder roles(MockHttpSession session, UUID employee, long version, String... roles) {
        return post("/admin/employees/{id}/roles", employee).session(session).with(csrf())
                .param("roles", roles).param("expectedVersion", Long.toString(version)).param("reason", "웹 검증 역할")
                .param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder approver(MockHttpSession session, UUID employee, UUID approver, long version) {
        return post("/admin/approver-assignments").session(session).with(csrf()).param("employeeId", employee.toString())
                .param("approverEmployeeId", approver == null ? "" : approver.toString())
                .param("validFrom", LocalDate.now(ZoneId.of("Asia/Seoul")).toString())
                .param("expectedVersion", Long.toString(version)).param("reason", "웹 검증 승인자")
                .param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder acceptance(String company, String email, String token) {
        return post("/invite/{companyCode}/accept", company).with(csrf())
                .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                .param("email", email).param("token", token).param("password", PASSWORD).param("confirmation", PASSWORD);
    }

    private Member member(String name, boolean approver) {
        UUID employee = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String email = email();
        admin.update("""
                INSERT INTO tna.employee (tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2026-01-01')
                """, DatabaseFixture.TENANT_A, employee, UUID.randomUUID().toString(), name);
        admin.update("""
                INSERT INTO tna.tenant_account (tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)
                """, DatabaseFixture.TENANT_A, account, employee, email, HASH);
        for (String role : approver ? List.of("EMPLOYEE", "APPROVER") : List.of("EMPLOYEE")) {
            admin.update("INSERT INTO tna.role_grant (tenant_id,account_id,role) VALUES (?,?,?)",
                    DatabaseFixture.TENANT_A, account, role);
        }
        return new Member(employee, account, email);
    }

    private int accountCount(UUID employee) {
        return admin.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                Integer.class, DatabaseFixture.TENANT_A, employee);
    }

    private ResultMatcher selectedOption(UUID value) {
        return ReactPageAssertions.data("/approverForm/approverEmployeeId", value);
    }

    private String email() { return UUID.randomUUID() + "@example.test"; }
    private String access(UUID employee) { return "/admin/employees/" + employee + "/access"; }
    private record Member(UUID employee, UUID account, String email) { }
}
