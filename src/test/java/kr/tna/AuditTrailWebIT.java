package kr.tna;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailWebIT {
    private static final String PASSWORD = "Synthetic audit web credential 43!";
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant, manager, employeeId;
    private String company;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void ownTenant() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = "AUDIT-WEB-" + tenant.toString().toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 감사 웹 회사')", tenant, company);
        manager = member("manager", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        UUID staffAccount = member("employee", Set.of("EMPLOYEE"));
        employeeId = db.queryForObject("SELECT employee_id FROM tna.tenant_account WHERE tenant_id=? AND id=?", UUID.class, tenant, staffAccount);
    }

    @Test
    void listLinksToDetailsAndProvidesBothDirectionsAcrossOlderPages() throws Exception {
        for (int i = 1; i <= 110; i++) event(new UUID(0, i), "합성 감사 사유");
        var session = login("manager");
        mvc.perform(get("/admin/audit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/audit/" + new UUID(0, 110))))
                .andExpect(content().string(containsString("before=" + new UUID(0, 61))))
                .andExpect(content().string(containsString("조회 시점")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/admin/audit").session(session).param("before", new UUID(0, 61).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("after=" + new UUID(0, 60))))
                .andExpect(content().string(containsString("before=" + new UUID(0, 11))))
                .andExpect(content().string(not(containsString("/admin/audit/" + new UUID(0, 110)))));
    }

    @Test
    void detailShowsCommandTraceAndSeoulTimeWhileEscapingTheAllowedReason() throws Exception {
        UUID id = UUID.randomUUID();
        var references = event(id, "<script>HARMLESS_AUDIT_REASON</script>");
        mvc.perform(get("/admin/audit/{id}", id).session(login("manager")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("감사 상세")))
                .andExpect(content().string(containsString(references[0].toString())))
                .andExpect(content().string(containsString(references[1].toString())))
                .andExpect(content().string(containsString("2099-01-01 09:00:00.123")))
                .andExpect(content().string(containsString("Asia/Seoul")))
                .andExpect(content().string(containsString("&lt;script&gt;HARMLESS_AUDIT_REASON&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>HARMLESS_AUDIT_REASON</script>"))))
                .andExpect(content().string(not(containsString("PRIVATE_AUDIT_EMAIL"))))
                .andExpect(content().string(not(containsString("PRIVATE_AUDIT_TOKEN"))))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='AUDIT_DETAIL_VIEW'", Integer.class, tenant));
    }

    @Test
    void employeesAndUnauthenticatedCallersCannotReadEitherAuditPath() throws Exception {
        UUID id = UUID.randomUUID();event(id, "권한 검증");
        var staff = login("employee");
        for (String path : new String[]{"/admin/audit", "/admin/audit/" + id}) {
            mvc.perform(get(path).session(staff)).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
    }

    @Test
    void malformedCursorsIdsAndTwoDirectionsAreRejected() throws Exception {
        UUID id = UUID.randomUUID();event(id, "커서 검증");
        var session = login("manager");
        mvc.perform(get("/admin/audit").session(session).param("before", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit/not-a-uuid").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit").session(session).param("before", id.toString()).param("after", id.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestTenantParametersCannotRedirectTheAuditLookup() throws Exception {
        UUID own = UUID.randomUUID();event(own, "자기 회사 기록");
        UUID foreign = UUID.randomUUID();
        var session = login("manager");
        mvc.perform(get("/admin/audit/{id}", own).session(session).param("tenant_id", foreign.toString()).header("X-Tenant-Id", foreign.toString()))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/audit/{id}", foreign).session(session).param("tenant_id", foreign.toString()).header("X-Tenant-Id", foreign.toString()))
                .andExpect(status().isNotFound());
    }

    private UUID[] event(UUID id, String reason) {
        UUID command = UUID.randomUUID(), trace = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,
                    input_hash,reason,before_state,after_state,request_trace_id,occurred_at)
                VALUES (?,?,?,?,'SYNTHETIC_AUDIT','EMPLOYEE',?,?,?,
                    '{"version":1,"email":"PRIVATE_AUDIT_EMAIL"}'::jsonb,
                    '{"version":2,"token":"PRIVATE_AUDIT_TOKEN"}'::jsonb,?,?)
                """, tenant, id, command, manager, employeeId, "0".repeat(64), reason, trace,
                Timestamp.from(Instant.parse("2099-01-01T00:00:00.123Z")));
        return new UUID[]{command, trace};
    }

    private UUID member(String name, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, name, "합성 감사 " + name, LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employeeId, name + "@example.test", PasswordPolicy.passwordEncoder().encode(PASSWORD));
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return account;
    }

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("companyCode", company)
                .param("email", name + "@example.test").param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/today")).andReturn().getRequest().getSession(false);
    }
}
