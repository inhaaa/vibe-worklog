package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RoleMembershipWebIT {
    private static final String PASSWORD = "Synthetic membership password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final UUID TENANT = DatabaseFixture.TENANT_A;
    private static final UUID ADMIN_EMPLOYEE = DatabaseFixture.EMPLOYEE_A;
    private static final UUID ADMIN_ACCOUNT = DatabaseFixture.ACCOUNT_A;
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private MockHttpSession admin;

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
    void seed() throws Exception {
        DatabaseFixture.start().seedIdentity(HASH);
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        grant(ADMIN_ACCOUNT, "ATTENDANCE_ADMIN");
        admin = login(DatabaseFixture.COMPANY_A, DatabaseFixture.EMAIL);
    }

    @Test
    void changesOnlySelectedRoleRevokesTargetSessionAndReplaysOriginalResult() throws Exception {
        var target = employee(true);
        grant(target.account(), "COMPANY_REPRESENTATIVE");
        var oldSession = login(DatabaseFixture.COMPANY_A, target.email());
        UUID command = UUID.randomUUID();
        String row = row(target.id(), 0, "GRANT");
        mvc.perform(bulk("APPROVER", body(command, row)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affectedSelf").value(false))
                .andExpect(jsonPath("$.rows[0].employeeId").value(target.id().toString()))
                .andExpect(jsonPath("$.rows[0].version").value(1));
        mvc.perform(bulk("APPROVER", body(command, row)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].version").value(1));
        mvc.perform(bulk("APPROVER", body(command, row(target.id(), 1, "REVOKE"))).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(get("/me/home").session(oldSession)).andExpect(status().is3xxRedirection());
        assertEquals(2L, authVersion(target.account()));
        assertEquals(1L, authVersion(ADMIN_ACCOUNT));
        assertEquals(3L, db.queryForObject("""
                SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active
                AND role IN ('EMPLOYEE','APPROVER','COMPANY_REPRESENTATIVE')
                """, Long.class, TENANT, target.account()));
        assertEquals(1L, db.queryForObject("""
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND actor_account_id=? AND target_id=?
                AND reason='합성 권한 검증' AND before_state->'roles' @> '["COMPANY_REPRESENTATIVE"]'
                AND NOT (before_state->'roles' @> '["APPROVER"]') AND after_state->'roles' @> '["APPROVER"]'
                """, Long.class, TENANT, ADMIN_ACCOUNT, target.id()));
    }

    @Test
    void rejectsWholeBatchForIneligibleMemberAndReportsCurrentVersionForConflict() throws Exception {
        var valid = employee(true);
        var inactive = employee(false);
        mvc.perform(bulk("APPROVER", body(UUID.randomUUID(), row(valid.id(), 0, "GRANT"),
                        row(inactive.id(), 0, "GRANT"))).with(csrf()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].clientRowId").exists());
        assertEquals(0L, activeGrant(valid.account(), "APPROVER"));
        assertEquals(1L, authVersion(valid.account()));
        assertEquals(0L, db.queryForObject("""
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND target_id=?
                """, Long.class, TENANT, valid.id()));
        mvc.perform(bulk("APPROVER", body(UUID.randomUUID(), row(valid.id(), 12, "GRANT"))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.latest[0].version").value(0))
                .andExpect(jsonPath("$.latest[0].granted").value(false));
    }

    @Test
    void rejectsLastPermanentAdministratorButAllowsReplacementInOneBatch() throws Exception {
        long version = db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",
                Long.class, TENANT, ADMIN_EMPLOYEE);
        mvc.perform(bulk("ATTENDANCE_ADMIN", body(UUID.randomUUID(), row(ADMIN_EMPLOYEE, version, "REVOKE"))).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertEquals(1L, activeGrant(ADMIN_ACCOUNT, "ATTENDANCE_ADMIN"));
        var retiring = employee(true);
        db.update("UPDATE tna.employee SET left_on=DATE '2030-01-01' WHERE tenant_id=? AND id=?", TENANT, retiring.id());
        mvc.perform(bulk("ATTENDANCE_ADMIN", body(UUID.randomUUID(), row(ADMIN_EMPLOYEE, version, "REVOKE"),
                        row(retiring.id(), 0, "GRANT"))).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertEquals(1L, authVersion(retiring.account()));
        assertEquals(0L, activeGrant(retiring.account(), "ATTENDANCE_ADMIN"));
        var successor = employee(true);
        mvc.perform(bulk("ATTENDANCE_ADMIN", body(UUID.randomUUID(), row(ADMIN_EMPLOYEE, version, "REVOKE"),
                        row(successor.id(), 0, "GRANT"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affectedSelf").value(true));
        assertEquals(0L, activeGrant(ADMIN_ACCOUNT, "ATTENDANCE_ADMIN"));
        assertEquals(1L, activeGrant(successor.account(), "ATTENDANCE_ADMIN"));
    }

    @Test
    void usesCsrfAndCurrentAdministratorAndConcealsForeignEmployee() throws Exception {
        String request = body(UUID.randomUUID(), row(DatabaseFixture.EMPLOYEE_B, 0, "GRANT"));
        mvc.perform(bulk("APPROVER", request)).andExpect(status().isForbidden());
        mvc.perform(bulk("APPROVER", request).with(csrf())).andExpect(status().isNotFound());
        var ordinary = login(DatabaseFixture.COMPANY_B, DatabaseFixture.EMAIL);
        mvc.perform(bulk("APPROVER", request).session(ordinary).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(bulk("EMPLOYEE", body(UUID.randomUUID(), row(ADMIN_EMPLOYEE, 0, "REVOKE"))).with(csrf()))
                .andExpect(status().isUnprocessableContent());
    }

    private MockHttpServletRequestBuilder bulk(String role, String body) {
        return post("/admin/access/roles/{role}/members/bulk", role).session(admin)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(UUID command, String... rows) {
        return """
                {"commandId":"%s","reason":" 합성 권한 검증 ","rows":[%s]}
                """.formatted(command, String.join(",", rows));
    }

    private static String row(UUID id, long version, String operation) {
        return """
                {"clientRowId":"%s","employeeId":"%s","expectedVersion":%d,"operation":"%s"}
                """.formatted(UUID.randomUUID(), id, version, operation);
    }

    private MockHttpSession login(String company, String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).with(request -> {
            request.setRemoteAddr("198.18.2." + ADDRESS.getAndIncrement());
            return request;
        }).param("companyCode", company).param("email", email).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
    }

    private Target employee(boolean active) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        String email = id + "@example.test";
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,active)
                VALUES (?,?,?,'합성 권한 사원',DATE '2026-01-01',true)
                """, TENANT, id, id.toString());
        db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version)
                VALUES (?,?,?,?,?,?,1)
                """, TENANT, account, id, email, HASH, active);
        grant(account, "EMPLOYEE");
        return new Target(id, account, email);
    }

    private void grant(UUID account, String role) {
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role,active) VALUES (?,?,?,true)", TENANT, account, role);
    }

    private long activeGrant(UUID account, String role) {
        return db.queryForObject("SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role=? AND active",
                Long.class, TENANT, account, role);
    }

    private long authVersion(UUID account) {
        return db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, TENANT, account);
    }

    private record Target(UUID id, UUID account, String email) { }
}
