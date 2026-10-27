package kr.tna;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.config.WorkspaceWebConfiguration;
import kr.tna.common.web.ReactPageViewResolver;
import kr.tna.console.OperatorController;
import kr.tna.console.OperatorData.OperatorPrincipal;
import kr.tna.console.OperatorMapper;
import kr.tna.console.OperatorSecurityConfiguration;
import kr.tna.console.OperatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = OperatorController.class, properties = "tna.operator.enabled=true")
@Import({OperatorSecurityConfiguration.class, ReactPageViewResolver.class, ReactOperatorSecurityTest.Security.class})
class ReactOperatorSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean OperatorMapper mapper;
    @MockitoBean OperatorService operators;
    @MockitoBean(name = "operatorDataSource") HikariDataSource source;
    @MockitoBean WorkspaceWebConfiguration workspace;

    @Test
    void failedOperatorSessionValidationRequiresReauthenticationAndPreservesTheCustomerContext() throws Exception {
        var actor = new OperatorPrincipal(UUID.randomUUID(), "synthetic-operator", 1, Instant.now());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))));
        var session = new MockHttpSession();
        session.setAttribute(OperatorSecurityConfiguration.CONTEXT_KEY, context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, "customer-context-preserved");
        when(mapper.current(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-details"));

        mvc.perform(get("/operator/tenants").session(session).header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.status").value(503))
                .andExpect(jsonPath("$.data.reauthenticate").value(true));
        assertNull(session.getAttribute(OperatorSecurityConfiguration.CONTEXT_KEY));
        assertEquals("customer-context-preserved", session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class Security {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean PasswordEncoder passwordEncoder() { return PasswordPolicy.passwordEncoder(); }
    }
}
