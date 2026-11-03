package kr.tna.common.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoPersonaAuthenticationTest {
    private static final UUID APPROVER_EMPLOYEE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0201");
    private static final UUID APPROVER_ACCOUNT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0211");
    private static final String APPROVER_EMAIL = "approver@local.example.test";
    private static final PasswordEncoder ENCODER = PasswordPolicy.passwordEncoder();
    private static final String APPROVER_HASH = ENCODER.encode("approver");
    private AuthStore store;
    private CompanyAuthenticationProvider provider;

    @BeforeEach
    void localDemoProvider() {
        store = mock(AuthStore.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local", "local-demo");
        provider = new CompanyAuthenticationProvider(store, ENCODER, Clock.systemUTC(), new LocalDemoLogin(environment));
    }

    @Test
    void approverUsesItsOwnStoredHashAndPrincipalWithinTheFixedTenant() {
        var principal = principal(LocalDemoLogin.TENANT_ID, APPROVER_ACCOUNT, APPROVER_EMPLOYEE, Set.of("EMPLOYEE", "APPROVER"));
        stored(principal, APPROVER_HASH);

        var result = assertDoesNotThrow(() -> provider.authenticate(credentials("approver", "approver")));

        assertTrue(result.isAuthenticated());
        assertSame(principal, result.getPrincipal());
        assertNull(result.getCredentials());
        assertEquals(Set.of("ROLE_EMPLOYEE", "ROLE_APPROVER"), result.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(java.util.stream.Collectors.toSet()));
        verify(store).forLogin(LocalDemoLogin.COMPANY_CODE, APPROVER_EMAIL);
    }

    @Test
    void approverCannotUseAnAdminOrForeignPrincipalDespiteMatchingHash() {
        for (var principal : java.util.List.of(
                principal(UUID.randomUUID(), APPROVER_ACCOUNT, APPROVER_EMPLOYEE, Set.of("EMPLOYEE", "APPROVER")),
                principal(LocalDemoLogin.TENANT_ID, UUID.randomUUID(), APPROVER_EMPLOYEE, Set.of("EMPLOYEE", "APPROVER")),
                principal(LocalDemoLogin.TENANT_ID, APPROVER_ACCOUNT, UUID.randomUUID(), Set.of("EMPLOYEE", "APPROVER")),
                principal(LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.EMPLOYEE_ID, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")))) {
            stored(principal, APPROVER_HASH);
            assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "approver")));
        }
    }

    @Test
    void approverRequiresExactlyItsTwoRoles() {
        for (Set<String> roles : java.util.List.of(Set.of("EMPLOYEE"), Set.of("APPROVER"),
                Set.of("EMPLOYEE", "APPROVER", "ATTENDANCE_ADMIN"), Set.of("EMPLOYEE", "APPROVER", "COMPANY_REPRESENTATIVE"))) {
            stored(principal(LocalDemoLogin.TENANT_ID, APPROVER_ACCOUNT, APPROVER_EMPLOYEE, roles), APPROVER_HASH);
            assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "approver")));
        }
    }

    @Test
    void exactApproverAliasAndCredentialAreRequired() {
        for (String login : java.util.List.of("APPROVER", " approver", "approver ", APPROVER_EMAIL, "unknown")) {
            assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials(login, "approver")));
        }
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "admin")));
        verifyNoInteractions(store);
    }

    @Test
    void storedApproverAccountAndMatchingHashRemainRequired() {
        when(store.forLogin(LocalDemoLogin.COMPANY_CODE, APPROVER_EMAIL)).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "approver")));
        stored(principal(LocalDemoLogin.TENANT_ID, APPROVER_ACCOUNT, APPROVER_EMPLOYEE, Set.of("EMPLOYEE", "APPROVER")), ENCODER.encode("different synthetic credential"));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "approver")));
    }

    @Test
    void ordinaryProfileDoesNotAcceptTheDemoApproverShortcut() {
        provider = new CompanyAuthenticationProvider(store, ENCODER, Clock.systemUTC(), new LocalDemoLogin(new MockEnvironment()));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("approver", "approver")));
        verifyNoInteractions(store);
    }

    @Test
    void rotatingDemoAliasesCannotResetTheExistingRateLimit() {
        for (int i = 0; i < 20; i++) {
            final int attempt = i;
            assertThrows(BadCredentialsException.class, () -> provider.authenticate(credentials("unknown-" + attempt, "wrong")));
        }
        assertThrows(CompanyAuthenticationProvider.LoginRateLimitedException.class,
                () -> provider.authenticate(credentials("approver", "approver")));
    }

    private void stored(TnaPrincipal principal, String hash) {
        when(store.forLogin(LocalDemoLogin.COMPANY_CODE, APPROVER_EMAIL)).thenReturn(Optional.of(new AuthStore.LoginIdentity(principal, hash)));
    }

    private TnaPrincipal principal(UUID tenant, UUID account, UUID employee, Set<String> roles) {
        return new TnaPrincipal(tenant, account, employee, 1L, roles, Instant.now());
    }

    private UsernamePasswordAuthenticationToken credentials(String login, String password) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.240");
        request.addParameter("companyCode", "IGNORED-FOREIGN-COMPANY");
        var token = UsernamePasswordAuthenticationToken.unauthenticated(login, password);
        token.setDetails(new CompanyAuthenticationDetails(request));
        return token;
    }
}
