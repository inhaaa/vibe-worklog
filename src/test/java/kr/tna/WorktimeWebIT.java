package kr.tna;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc
class WorktimeWebIT {
    static final String PASSWORD="Synthetic reports web 43!";@Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){var d=DatabaseFixture.start();r.add("spring.datasource.url",d::jdbcUrl);r.add("spring.datasource.username",()->DatabaseFixture.APP_USER);r.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);r.add("tna.auth.url",d::jdbcUrl);r.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);r.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);}
    @BeforeEach void seed(){var d=DatabaseFixture.start();d.seedIdentity(kr.tna.common.auth.PasswordPolicy.passwordEncoder().encode(PASSWORD));new JdbcTemplate(d.adminDataSource()).update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",DatabaseFixture.TENANT_A,DatabaseFixture.ACCOUNT_A);}
    @Test void managerCanOpenMonthlyReviewAndPolicyForms() throws Exception{var s=login(true);mvc.perform(get("/admin/attendance").session(s)).andExpect(status().isOk()).andExpect(content().string(containsString("미확정"))).andExpect(content().string(containsString("검토용")));mvc.perform(get("/admin/work-policies").session(s)).andExpect(status().isOk());}
    @Test void employeeCanReadOwnMonthButCannotCalculateExportOrReadAudit() throws Exception{var s=login(false);mvc.perform(get("/me/worktime").session(s)).andExpect(status().isOk());for(String p:java.util.List.of("/admin/attendance","/admin/audit","/admin/work-policies"))mvc.perform(get(p).session(s)).andExpect(status().isForbidden());mvc.perform(post("/admin/calculations").session(s).with(csrf())).andExpect(status().isForbidden());}
    @Test void stateChangesRequireCsrfAndMalformedIdentifiersAreSafe() throws Exception{var s=login(true);mvc.perform(post("/admin/calculations").session(s)).andExpect(status().isForbidden());mvc.perform(get("/admin/reports/exports/not-an-id/download").session(s)).andExpect(status().isBadRequest());}
    @Test void calculationAndReviewExportReturnAuthorizedXlsx() throws Exception {
        var session=login(true);var from=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);var to=from.plusDays(1);
        mvc.perform(post("/admin/calculations").session(session).with(csrf()).param("employeeId",DatabaseFixture.EMPLOYEE_A.toString()).param("from",from.toString()).param("to",to.toString()).param("reason","합성 웹 재집계").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        var prepared=mvc.perform(post("/admin/reports/exports").session(session).with(csrf()).param("employeeId",DatabaseFixture.EMPLOYEE_A.toString()).param("from",from.toString()).param("to",to.toString()).param("reason","합성 검토용 출력").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther()).andReturn();
        String url=prepared.getResponse().getRedirectedUrl();org.junit.jupiter.api.Assertions.assertNotNull(url);
        mvc.perform(get(url).session(session)).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Cache-Control",containsString("no-store"))).andExpect(r->{var bytes=r.getResponse().getContentAsByteArray();org.junit.jupiter.api.Assertions.assertEquals('P',bytes[0]);org.junit.jupiter.api.Assertions.assertEquals('K',bytes[1]);});
        mvc.perform(get(url).session(login(false))).andExpect(status().isForbidden());
    }
    @Test void weeklyRangesDisplayTheInclusiveSundayInsteadOfTheNextMonday() throws Exception {
        mvc.perform(get("/me/worktime").session(login(false)).param("month","2026-10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-09-28 ~ 2026-10-04")))
                .andExpect(content().string(not(containsString("2026-09-28 ~ 2026-10-05"))));
    }
    private MockHttpSession login(boolean admin) throws Exception{return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",admin?DatabaseFixture.COMPANY_A:DatabaseFixture.COMPANY_B).param("email",DatabaseFixture.EMAIL).param("password",PASSWORD)).andExpect(redirectedUrl("/me/today")).andReturn().getRequest().getSession(false);}
}
