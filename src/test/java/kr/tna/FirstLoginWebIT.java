package kr.tna;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FirstLoginWebIT {
    static final String PAGE = "application/vnd.worklog.page+json";
    @Autowired MockMvc mvc;
    String company;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { FirstLoginIT.database(p); }

    @BeforeEach void seed() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID tenant = UUID.randomUUID(), employee = UUID.randomUUID(), account = UUID.randomUUID();
        company = ("WEB-" + tenant).toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 화면')", tenant, company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'2019031','합성 사원',DATE '2019-03-04')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at) VALUES (?,?,?,false,now()+interval '7 days')", tenant, account, employee);
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)", tenant, employee, account);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
    }

    @Test void employeeNumberAsPasswordMovesToFirstLoginWithoutQueryParameters() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("companyCode", company)
                        .param("email", "2019031").param("password", "2019031"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/first-login"));
        mvc.perform(get("/first-login").session(session).accept(PAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value("first-login"))
                .andExpect(jsonPath("$.data.companyCode").value(company))
                .andExpect(jsonPath("$.data.employeeNo").value("2019031"));
    }

    @Test void unknownCompanyAndEmployeeGetTheSameRedirectAsARealPendingAccount() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("companyCode", "NO-SUCH-" + UUID.randomUUID())
                        .param("email", "0000000").param("password", "0000000"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/first-login"));
    }

    @Test void overLongOrMissingInputsFallThroughToTheGenericError() throws Exception {
        String longLogin = "1".repeat(41);
        mvc.perform(post("/login").with(csrf()).param("companyCode", company).param("email", longLogin).param("password", longLogin))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("companyCode", "C".repeat(81)).param("email", "2019031").param("password", "2019031"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("email", "2019031").param("password", "2019031"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test void firstLoginPostRequiresCsrf() throws Exception {
        mvc.perform(post("/first-login")).andExpect(status().isForbidden());
    }

    @Test void flashCarriesOnlyCompanyCodeAndEmployeeNumber() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("companyCode", company)
                        .param("email", "2019031").param("password", "2019031"))
                .andExpect(redirectedUrl("/first-login"));
        @SuppressWarnings("unchecked")
        var maps = (java.util.List<FlashMap>) session.getAttribute(SessionFlashMapManager.class.getName() + ".FLASH_MAPS");
        org.junit.jupiter.api.Assertions.assertEquals(1, maps.size());
        org.junit.jupiter.api.Assertions.assertEquals(Set.of("companyCode", "employeeNo"), maps.get(0).keySet());
    }

    @Test void pageClientGetsTheSameRedirect() throws Exception {
        mvc.perform(post("/login").with(csrf()).accept(PAGE).param("companyCode", company)
                        .param("email", "2019031").param("password", "2019031"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/first-login"));
    }

    @Test void otherWrongPasswordsStayOnLogin() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("companyCode", company).param("email", "2019031").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test void completionRedirectsToLoginAndFailureKeepsSameMessage() throws Exception {
        mvc.perform(post("/first-login").with(csrf()).param("companyCode", company).param("employeeNo", "2019031")
                        .param("birthDate", "1990-05-13").param("password", "Synthetic first web 43!")
                        .param("confirmation", "Synthetic first web 43!").accept(PAGE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.error").value("입력한 정보가 맞지 않거나 최초 접속 기간이 지났습니다. 담당자에게 문의하세요."));
        mvc.perform(post("/first-login").with(csrf()).param("companyCode", company).param("employeeNo", "2019031")
                        .param("birthDate", "1990-05-12").param("password", "Synthetic first web 43!")
                        .param("confirmation", "Synthetic first web 43!"))
                .andExpect(redirectedUrl("/login?activated"));
    }
}
