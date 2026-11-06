package kr.tna;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="tna.operator.enabled=true") @AutoConfigureMockMvc @ActiveProfiles("local")
class OperatorWebIT {
    static final String PASSWORD="Synthetic operator web account 43!";
    @Autowired MockMvc mvc;@Autowired InvitationDelivery delivery;
    private UUID operatorId;private String login;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);p.add("tna.operator.url",f::jdbcUrl);p.add("tna.operator.password",()->"synthetic-operator-db-password-only");}
    @BeforeEach void identities(){var f=DatabaseFixture.start();f.seedIdentity(PasswordPolicy.passwordEncoder().encode(PASSWORD));operatorId=UUID.randomUUID();login="operator-"+operatorId;db().update("INSERT INTO platform.operator_account(id,login_id,password_hash) VALUES (?,?,?)",operatorId,login,PasswordPolicy.passwordEncoder().encode(PASSWORD));}
    @Test void explicitlyEnabledOperatorLoginUsesItsOwnCsrfForm()throws Exception{mvc.perform(get("/operator/login")).andExpect(status().isOk()).andExpect(ReactPageAssertions.csrf());}
    @Test void reactOperatorNavigationKeepsIndependentAuthenticationAndCsrfErrors()throws Exception{
        String media=kr.tna.common.web.ReactPageViewResolver.MEDIA_TYPE;
        mvc.perform(get("/operator/login").header("Accept",media)).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value("operator/login")).andExpect(jsonPath("$.csrf.token").isNotEmpty());
        mvc.perform(get("/operator/tenants").header("Accept",media)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.status").value(401));
        var customer=customerLogin();
        mvc.perform(get("/operator/tenants").session(customer).header("Accept",media)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.status").value(401));
        var operator=operatorLogin(null);
        mvc.perform(post("/operator/tenants").session(operator).header("Accept",media)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.status").value(403));
        mvc.perform(get("/me/profile").session(operator).header("Accept",media)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.status").value(401));
    }
    @Test void operatorLoginDoesNotCreateAnyCustomerSession()throws Exception{var session=operatorLogin(null);mvc.perform(get("/operator/tenants").session(session)).andExpect(status().isOk());mvc.perform(get("/me/profile").session(session)).andExpect(redirectedUrl("/login"));mvc.perform(get("/admin/employees").session(session)).andExpect(redirectedUrl("/login"));}
    @Test void customerSessionCannotEnterOperatorAndBothContextsStaySeparate()throws Exception{
        var customer=customerLogin();mvc.perform(get("/operator/tenants").session(customer)).andExpect(redirectedUrl("/operator/login"));
        var both=operatorLogin(customer);mvc.perform(get("/operator/tenants").session(both)).andExpect(status().isOk());mvc.perform(get("/me/profile").session(both)).andExpect(status().isOk());
    }
    @Test void loginOnboardingStateAndLogoutRequireCsrf()throws Exception{
        mvc.perform(post("/operator/login").param("loginId",login).param("password",PASSWORD)).andExpect(status().isForbidden());
        var session=operatorLogin(null);
        for(String path:java.util.List.of("/operator/tenants","/operator/tenants/"+UUID.randomUUID()+"/state","/operator/logout"))mvc.perform(post(path).session(session)).andExpect(status().isForbidden());
    }
    @Test void enabledOperatorCanCreatePendingCompanyWithoutTokenOrPasswordInHtml()throws Exception{
        var session=operatorLogin(null);String code="WEB-"+UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),email="web-"+UUID.randomUUID()+"@example.test";
        mvc.perform(post("/operator/tenants").session(session).with(csrf()).param("companyCode",code).param("companyName","합성 웹 회사").param("employeeNo","FIRST-1").param("employeeName","합성 담당자").param("joinedOn",LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).param("email",email).param("relationshipBasis","회사와 연락 경로 확인").param("reason","신규 연결 검증").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        var envelope=delivery.messages().stream().filter(e->e.email().equals(email)).findFirst().orElseThrow();
        mvc.perform(get("/operator/tenants").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString(code))).andExpect(content().string(not(containsString(envelope.token())))).andExpect(content().string(not(containsString(PASSWORD))));
    }
    @Test void revokedOperatorCannotUseAnExistingSession()throws Exception{var session=operatorLogin(null);db().update("UPDATE platform.operator_account SET auth_version=auth_version+1 WHERE id=?",operatorId);mvc.perform(get("/operator/tenants").session(session)).andExpect(redirectedUrl("/operator/login"));}
    @Test void malformedAndUngroundedInputDoesNotCreateACompany()throws Exception{
        var session=operatorLogin(null);mvc.perform(post("/operator/tenants").session(session).with(csrf()).param("companyCode","INVALID").param("joinedOn","not-a-date").param("commandId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
    }
    @Test void initialInviteReissueIgnoresForgedAccountAndEmailAndRequiresCsrf()throws Exception{
        var session=operatorLogin(null);String code="REISSUE-"+UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),email="initial-"+UUID.randomUUID()+"@example.test";
        mvc.perform(post("/operator/tenants").session(session).with(csrf()).param("companyCode",code).param("companyName","합성 재발급 회사").param("employeeNo","FIRST-1").param("employeeName","합성 담당자").param("joinedOn",LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).param("email",email).param("relationshipBasis","합성 확인").param("reason","합성 신규 연결").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        UUID tenant=db().queryForObject("SELECT id FROM platform.tenant_registry WHERE company_code=?",UUID.class,code);
        mvc.perform(post("/operator/tenants/{id}/initial-invitations",tenant).session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/operator/tenants/{id}/initial-invitations",tenant).session(session).with(csrf()).param("expectedVersion","0").param("reason","최초 초대 복구").param("commandId",UUID.randomUUID().toString()).param("email","forged@example.test").param("accountId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        assertEquals(2,delivery.messages().stream().filter(e->e.email().equals(email)).count());
        assertTrue(delivery.messages().stream().noneMatch(e->e.email().equals("forged@example.test")));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?",Integer.class,tenant));
    }
    private MockHttpSession operatorLogin(MockHttpSession existing)throws Exception{var request=post("/operator/login").with(csrf()).param("loginId",login).param("password",PASSWORD);if(existing!=null)request.session(existing);return (MockHttpSession)mvc.perform(request).andExpect(redirectedUrl("/operator/tenants")).andReturn().getRequest().getSession(false);}
    private MockHttpSession customerLogin()throws Exception{return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",DatabaseFixture.COMPANY_A).param("email",DatabaseFixture.EMAIL).param("password",PASSWORD)).andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);}
    private JdbcTemplate db(){return new JdbcTemplate(DatabaseFixture.start().adminDataSource());}
}
