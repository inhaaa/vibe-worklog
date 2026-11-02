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

    @AfterEach
    void removeOwnedPersonnelFixture() {
        var db = demoDb();
        for (String table : List.of("employee_employment_status", "employee_personnel")) {
            db.update("DELETE FROM tna." + table + " WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        }
    }

    @Test
    void publicLandingIdentifiesTheSharedSyntheticEnvironment() throws Exception {
        publicMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/publicDemo", true));
    }

    @Test
    void encodedWhitespaceCannotChangeTheSharedAccountRoles() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.251"); return request; }))
                .andExpect(redirectedUrl("/me/profile"))
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
                .andExpect(redirectedUrl("/me/profile"))
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
                .andExpect(redirectedUrl("/me/profile"))
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
                .andExpect(redirectedUrl("/me/profile")).andReturn().getRequest().getSession(false);
    }

    private JdbcTemplate demoDb() { return new JdbcTemplate(DatabaseFixture.start().adminDataSource()); }

    private List<List<String>> demoState() {
        List<List<String>> state = new ArrayList<>();
        for (String table : List.of("employee", "employee_personnel", "employee_employment_status", "org_audit_event")) {
            state.add(demoDb().queryForList("SELECT row_to_json(record)::text FROM tna." + table
                    + " record WHERE tenant_id=? ORDER BY row_to_json(record)::text", String.class, LocalDemoLogin.TENANT_ID));
        }
        return state;
    }
}
