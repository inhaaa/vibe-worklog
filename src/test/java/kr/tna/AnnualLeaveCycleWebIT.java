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
class AnnualLeaveCycleWebIT {
    private static final String ADMIN = "/admin/leave-balances/annual-cycle";
    private static final String MINE = "/me/leave-ledger";
    private static final String PAGE = ReactPageViewResolver.MEDIA_TYPE;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private TnaPrincipal admin, employee;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

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

    @BeforeEach void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 연차 회사')", tenant, "LEDGER-" + tenant);
        admin = member(tenant, "M01", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member(tenant, "E01", Set.of("EMPLOYEE"));
    }

    @Test void adminPreviewsWithoutWritingAndEmployeeCanReadOnlyTheirOwnPostedDays() throws Exception {
        var adminPage = mvc.perform(as(get(ADMIN).accept(PAGE), admin)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.csrf()).andExpect(ReactPageAssertions.data("/yearSelected", false)).andReturn();
        assertEquals("request/annual-cycle", ReactPageAssertions.envelope(adminPage).path("page").asString());
        mvc.perform(as(get(ADMIN).param("year", String.valueOf(today.getYear())).accept(PAGE), admin))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/yearSelected", true));
        var policy = Map.of("expectedVersion", 0, "fiscalStartMonth", 1, "grantDays", 12.5, "carryLimitDays", 3,
                "reason", "합성 운영 규정", "commandId", UUID.randomUUID());
        var saved = json(postJson("/policy", policy, admin).andExpect(status().isOk()));
        assertEquals(1, saved.path("version").asInt());
        int year = today.getYear();
        JsonNode preview = json(postJson("/preview", Map.of("year", year), admin).andExpect(status().isOk()));
        assertEquals(1, preview.path("rows").size());
        assertEquals("E01", preview.at("/rows/0/employeeNo").asString());
        assertEquals(12.5, preview.at("/rows/0/grantDays").asDouble());
        assertEquals(0, ownLedger(employee).path("entries").size());
        assertEquals(0, ownLedger(employee).path("availableDays").asDouble());
        var application = Map.of("year", year, "token", preview.path("token").asString(), "reason", "연도 시작",
                "commandId", UUID.randomUUID());
        var first = json(postJson("/apply", application, admin).andExpect(status().isOk()));
        var retry = json(postJson("/apply", application, admin).andExpect(status().isOk()));
        assertFalse(first.path("id").asString().isBlank());
        assertEquals(first.path("id"), retry.path("id"));
        assertEquals(12.5, ownLedger(employee).path("availableDays").asDouble());
        assertEquals("GRANT", ownLedger(employee).at("/entries/0/entryKind").asString());
        assertEquals(0, ownLedger(admin).path("entries").size());
        var annual = mvc.perform(as(get("/me/requests").accept(PAGE), employee)).andExpect(status().isOk()).andReturn();
        assertEquals(12.5, ReactPageAssertions.envelope(annual).at("/data/annual/registeredDays").asDouble());
        assertEquals(12.5, ReactPageAssertions.envelope(annual).at("/data/annual/currentAvailableDays").asDouble());
        var otherAdmin = member(admin.tenantId(), "M02", admin.roles());
        var revision = Map.of("expectedVersion", 1, "fiscalStartMonth", 1, "grantDays", 18.5, "carryLimitDays", 2,
                "reason", "다음 연도 개정", "commandId", UUID.randomUUID());
        postJson("/policy", revision, otherAdmin).andExpect(status().isOk());
        assertEquals(saved, json(postJson("/policy", policy, admin).andExpect(status().isOk())));
        mvc.perform(as(get(ADMIN).accept(PAGE), admin)).andExpect(ReactPageAssertions.data("/policy/version", 2));
    }

    @Test void employeeCannotChooseAnotherLedgerOrManageCompanyAndWritesRequireCsrf() throws Exception {
        mvc.perform(as(get(ADMIN).accept(PAGE), employee)).andExpect(status().isForbidden());
        postJson("/policy", Map.of(), employee).andExpect(status().isForbidden());
        postJson("/preview", Map.of("year", today.getYear()), employee).andExpect(status().isForbidden());
        postJson("/apply", Map.of(), employee).andExpect(status().isForbidden());
        mvc.perform(as(post(ADMIN + "/apply").contentType(MediaType.APPLICATION_JSON).content("{}"), admin))
                .andExpect(status().isForbidden());
        mvc.perform(as(get(MINE).accept(PAGE).param("employeeId", admin.employeeId().toString()), employee))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get(MINE).accept(PAGE).param("year", "2025", "2026"), employee)).andExpect(status().isBadRequest());
        mvc.perform(as(get(ADMIN).accept(PAGE).param("year", "10000"), admin)).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(MINE).accept(PAGE)).andExpect(status().isUnauthorized());
    }

    @Test void aFractionalMonthVersionOrYearNeverAppliesTheTruncatedValue() throws Exception {
        var rule = new java.util.HashMap<String, Object>(Map.of("expectedVersion", 0, "fiscalStartMonth", 1.9,
                "grantDays", 12.5, "carryLimitDays", 3, "reason", "합성 기준", "commandId", UUID.randomUUID()));
        postJson("/policy", rule, admin).andExpect(status().isBadRequest());
        rule.put("fiscalStartMonth", 1);
        rule.put("expectedVersion", 0.9);
        postJson("/policy", rule, admin).andExpect(status().isBadRequest());
        rule.put("expectedVersion", 0);
        postJson("/policy", rule, admin).andExpect(status().isOk());
        int year = today.getYear();
        postJson("/preview", Map.of("year", year + 0.9), admin).andExpect(status().isBadRequest());
        var preview = json(postJson("/preview", Map.of("year", year), admin).andExpect(status().isOk()));
        postJson("/apply", Map.of("year", year + 0.9, "token", preview.path("token").asString(),
                "reason", "합성 반영", "commandId", UUID.randomUUID()), admin).andExpect(status().isBadRequest());
        assertTrue(ownLedger(employee).path("entries").isEmpty());
    }

    private JsonNode ownLedger(TnaPrincipal actor) throws Exception {
        var response = mvc.perform(as(get(MINE).accept(PAGE), actor)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page()).andReturn();
        var page = ReactPageAssertions.envelope(response);
        assertEquals("request/personal-ledger", page.path("page").asString());
        return page.at("/data/ledger");
    }

    private ResultActions postJson(String path, Map<String, ?> body, TnaPrincipal actor) throws Exception {
        return mvc.perform(as(post(ADMIN + path).with(csrf()).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)), actor));
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, TnaPrincipal actor) {
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities)));
    }

    private TnaPrincipal member(UUID tenant, String number, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, number, "합성 " + number, today.minusYears(2));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                tenant, account, employeeId, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }
}
