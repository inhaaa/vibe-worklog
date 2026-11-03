package kr.tna;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.org.PublicDemoIdentityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoIdentityGuardTest {
    private static final UUID APPROVER = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0201");

    @Test
    void bothSharedPersonasAreImmutableInTheReservedPublicTenant() {
        var guard = guard("public-demo");
        assertDoesNotThrow(() -> guard.requireMutable(actor(LocalDemoLogin.TENANT_ID), null));
        for (UUID employee : Set.of(LocalDemoLogin.EMPLOYEE_ID, APPROVER)) {
            assertEquals(403, assertThrows(OrgException.class,
                    () -> guard.requireMutable(actor(LocalDemoLogin.TENANT_ID), employee)).status());
        }
    }

    @Test
    void guardDoesNotExpandToUnrelatedEmployeesOrOtherTenants() {
        var guard = guard("public-demo");
        assertDoesNotThrow(() -> guard.requireMutable(actor(LocalDemoLogin.TENANT_ID), UUID.randomUUID()));
        assertDoesNotThrow(() -> guard.requireMutable(actor(UUID.randomUUID()), APPROVER));
    }

    @Test
    void ordinaryAndExplicitLocalDemoKeepTheirExistingMutationRules() {
        for (var guard : java.util.List.of(guard(), guard("local"), guard("local", "local-demo"))) {
            assertDoesNotThrow(() -> guard.requireMutable(actor(LocalDemoLogin.TENANT_ID), APPROVER));
            assertDoesNotThrow(() -> guard.requireMutable(actor(LocalDemoLogin.TENANT_ID), LocalDemoLogin.EMPLOYEE_ID));
        }
    }

    private PublicDemoIdentityGuard guard(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new PublicDemoIdentityGuard(new LocalDemoLogin(environment));
    }

    private TnaPrincipal actor(UUID tenant) {
        return new TnaPrincipal(tenant, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.EMPLOYEE_ID,
                1L, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }
}
