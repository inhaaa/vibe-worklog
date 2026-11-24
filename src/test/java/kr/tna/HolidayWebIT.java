package kr.tna;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
class HolidayWebIT {
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
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴일 웹')",
                tenant, "HOLIDAYWEB-" + tenant);
        manager = member("ADMIN", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    private kr.tna.common.auth.TnaPrincipal member(String number, java.util.Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new kr.tna.common.auth.TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }

    @Test void pagePreviewSaveAndLegacyAddressFollowTheContract() throws Exception {
        mvc.perform(authenticated(get("/admin/holidays").param("year", "2027"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.data("/holidays/year", 2027))
                .andExpect(ReactPageAssertions.data("/holidays/builtIn", true));
        mvc.perform(authenticated(get("/admin/calendar").param("date", "2027-01-04"), manager))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/admin/holidays"));
        mvc.perform(authenticated(post("/admin/holidays/public-preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("{\"year\":2027}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holidays.length()").value(24))
                .andExpect(jsonPath("$.holidays[0].name").value("1월 1일"))
                .andExpect(jsonPath("$.notice").value(kr.tna.clock.KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE));

        String bad = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"휴일 정리\",\"year\":2027,\"expectedVersion\":0,"
                + "\"rows\":[{\"clientRowId\":\"a\",\"date\":\"2027-01-01\",\"name\":\"\",\"category\":\"PUBLIC\",\"basis\":null}]}";
        mvc.perform(authenticated(post("/admin/holidays/save"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].clientRowId").value("a")).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(authenticated(post("/admin/holidays/save"), manager).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isForbidden());
        String good = bad.replace("\"name\":\"\"", "\"name\":\"1월 1일\"").replaceFirst("\"commandId\":\"[^\"]+\"", "\"commandId\":\"" + UUID.randomUUID() + "\"");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(authenticated(post("/admin/holidays/save"), manager).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(good))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.applied.length()").value(365));
        }
        mvc.perform(authenticated(post("/admin/calendar/versions"), manager).with(csrf())).andExpect(status().isForbidden());
        var plain = member("PLAIN", java.util.Set.of("EMPLOYEE"));
        mvc.perform(authenticated(get("/admin/holidays"), plain)).andExpect(status().isForbidden());
        mvc.perform(authenticated(post("/admin/holidays/public-preview"), plain).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2027}")).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, kr.tna.common.auth.TnaPrincipal actor) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
}
