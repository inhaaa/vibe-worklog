package kr.tna;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.CompanyAuthenticationProvider;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.config.SecurityConfiguration;
import kr.tna.common.config.WorkspaceWebConfiguration;
import kr.tna.common.web.DemoController;
import kr.tna.common.web.ReactPageViewResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DemoController.class)
@Import({SecurityConfiguration.class, ReactPageViewResolver.class, ReactSecurityTest.Time.class})
class ReactSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CompanyAuthenticationProvider provider;
    @MockitoBean AuthStore auth;
    @MockitoBean LocalDemoLogin demo;
    @MockitoBean WorkspaceWebConfiguration workspace;

    @Test
    void anonymousPageNavigationIsJsonUnauthorizedAndNormalNavigationStillRedirects() throws Exception {
        mvc.perform(get("/me/today").header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(jsonPath("$.page").value("org/problem"))
                .andExpect(jsonPath("$.data.status").value(401))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/me/today")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void csrfAndRoleDenialsAndUnknownPathsRemainForbiddenJson() throws Exception {
        mvc.perform(post("/logout").with(user("employee")).header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.status").value(403));
        for (String path : new String[]{"/admin/employees", "/operator/tenants", "/unlisted", "/actuator/env"}) {
            mvc.perform(get(path).with(user("employee").roles("EMPLOYEE")).header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.status").value(403));
        }
    }

    @Test
    void onlyGetReactAssetsArePublicAndLoginUsesTheReactResolver() throws Exception {
        mvc.perform(get("/react/nonexistent.js")).andExpect(status().isNotFound());
        mvc.perform(post("/react/nonexistent.js").with(user("employee")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/login").header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value("login"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty());
    }

    @Test
    void ownPhotoOnlyAllowsAuthenticatedGetAndDoesNotBroadenProfileWrites() throws Exception {
        mvc.perform(get("/me/profile/photo")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/me/profile/photo").with(user("employee").roles("EMPLOYEE")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/me/profile/photo").with(user("employee").roles("EMPLOYEE")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void sessionValidationStorageFailureReturnsJson503AndInvalidatesTheSession() throws Exception {
        var principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        when(auth.isCurrent(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-detail"));
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(get("/me/today").session(session).with(authentication(authentication)).header("Accept", ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.status").value(503))
                .andExpect(jsonPath("$.data.reauthenticate").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("private-db-detail"))));
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }

    @Test
    void ordinaryStorageErrorsDoNotRequestReauthentication() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Accept", ReactPageViewResolver.MEDIA_TYPE);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        kr.tna.common.web.ReactPageResponse.problem(request, response, 503, "잠시 후 다시 시도해 주세요.");
        var data = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsString()).get("data");
        org.junit.jupiter.api.Assertions.assertFalse(data.has("reauthenticate"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class Time {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
