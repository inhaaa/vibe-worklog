package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AccountDirectoryWebIT {
    @Autowired MockMvc mvc;
    JdbcTemplate db;
    UUID tenant;
    kr.tna.common.auth.TnaPrincipal manager;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 사용자 관리')",
                tenant, "ACCT-" + tenant);
        manager = member("ADMIN", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    UUID employee(String number, String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2020-01-01')",
                tenant, id, number, name);
        return id;
    }

    void account(UUID employee, boolean active, String expires, int failures) {
        db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,password_hash,activation_expires_at,activation_failures)
                VALUES (?,?,?,?,?,CAST(? AS timestamptz),?)
                """, tenant, UUID.randomUUID(), employee, active, active ? "{noop}x-synthetic-hash" : null, expires, failures);
    }

    void birth(UUID employee) {
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-01-01',1,?)",
                tenant, employee, manager.accountId());
    }

    @Test void listsEveryStateTheSameWayAccountStatusDoes() throws Exception {
        employee("A1", "계정없음");
        UUID active = employee("A2", "사용중"); account(active, true, null, 0);
        UUID noBirth = employee("A3", "생년월일없음"); account(noBirth, false, "2999-01-01", 0);
        UUID locked = employee("A4", "잠김"); account(locked, false, "2999-01-01", 5); birth(locked);
        UUID expired = employee("A5", "만료"); account(expired, false, "2000-01-01", 0); birth(expired);
        UUID pending = employee("A6", "대기"); account(pending, false, "2999-01-01", 1); birth(pending);
        UUID previous = employee("A7", "이전초대"); account(previous, false, null, 0);
        String[] expected = {"NONE", "ACTIVE", "BIRTH_DATE_REQUIRED", "LOCKED", "EXPIRED", "PENDING", "PREVIOUS_INVITATION"};
        var request = mvc.perform(authenticated(get("/admin/accounts").param("q", "A"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page());
        for (int i = 0; i < expected.length; i++) {
            request.andExpect(ReactPageAssertions.data("/accountDirectory/items/" + i + "/state", expected[i]))
                    .andExpect(ReactPageAssertions.data("/accountDirectory/items/" + i + "/label",
                            kr.tna.org.AccountStatus.valueOf(expected[i]).label()));
        }
        String page = request.andReturn().getResponse().getContentAsString();
        assertFalse(page.contains("1990-01-01"));
        mvc.perform(authenticated(get("/admin/accounts").param("status", "LOCKED"), manager))
                .andExpect(ReactPageAssertions.data("/accountDirectory/items/0/employeeId", locked.toString()))
                .andExpect(ReactPageAssertions.data("/accountDirectory/hasNext", false));
        mvc.perform(authenticated(get("/admin/accounts").param("status", "WRONG"), manager)).andExpect(status().isBadRequest());
    }

    @Test void filterReturnsOnlyTheMatchingState() throws Exception {
        employee("B1", "없음");
        UUID locked = employee("B2", "잠김"); account(locked, false, "2999-01-01", 5); birth(locked);
        mvc.perform(authenticated(get("/admin/accounts").param("status", "LOCKED"), manager))
                .andExpect(ReactPageAssertions.data("/accountDirectory/items/0/employeeNo", "B2"));
    }

    @Test void employeeOnlyAccountCannotOpenTheDirectory() throws Exception {
        var plain = member("PLAIN", java.util.Set.of("EMPLOYEE"));
        mvc.perform(authenticated(get("/admin/accounts"), plain)).andExpect(status().isForbidden());
    }

    String bulk(String action, String reason, UUID... employees) throws Exception {
        String ids = java.util.Arrays.stream(employees).map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(authenticated(post("/admin/accounts/" + action), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"" + reason + "\",\"employeeIds\":[" + ids + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test void createsAccountsForEveryoneOrNobody() throws Exception {
        UUID first = employee("B1", "첫째");
        UUID second = employee("B2", "둘째");
        UUID has = employee("B3", "이미있음"); account(has, true, null, 0);
        String rejected = bulk("create", "개통", first, has);
        assertTrue(rejected.contains("이미 계정이 있습니다."), rejected);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Integer.class, tenant, first));
        assertTrue(bulk("create", "개통", first, second).contains("\"count\":2"));
        assertEquals(2, db.queryForObject("""
                SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id IN (?,?) AND activation_expires_at IS NOT NULL
                """, Integer.class, tenant, first, second));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_BULK_PROVISIONED'", Integer.class, tenant));
    }

    @Test void resetsAccountsButNeverTheActorOrSomeoneWithoutAnAccount() throws Exception {
        UUID worker = employee("C1", "직원"); account(worker, true, null, 0);
        UUID none = employee("C2", "계정없음");
        assertTrue(bulk("reset", "초기화", worker, none).contains("계정이 없습니다. 계정 생성을 사용하세요."));
        assertTrue(bulk("reset", "초기화", worker, manager.employeeId()).contains("본인 계정은 초기화할 수 없습니다."));
        long before = db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Long.class, tenant, worker);
        assertTrue(bulk("reset", "초기화", worker).contains("\"count\":1"));
        assertEquals(before + 1, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Long.class, tenant, worker));
        assertTrue(bulk("reset", " ", worker).contains("초기화 사유"));
    }

    @Test void bulkRejectsMalformedJsonAndEmployeeOnlyAccounts() throws Exception {
        for (String action : new String[] {"create", "reset"}) {
            mvc.perform(authenticated(post("/admin/accounts/" + action), manager).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("{\"employeeIds\":["))
                    .andExpect(status().isBadRequest());
            var plain = member("PLAIN" + action, java.util.Set.of("EMPLOYEE"));
            mvc.perform(authenticated(post("/admin/accounts/" + action), plain).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                            .content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"x\",\"employeeIds\":[]}"))
                    .andExpect(status().isForbidden());
        }
    }

    private kr.tna.common.auth.TnaPrincipal member(String number, java.util.Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 사용자',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new kr.tna.common.auth.TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, kr.tna.common.auth.TnaPrincipal actor) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
}
