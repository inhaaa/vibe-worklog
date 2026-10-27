package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class PasswordResetWebIT {
    private static final String OLD="Synthetic reset web old 43!",NEW="Synthetic reset web replacement 43!";
    private static final String HASH=PasswordPolicy.passwordEncoder().encode(OLD);
    @Autowired MockMvc mvc;
    @Autowired PasswordResetService resets;
    @Autowired InvitationDelivery delivery;
    private String company,email,address;
    private UUID tenant;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);p.add("tna.auth.url",f::jdbcUrl);
        p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void account() {
        var db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        company=("RESET-WEB-"+tenant).toUpperCase(java.util.Locale.ROOT);email=account+"@example.test";address="reset-web-"+tenant;
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 재설정 고객사')",tenant,company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'A','합성 재설정 사원',DATE '2020-01-01')",tenant,employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,employee,email,HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",tenant,account);
    }
    @Test void anonymousFormProvidesCsrfWithoutPuttingSecretsInTheUrl()throws Exception {
        mvc.perform(get("/password-reset")).andExpect(status().isOk()).andExpect(view().name("password-reset"))
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(content().string(containsString("no-referrer")))
                .andExpect(content().string(not(containsString(email))));
    }
    @Test void bothPublicMutationEndpointsKeepCsrfEnabled()throws Exception {
        mvc.perform(post("/password-reset/request")).andExpect(status().isForbidden());
        mvc.perform(post("/password-reset/complete")).andExpect(status().isForbidden());
    }
    @Test void knownAndUnknownRequestsHaveTheSameAcceptanceRedirectAndPublicMessage()throws Exception {
        for(String value:new String[]{email,"unknown@example.test"}) mvc.perform(request(value))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/password-reset?requested"));
        mvc.perform(get("/password-reset").param("requested",""))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(content().string(not(containsString(email))));
    }
    @Test void successfulCompletionInvalidatesExistingSessionAndRequiresManualLogin()throws Exception {
        var login=mvc.perform(post("/login").with(csrf()).param("companyCode",company).param("email",email).param("password",OLD))
                .andExpect(redirectedUrl("/me/today")).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);resets.request(company,email,address);
        mvc.perform(complete(message().token()).session(session)).andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/login?reset"));assertTrue(session.isInvalid());
        mvc.perform(post("/login").with(csrf()).param("companyCode",company).param("email",email).param("password",OLD))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("companyCode",company).param("email",email).param("password",NEW))
                .andExpect(redirectedUrl("/me/today"));
    }
    @Test void invalidCodeAndPasswordMismatchDoNotReflectSecrets()throws Exception {
        resets.request(company,email,address);String token=message().token();
        for(var request:new MockHttpServletRequestBuilder[]{complete("private-invalid-code"),complete(token).with(r->{r.setParameter("confirmation","mismatch-secret");return r;})}) {
            mvc.perform(request).andExpect(status().is(422)).andExpect(view().name("password-reset"))
                    .andExpect(content().string(not(containsString(token))))
                    .andExpect(content().string(not(containsString(NEW))))
                    .andExpect(content().string(not(containsString("mismatch-secret"))))
                    .andExpect(content().string(not(containsString("private-invalid-code"))));
        }
    }
    @Test void requestLimitReturns429AndRetryAfterWithoutDisclosingAccountExistence()throws Exception {
        for(int i=0;i<20;i++) mvc.perform(request("unknown@example.test")).andExpect(status().isSeeOther());
        mvc.perform(request("unknown@example.test")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","300"))
                .andExpect(content().string(not(containsString("unknown@example.test"))));
    }
    private MockHttpServletRequestBuilder request(String value){return post("/password-reset/request").with(csrf())
            .with(r->{r.setRemoteAddr(address);return r;}).param("companyCode",company).param("email",value);}
    private MockHttpServletRequestBuilder complete(String token){return post("/password-reset/complete").with(csrf())
            .with(r->{r.setRemoteAddr(address);return r;}).param("companyCode",company).param("email",email)
            .param("token",token).param("password",NEW).param("confirmation",NEW);}
    private InvitationDelivery.Envelope message(){return delivery.messages().stream().filter(m->m.companyCode().equals(company)).reduce((a,b)->b).orElseThrow();}
}
