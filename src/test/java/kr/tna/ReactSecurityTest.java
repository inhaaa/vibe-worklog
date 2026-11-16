package kr.tna;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.CompanyAuthenticationProvider;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.config.SecurityConfiguration;
import kr.tna.common.config.WorkspaceWebConfiguration;
import kr.tna.common.web.DemoController;
import kr.tna.common.web.ReactPageViewResolver;
import kr.tna.org.EmployeeOverview;
import kr.tna.workspace.WorkspaceController;
import kr.tna.workspace.WorkspaceData.DayView;
import kr.tna.workspace.WorkspaceData.HomeView;
import kr.tna.workspace.WorkspaceQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({DemoController.class, WorkspaceController.class})
@Import({SecurityConfiguration.class, ReactPageViewResolver.class, ReactSecurityTest.Time.class})
class ReactSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CompanyAuthenticationProvider provider;
    @MockitoBean AuthStore auth;
    @MockitoBean LocalDemoLogin demo;
    @MockitoBean WorkspaceWebConfiguration workspace;
    @MockitoBean WorkspaceQueries workspaceQueries;

    @Test
    void homeAllowsOnlyAuthenticatedGetAndUsesTheCurrentPrincipal() throws Exception {
        var principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        when(auth.isCurrent(principal)).thenReturn(true);
        var day = new DayView(new DaySnapshot(principal.employeeId(), LocalDate.of(2026, 11, 6),
                List.of(), null, null, null, "UNCONFIRMED", 0, 0, null, "internal-fingerprint", List.of()),
                List.of(), true, true);
        when(workspaceQueries.home(principal)).thenReturn(new HomeView(new EmployeeOverview("김직원", "합성회사"),
                day, List.of(day), null, 2, List.of()));
        mvc.perform(get("/me/home").accept(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/me/home").with(authentication(authentication)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/me/home").param("tenantId", UUID.randomUUID().toString())
                .with(authentication(authentication)).accept(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value("workspace/home"))
                .andExpect(jsonPath("$.data.workspaceHome.pendingRequests").value(2))
                .andExpect(jsonPath("$.data.workspaceHome.today.snapshot.workDate").value("2026-11-06"))
                .andExpect(jsonPath("$.data.workspaceHome.today.snapshot.sourceFingerprint").doesNotExist());
    }

    @Test
    void homeClockOffersCommandsOnlyForUnrecordedKindsAndPassesTheErrorStatus() throws Exception {
        var principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        when(auth.isCurrent(principal)).thenReturn(true);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        var date = LocalDate.of(2026, 11, 6);
        var in = new kr.tna.clock.ClockData.Event(UUID.randomUUID(), "IN", Instant.parse("2026-11-06T00:28:14Z"), date);
        var empty = new DayView(new DaySnapshot(principal.employeeId(), date, List.of(), null, null, null, "UNCONFIRMED",
                0, 0, null, "fixture", List.of()), List.of(), true, true);
        when(workspaceQueries.home(principal)).thenReturn(new HomeView(new EmployeeOverview("김직원", "합성회사"),
                empty, List.of(empty), null, 0, List.of()));
        mvc.perform(get("/me/home").param("clockError", "403").with(authentication(authentication)).accept(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clockInCommand").isString()).andExpect(jsonPath("$.data.clockOutCommand").isString())
                .andExpect(jsonPath("$.data.clockError").value(403))
                .andExpect(jsonPath("$.data.workspaceHome.today.inRecordedAt").value(org.hamcrest.Matchers.nullValue()));
        var recorded = new DayView(new DaySnapshot(principal.employeeId(), date, List.of(in), null, null, null, "UNCONFIRMED",
                1, 0, null, "fixture", List.of()), List.of(), true, true);
        when(workspaceQueries.home(principal)).thenReturn(new HomeView(new EmployeeOverview("김직원", "합성회사"),
                recorded, List.of(recorded), null, 0, List.of()));
        mvc.perform(get("/me/home").with(authentication(authentication)).accept(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clockInCommand").doesNotExist()).andExpect(jsonPath("$.data.clockOutCommand").isString())
                .andExpect(jsonPath("$.data.workspaceHome.today.inRecordedAt").value("2026-11-06T00:28:14Z"))
                .andExpect(jsonPath("$.data.workspaceHome.today.outRecordedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void homeWidgetScopeIsOpaqueAndStableForEachTenantAndAccount() throws Exception {
        var principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        var nextSession = new TnaPrincipal(principal.tenantId(), principal.accountId(), principal.employeeId(), 2,
                Set.of("EMPLOYEE", "APPROVER"), Instant.now());
        var otherAccount = new TnaPrincipal(principal.tenantId(), UUID.randomUUID(), principal.employeeId(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        var otherTenant = new TnaPrincipal(UUID.randomUUID(), principal.accountId(), principal.employeeId(), 1,
                Set.of("EMPLOYEE"), Instant.now());

        String scope = homeWidgetScope(principal);
        assertEquals(scope, homeWidgetScope(nextSession));
        assertNotEquals(scope, homeWidgetScope(otherAccount));
        assertNotEquals(scope, homeWidgetScope(otherTenant));
        assertNotEquals(principal.tenantId().toString(), scope);
        assertNotEquals(principal.accountId().toString(), scope);
        assertNotEquals(principal.employeeId().toString(), scope);
        assertTrue(scope.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    }

    private String homeWidgetScope(TnaPrincipal principal) throws Exception {
        when(auth.isCurrent(principal)).thenReturn(true);
        var day = new DayView(new DaySnapshot(principal.employeeId(), LocalDate.of(2026, 11, 6),
                List.of(), null, null, null, "UNCONFIRMED", 0, 0, null, "fixture", List.of()),
                List.of(), true, true);
        when(workspaceQueries.home(principal)).thenReturn(new HomeView(new EmployeeOverview("김직원", "합성회사"),
                day, List.of(day), null, 0, List.of()));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        var response = mvc.perform(get("/me/home").param("homeWidgetScope", "client-selected-scope")
                        .with(authentication(authentication)).accept(ReactPageViewResolver.MEDIA_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.homeWidgetScope").isString())
                .andReturn().getResponse();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsString())
                .get("data").get("homeWidgetScope").asString();
    }

    @Test
    void successfulEmployeeLoginOpensHome() throws Exception {
        var principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Set.of("EMPLOYEE"), Instant.now());
        when(provider.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(provider.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
        mvc.perform(post("/login").with(csrf()).param("email", "employee@example.test").param("password", "fixture"))
                .andExpect(redirectedUrl("/me/home"));
    }

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
                .andExpect(jsonPath("$.data.homeWidgetScope").doesNotExist())
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
