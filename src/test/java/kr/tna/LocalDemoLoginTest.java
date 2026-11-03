package kr.tna;

import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class LocalDemoLoginTest {
    @Test
    void allSyntheticTestAccountsHaveDistinctFixedIdsAndUsableDemoPasswords() {
        var personas = LocalDemoLogin.personas();
        assertEquals(5, personas.size());
        assertEquals(5, personas.stream().map(LocalDemoLogin.Persona::employeeId).distinct().count());
        assertEquals(5, personas.stream().map(LocalDemoLogin.Persona::accountId).distinct().count());
        assertTrue(personas.stream().allMatch(persona -> persona.password().length() >= 5));
        assertEquals(personas.stream().map(LocalDemoLogin.Persona::employeeId).collect(java.util.stream.Collectors.toSet()),
                LocalDemoLogin.SHARED_EMPLOYEE_IDS);
    }

    @Test
    void ordinaryAndLocalProfilesKeepStrongPasswordPolicy() {
        assertFalse(mode().enabled());
        assertFalse(mode("local").enabled());
        assertFalse(PasswordPolicy.isValid(LocalDemoLogin.PASSWORD));
    }

    @Test
    void bothExplicitProfilesEnableDemoRegardlessOfOrder() {
        assertTrue(mode("local", "local-demo").enabled());
        assertTrue(mode("local-demo", "local").enabled());
    }

    @Test
    void demoWithoutLocalFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> mode("local-demo"));
        assertThrows(IllegalArgumentException.class, () -> mode("production", "local-demo"));
    }

    private LocalDemoLogin mode(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new LocalDemoLogin(environment);
    }
}
