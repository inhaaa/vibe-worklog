package kr.tna;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeePersonnelData.Form;
import kr.tna.org.EmployeePersonnelService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reuses the real DB/hash/current-role/CSRF regression against the external demo profile. */
@ActiveProfiles(value = "public-demo", inheritProfiles = false)
class PublicDemoSecurityIT extends LocalDemoWebIT {
    @Autowired MockMvc publicMvc;
    @Autowired EmployeePersonnelService personnel;
    @Autowired kr.tna.org.EmployeePersonalDetailsService personal;

    @AfterEach
    void removeOwnedPersonnelFixture() {
        var db = demoDb();
        for (String table : List.of("employee_personal_entry", "employee_personal_details", "employee_employment_status", "employee_personnel")) {
            db.update("DELETE FROM tna." + table + " WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        }
    }

    @Test
    void preparedFiveDemoNumbersUseTheirOwnStoredPrincipalAndActualRoles() throws Exception {
        var db = demoDb();
        var numbers = java.util.Map.of("admin", "99999", "approver", "26001", "employee", "26002",
                "representative", "26003", "dual", "25001");
        for (var persona : LocalDemoLogin.personas()) {
            String number = numbers.get(persona.login());
            if (db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=? AND id=?", Integer.class,
                    LocalDemoLogin.TENANT_ID, persona.employeeId()) == 0) {
                db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                        LocalDemoLogin.TENANT_ID, persona.employeeId(), number, "합성 " + persona.login(),
                        LocalDate.of(persona.login().equals("dual") ? 2025 : 2026, 1, 1));
                db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                        LocalDemoLogin.TENANT_ID, persona.accountId(), persona.employeeId(), persona.email(),
                        kr.tna.common.auth.PasswordPolicy.passwordEncoder().encode(persona.password()));
                for (String role : persona.roles()) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                        LocalDemoLogin.TENANT_ID, persona.accountId(), role);
            }
            var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                            .param("companyCode", "UNTRUSTED-COMPANY").param("email", number).param("password", persona.password())
                            .with(request -> { request.setRemoteAddr("198.51.100.244"); return request; }))
                    .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
            assertNotNull(session);
            var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            var principal = (TnaPrincipal) context.getAuthentication().getPrincipal();
            assertTrue(LocalDemoLogin.matches(persona, principal));
            publicMvc.perform(get("/me/profile").session(session)).andExpect(status().isOk());
            publicMvc.perform(get("/admin/access").session(session))
                    .andExpect(status().is(persona.roles().contains("ATTENDANCE_ADMIN") ? 200 : 403));
            publicMvc.perform(get("/approvals").session(session))
                    .andExpect(status().is(persona.roles().contains("APPROVER") ? 200 : 403));
        }
        publicMvc.perform(get("/demo")).andExpect(ReactPageAssertions.data("/demoAccounts/4/employeeNo", "25001"));
    }

    @Test
    void everySharedEmployeeRejectsTheNewPersonalDetailAndEntryHttpPaths() throws Exception {
        var session = publicLogin("198.51.100.242");
        var before = demoState();
        List<Executable> checks = new ArrayList<>();
        for (UUID employee : LocalDemoLogin.SHARED_EMPLOYEE_IDS) {
            String id = employee.toString();
            for (String pathId : List.of(id, id.toUpperCase(Locale.ROOT), "%20" + id + "%20")) {
                for (String query : List.of("", "?demo=1")) {
                    for (String suffix : List.of("/personal-details", "/personal-entries")) {
                        var path = java.net.URI.create("/admin/employees/" + pathId + suffix + query);
                        checks.add(() -> publicMvc.perform(post(path).session(session).with(csrf())
                                        .param("englishName", "Synthetic Protected Person").param("kind", "FAMILY")
                                        .param("title", "공유 이력 차단").param("effectiveFrom", "2026-01-01")
                                        .param("expectedVersion", "0").param("reason", "공유 상세 변경 차단 검증")
                                        .param("commandId", UUID.randomUUID().toString()))
                                .andExpect(status().isForbidden()));
                    }
                }
            }
        }
        checks.add(() -> assertEquals(before, demoState()));
        assertAll(checks);
    }

    @Test
    void directPersonalServicesRejectAllFiveSharedEmployeesBeforeAnyWrites() throws Exception {
        var session = publicLogin("198.51.100.243");
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var actor = (TnaPrincipal) context.getAuthentication().getPrincipal();
        var before = demoState();
        for (UUID employee : LocalDemoLogin.SHARED_EMPLOYEE_IDS) {
            var form = new kr.tna.org.EmployeePersonalDetailsData.Form("UNKNOWN", null, null, "Synthetic Protected Person",
                    "UNKNOWN", null, "UNKNOWN", "UNKNOWN", "UNKNOWN", null, null, null, null, null, null,
                    0L, "공유 상세 서비스 차단", UUID.randomUUID());
            var entry = new kr.tna.org.EmployeePersonalDetailsData.EntryForm("FAMILY", "공유 이력 차단", null,
                    LocalDate.of(2026, 1, 1), null, 0L, "공유 이력 서비스 차단", UUID.randomUUID());
            assertEquals(403, assertThrows(OrgException.class, () -> personal.update(actor, employee, form)).status());
            assertEquals(403, assertThrows(OrgException.class, () -> personal.append(actor, employee, entry)).status());
        }
        assertEquals(before, demoState());
    }

    @Test
    void publicLandingIdentifiesTheSharedSyntheticEnvironment() throws Exception {
        publicMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/publicDemo", true));
    }

    @Test
    void publicOnboardingIsReadOnlyAndPersonnelExportIsForbidden() throws Exception {
        var session = publicLogin("198.51.100.243");
        String onboarding = "/admin/employees/onboarding";
        publicMvc.perform(get(onboarding).session(session))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/onboarding/publicDemo", true));
        String draft = onboarding + "/" + UUID.randomUUID();
        for (String path : List.of(onboarding, draft + "/changes", draft + "/confirm",
                draft + "/withdraw", "/admin/employees/exports")) {
            publicMvc.perform(post(path).session(session).with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void sharedApproverSingleEncodedAndBulkPathsCannotChangeItsIdentity() throws Exception {
        var session = publicLogin("198.51.100.246");
        var before = demoState();
        String id = LocalDemoLogin.APPROVER_EMPLOYEE_ID.toString();
        for (String pathId : List.of(id, id.toUpperCase(Locale.ROOT), "%20" + id + "%20")) {
            for (String query : List.of("", "?demo=1")) {
                var roles = java.net.URI.create("/admin/employees/" + pathId + "/roles" + query);
                publicMvc.perform(post(roles).session(session).with(csrf())
                                .param("roles", "EMPLOYEE").param("expectedVersion", "1")
                                .param("reason", "공유 승인자 역할 차단 검증").param("commandId", UUID.randomUUID().toString()))
                        .andExpect(status().isForbidden());
                var personnelPath = java.net.URI.create("/admin/employees/" + pathId + "/personnel" + query);
                publicMvc.perform(post(personnelPath).session(session).with(csrf())
                                .param("birthDate", "1990-01-01").param("gradeName", "공유 변경 차단")
                                .param("employmentStatus", "ON_LEAVE")
                                .param("effectiveFrom", LocalDate.now(ZoneId.of("Asia/Seoul")).toString())
                                .param("expectedVersion", "0").param("reason", "공유 승인자 인사 차단 검증")
                                .param("commandId", UUID.randomUUID().toString()).param("photoAction", "KEEP"))
                        .andExpect(status().isForbidden());
            }
        }
        for (String suffix : List.of("/changes", "/invitations")) {
            publicMvc.perform(post("/admin/employees/" + id + suffix).session(session).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        String rows = """
                [{"clientRowId":"%s","id":"%s","expectedVersion":0,"operation":"DEACTIVATE",
                  "employeeNo":"DEMO-001","displayName":"공유 승인자 변경 차단",
                  "joinedOn":"2026-01-01","leftOn":null,"active":false}]
                """.formatted(UUID.randomUUID(), id.toUpperCase(Locale.ROOT));
        publicMvc.perform(post("/admin/employees/bulk").session(session).with(csrf())
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "공유 승인자 일괄 차단 검증")
                        .param("rows", rows)).andExpect(status().isForbidden());
        assertEquals(before, demoState());
    }

    @Test
    void encodedWhitespaceCannotChangeTheSharedAccountRoles() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.251"); return request; }))
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
        var path = java.net.URI.create("/admin/employees/%20" + LocalDemoLogin.EMPLOYEE_ID + "%20/roles");
        publicMvc.perform(post(path).session(session).with(csrf())
                        .param("roles", "EMPLOYEE", "ATTENDANCE_ADMIN", "APPROVER")
                        .param("expectedVersion", "1").param("reason", "공개 합성 데모 계정 보호 검증")
                        .param("commandId", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        publicMvc.perform(get("/me/attendance").session(session)).andExpect(status().isOk());
    }

    @Test
    void visitorCannotDisableTheSharedDemoIdentity() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.250"); return request; }))
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
        String employee = "/admin/employees/" + LocalDemoLogin.EMPLOYEE_ID;
        for (String suffix : new String[]{"/changes", "/roles", "/invitations", "/personnel"}) {
            publicMvc.perform(post(employee + suffix).session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            publicMvc.perform(post("/admin/employees/" + LocalDemoLogin.EMPLOYEE_ID.toString()
                            .toUpperCase(java.util.Locale.ROOT) + suffix + "?demo=1")
                            .session(session).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        publicMvc.perform(get("/me/attendance").session(session)).andExpect(status().isOk());
        publicMvc.perform(get("/operator/tenants").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void employeeBulkCannotBypassSharedIdentityProtectionWithAnUppercaseUuid() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.249"); return request; }))
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
        String rows = """
                [{"clientRowId":"%s","id":"%s","expectedVersion":0,"operation":"UPDATE",
                  "employeeNo":"LOCAL-DEMO","displayName":"덮어쓰면 안 되는 공유 사원",
                  "joinedOn":"2026-01-01","leftOn":null,"active":true}]
                """.formatted(java.util.UUID.randomUUID(),
                        LocalDemoLogin.EMPLOYEE_ID.toString().toUpperCase(java.util.Locale.ROOT));
        publicMvc.perform(post("/admin/employees/bulk").session(session).with(csrf())
                        .param("commandId", java.util.UUID.randomUUID().toString())
                        .param("reason", "공개 합성 데모 일괄 변경 차단 검증").param("rows", rows))
                .andExpect(status().isForbidden());
        publicMvc.perform(get("/me/attendance").session(session)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/workspaceEmployee/employeeName", "로컬 데모 담당자"));
    }

    @Test
    void personnelPathsCannotChangeSharedEmployeeOrPrivateState() throws Exception {
        var session = publicLogin("198.51.100.248");
        var before = demoState();
        List<Executable> checks = new ArrayList<>();
        String id = LocalDemoLogin.EMPLOYEE_ID.toString();
        for (String pathId : List.of(id, id.toUpperCase(Locale.ROOT), "%20" + id + "%20")) {
            for (String query : List.of("", "?demo=1")) {
                var path = java.net.URI.create("/admin/employees/" + pathId + "/personnel" + query);
                checks.add(() -> publicMvc.perform(post(path).session(session).with(csrf())
                                .param("birthDate", "1990-01-01").param("gradeName", "공유 변경 차단")
                                .param("employmentStatus", "ON_LEAVE")
                                .param("effectiveFrom", LocalDate.now(ZoneId.of("Asia/Seoul")).toString())
                                .param("expectedVersion", "0").param("reason", "공개 인사 보호 검증")
                                .param("commandId", UUID.randomUUID().toString()).param("photoAction", "KEEP"))
                        .andExpect(status().isForbidden()));
            }
        }
        checks.add(() -> assertEquals(before, demoState(), "사원·인사·상태 이력·감사는 그대로여야 한다"));
        assertAll(checks);
    }

    @Test
    void directPersonnelServiceCannotBypassSharedIdentityProtection() throws Exception {
        var session = publicLogin("198.51.100.247");
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var actor = (TnaPrincipal) context.getAuthentication().getPrincipal();
        var before = demoState();
        var form = new Form(LocalDate.of(1990, 1, 1), "공유 변경 차단", null, null,
                "SUSPENDED", LocalDate.now(ZoneId.of("Asia/Seoul")), 0L,
                "공개 서비스 직접 호출 보호 검증", UUID.randomUUID(), "KEEP", null);
        assertAll(
                () -> assertEquals(403, assertThrows(OrgException.class,
                        () -> personnel.update(actor, LocalDemoLogin.EMPLOYEE_ID, form)).status()),
                () -> assertEquals(before, demoState(), "서비스 호출도 사원·인사·상태 이력·감사를 바꾸지 않는다"));
    }

    private MockHttpSession publicLogin(String remoteAddress) throws Exception {
        return (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }

    private JdbcTemplate demoDb() { return new JdbcTemplate(DatabaseFixture.start().adminDataSource()); }

    private List<List<String>> demoState() {
        List<List<String>> state = new ArrayList<>();
        for (String table : List.of("employee", "employee_personnel", "employee_employment_status", "employee_personal_details",
                "employee_personal_entry", "tenant_account", "role_grant", "account_token", "org_audit_event")) {
            state.add(demoDb().queryForList("SELECT row_to_json(record)::text FROM tna." + table
                    + " record WHERE tenant_id=? ORDER BY row_to_json(record)::text", String.class, LocalDemoLogin.TENANT_ID));
        }
        return state;
    }
}
