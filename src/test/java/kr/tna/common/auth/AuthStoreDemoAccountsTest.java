package kr.tna.common.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AuthStoreDemoAccountsTest {
    @Test
    void pickerOffersOnlyPreparedAccountsWithTheCurrentStoredPassword() {
        var store = spy(new AuthStore(mock(DataSource.class), Clock.systemUTC()));
        doReturn(Optional.empty()).when(store).forLogin(anyString(), anyString());
        var persona = LocalDemoLogin.persona("admin").orElseThrow();
        var principal = new TnaPrincipal(LocalDemoLogin.TENANT_ID, persona.accountId(), persona.employeeId(),
                1L, persona.roles(), Instant.now());
        var encoder = PasswordPolicy.passwordEncoder();
        for (String hash : java.util.List.of(encoder.encode("another synthetic password"), "{unsupported}malformed")) {
            doReturn(Optional.of(new AuthStore.LoginIdentity(principal, hash, "99999", "담당자")))
                    .when(store).forLogin(LocalDemoLogin.COMPANY_CODE, persona.email());
            assertTrue(store.demoAccounts().isEmpty());
        }
        doReturn(Optional.of(new AuthStore.LoginIdentity(principal, encoder.encode(persona.password()), "99999", "담당자")))
                .when(store).forLogin(LocalDemoLogin.COMPANY_CODE, persona.email());
        assertEquals("99999", store.demoAccounts().getFirst().employeeNo());
        assertFalse(store.demoAccounts().toString().contains(persona.password()));
    }
}
