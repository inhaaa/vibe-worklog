package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.web.ReactPageViewResolver;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class EmploymentLeaveWebIT {
    private static final String PATH = "/admin/leave-balances/employment";
    private static final String PAGE = ReactPageViewResolver.MEDIA_TYPE;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private TnaPrincipal admin, worker, other;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID tenant = tenant();
        admin = member(tenant, "HR", today.minusYears(2), null, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        worker = member(tenant, "W-101", today.minusDays(36), today, Set.of("EMPLOYEE"));
        other = member(tenant(), "W-101", today.minusMonths(2), null, Set.of("EMPLOYEE"));
    }

    @Test void managerAndEmployeeSeeTheSameZeroDayDecisionAndExitReceipt() throws Exception {
        var list = page(admin, get(PATH).param("q", "W-101"));
        assertEquals(1, list.path("employees").size());
        assertTrue(list.path("monthly").isNull() || list.path("monthly").isMissingNode());
        var selected = selected();
        var period = selected.at("/monthly/periods/0");
        assertEquals(1, selected.at("/monthly/periods").size());
        assertFalse(selected.at("/exit/canPost").asBoolean());
        var review = Map.of("employeeId", worker.employeeId(), "accrualOn", period.path("accrualOn").asString(),
                "decision", "NOT_FULL", "reason", "인사 확인 결과 해당 기간 결근", "token", period.path("token").asString(),
                "commandId", UUID.randomUUID());
        var posted = body(write(admin, "/monthly", review).andExpect(status().isOk()));
        assertEquals(posted, body(write(admin, "/monthly", review).andExpect(status().isOk())));
        assertEquals("NOT_FULL", selected().at("/monthly/periods/0/decision").asString());
        var mine = page(worker, get("/me/leave-ledger")).path("ledger");
        assertEquals(0, mine.path("availableDays").asDouble());
        assertTrue(mine.path("entries").valueStream().anyMatch(entry -> "MONTHLY_ACCRUAL".equals(entry.path("entryKind").asString())
                && "NOT_FULL".equals(entry.path("monthlyDecision").asString(""))
                && entry.path("signedQuantityDays").asDouble() == 0 && entry.path("reason").asString().contains("결근")));
        var preview = selected().path("exit");
        assertTrue(preview.path("canPost").asBoolean(), preview.toString());
        var command = Map.of("employeeId", worker.employeeId(), "token", preview.path("token").asString(),
                "reason", "마지막 재직일 0일 대조", "commandId", UUID.randomUUID(), "priorHistoryConfirmed", false);
        var receipt = body(write(admin, "/settle", command).andExpect(status().isOk()));
        assertEquals(receipt, body(write(admin, "/settle", command).andExpect(status().isOk())));
        var saved = selected().path("exit");
        assertEquals(receipt.path("id").asString(), saved.path("settlementId").asString());
        var employeeReceipt = page(worker, get("/me/leave-ledger")).at("/ledger/exitReceipt");
        assertEquals(today.toString(), employeeReceipt.path("leftOn").asString());
        assertEquals(saved.path("settlementDays").asDouble(), employeeReceipt.path("settlementDays").asDouble());
        assertEquals(saved.path("reason").asString(), employeeReceipt.path("reason").asString());
        assertTrue(page(worker, get("/me/leave-ledger").param("year", String.valueOf(today.getYear() - 1))).at("/ledger/exitReceipt").isNull());
        assertTrue(page(other, get("/me/leave-ledger")).at("/ledger/exitReceipt").isNull());
    }

    @Test void readsAndActionsRespectCompanyRoleSessionAndCsrf() throws Exception {
        mvc.perform(as(get(PATH), worker)).andExpect(status().isForbidden());
        for (String endpoint : new String[]{"/monthly", "/settle"}) {
            write(worker, endpoint, Map.of()).andExpect(status().isForbidden());
            mvc.perform(as(post(PATH + endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"), admin)).andExpect(status().isForbidden());
            write(admin, endpoint, Map.of("unknownField", true)).andExpect(status().isBadRequest());
        }
        mvc.perform(as(get(PATH).param("employeeId", other.employeeId().toString()).accept(PAGE), admin)).andExpect(status().isNotFound());
        mvc.perform(as(get(PATH).param("q", "W", "HR").accept(PAGE), admin)).andExpect(status().isBadRequest());
        mvc.perform(as(get(PATH).param("page", "-1").accept(PAGE), admin)).andExpect(status().isUnprocessableContent());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.minusDays(1), worker.tenantId(), worker.employeeId());
        mvc.perform(as(get("/me/leave-ledger").accept(PAGE), worker)).andExpect(status().isUnauthorized());
    }

    @Test void searchPagesCountEligibleEmployeesAfterExcludingTheCurrentAdministrator() throws Exception {
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                SELECT ?,gen_random_uuid(),'A'||n,'가상 직원 '||n,? FROM generate_series(1,49) n
                """, admin.tenantId(), today.minusMonths(2));
        var results = page(admin, get(PATH));
        assertFalse(results.path("hasNext").asBoolean());
        assertEquals(50, results.path("employees").size());
        assertFalse(results.path("employees").valueStream().anyMatch(e -> e.path("id").asString().equals(admin.employeeId().toString())));
    }

    private JsonNode selected() throws Exception { return page(admin, get(PATH).param("employeeId", worker.employeeId().toString())); }

    private JsonNode page(TnaPrincipal actor, MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(as(request.accept(PAGE), actor)).andExpect(status().isOk()).andExpect(ReactPageAssertions.csrf()).andReturn();
        return ReactPageAssertions.envelope(response).path("data");
    }

    private ResultActions write(TnaPrincipal actor, String suffix, Object input) throws Exception {
        return mvc.perform(as(post(PATH + suffix).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)), actor));
    }

    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsByteArray()); }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, TnaPrincipal actor) {
        var roles = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null, roles)));
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 정산 회사')", id, "HR-" + id);
        return id;
    }

    private TnaPrincipal member(UUID tenant, String number, LocalDate hire, LocalDate last, Set<String> roles) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                tenant, employee, number, "합성 " + number, hire, last);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                tenant, account, employee, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employee, 1, roles, Instant.now());
    }
}
