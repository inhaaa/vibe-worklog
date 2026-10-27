package kr.tna;

import java.io.IOException;
import java.util.List;
import kr.tna.common.auth.LocalDemoLogin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoProfileTest {
    @Test
    void explicitPublicDemoEnablesTheReservedSyntheticLogin() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("public-demo");
        assertTrue(new LocalDemoLogin(environment).enabled());
    }

    @Test
    void publicDemoRejectsEveryAdditionalProfile() {
        for (String profile : List.of("local", "local-demo", "production", "default", "staging")) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles("public-demo", profile);
            assertThrows(IllegalArgumentException.class, () -> new LocalDemoLogin(environment), profile);
        }
    }

    @Test
    void defaultPublicDemoDoesNotCountAsExplicitActivation() {
        var environment = new MockEnvironment();
        environment.setDefaultProfiles("public-demo");
        assertThrows(IllegalArgumentException.class, () -> new LocalDemoLogin(environment));
    }

    @Test
    void enabledOperatorCannotStartInPublicDemo() {
        var environment = new MockEnvironment().withProperty("tna.operator.enabled", "true");
        environment.setActiveProfiles("public-demo");
        assertThrows(IllegalArgumentException.class, () -> new LocalDemoLogin(environment));
    }

    @Test
    void publicProfileRequiresSecureCookiesAndKeepsForwardedHeadersDisabledByDefault() throws IOException {
        var resource = new ClassPathResource("application-public-demo.yml");
        assertTrue(resource.exists(), "공개 데모 전용 프로필 설정이 필요합니다.");
        var properties = new YamlPropertySourceLoader().load("public-demo", resource).getFirst();
        assertEquals(true, properties.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("none", properties.getProperty("server.forward-headers-strategy"));
        assertNull(properties.getProperty("tna.operator.enabled"), "운영자 요청을 숨기지 않고 시작을 거부해야 합니다.");
        assertNull(properties.getProperty("spring.datasource.password"));
        assertNull(properties.getProperty("spring.flyway.enabled"));
    }
}
