package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("public-demo")
class RoleManagementPublicIT {
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID SHARED = LocalDemoLogin.APPROVER_EMPLOYEE_ID;
    private static final String HASH = PasswordPolicy.passwordEncoder().encode("Synthetic role test password 43!");
    private static final String ROLE = "COMPANY_REPRESENTATIVE";
    private static final String BULK = "/admin/access/roles/" + ROLE + "/members/bulk";
    private static final String CANDIDATES = "/admin/access/roles/" + ROLE + "/candidates";

    @Autowired MockMvc mvc;

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

    @Test
    void sharedAccountIsVisibleAsProtectedButIneligibleAndBlocksMixedWrites() throws Exception {
        var db = demoDatabase();
        var admin = identity(db, UUID.randomUUID(), "권한 공개 검증 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        identity(db, SHARED, "권한 공개 검증 공유 사원", Set.of("EMPLOYEE", "APPROVER"));
        var available = identity(db, UUID.randomUUID(), "권한 공개 검증 일반 사원", Set.of("EMPLOYEE"));

        mvc.perform(get(CANDIDATES).with(as(admin)).param("q", "권한 공개 검증 공유 사원"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        mvc.perform(get(CANDIDATES).with(as(admin)).param("q", "권한 공개 검증 일반 사원"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].employeeId").value(available.employeeId().toString()));
        mvc.perform(get("/admin/access").with(as(admin)).param("role", "APPROVER")
                        .param("q", "권한 공개 검증 공유 사원").accept("application/vnd.worklog.page+json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleDirectory.members[0].editable").value(false))
                .andExpect(jsonPath("$.data.roleDirectory.members[0].restriction").value("공유 데모 보호"));

        UUID commandId = UUID.randomUUID();
        mvc.perform(post(BULK).with(as(admin)).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commandId":"%s","reason":"공유 보호와 일괄 원자성 확인","rows":[
                                  {"clientRowId":"%s","employeeId":"%s","expectedVersion":0,"operation":"GRANT"},
                                  {"clientRowId":"%s","employeeId":"%s","expectedVersion":0,"operation":"GRANT"}]}
                                """.formatted(commandId, UUID.randomUUID(), available.employeeId(), UUID.randomUUID(), SHARED)))
                .andExpect(status().isForbidden());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role=?",
                Integer.class, TENANT, available.accountId(), ROLE));
        assertEquals(1L, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, TENANT, available.accountId()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Integer.class, TENANT, commandId));
    }

    @Test
    void individualFormAndMembershipSheetCannotCommitTheSameStaleVersion() throws Exception {
        var db = demoDatabase();
        var admin = identity(db, UUID.randomUUID(), "권한 경합 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        var person = identity(db, UUID.randomUUID(), "권한 경합 사원", Set.of("EMPLOYEE"));
        String body = """
                {"commandId":"%s","reason":"일괄 권한 부여","rows":[
                  {"clientRowId":"%s","employeeId":"%s","expectedVersion":0,"operation":"GRANT"}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), person.employeeId());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var single = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/admin/employees/{id}/roles", person.employeeId()).with(as(admin)).with(csrf())
                                .param("roles", "EMPLOYEE", "APPROVER").param("expectedVersion", "0")
                                .param("reason", "사원 상세 권한 부여").param("commandId", UUID.randomUUID().toString()))
                        .andReturn().getResponse().getStatus();
            });
            var bulk = pool.submit(() -> {
                start.await();
                return mvc.perform(post(BULK).with(as(admin)).with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int singleStatus = single.get(15, TimeUnit.SECONDS);
            int bulkStatus = bulk.get(15, TimeUnit.SECONDS);
            assertEquals(singleStatus == 303 ? 409 : 200, bulkStatus);
            assertEquals(bulkStatus == 200 ? 409 : 303, singleStatus);
            assertEquals(List.of(singleStatus == 303 ? "APPROVER" : ROLE, "EMPLOYEE"),
                    db.queryForList("SELECT role FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active ORDER BY role",
                            String.class, TENANT, person.accountId()));
        }
        assertEquals(1L, db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",
                Long.class, TENANT, person.employeeId()));
        assertEquals(2L, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, TENANT, person.accountId()));
    }

    private static JdbcTemplate demoDatabase() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        db.update("""
                INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 권한 데모')
                ON CONFLICT (id) DO NOTHING
                """, TENANT, LocalDemoLogin.COMPANY_CODE);
        return db;
    }

    private static TnaPrincipal identity(JdbcTemplate db, UUID employee, String name, Set<String> roles) {
        UUID account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,'2025-01-01')",
                TENANT, employee, "R-" + employee.toString().substring(0, 12), name);
        db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version)
                VALUES (?,?,?,?,?,true,1)
                """, TENANT, account, employee, account + "@example.test", HASH);
        for (String role : roles) {
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", TENANT, account, role);
        }
        return new TnaPrincipal(TENANT, account, employee, 1, roles, Instant.now());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
