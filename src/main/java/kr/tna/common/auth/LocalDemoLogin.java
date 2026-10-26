package kr.tna.common.auth;

import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Explicit local-only synthetic identity shared with the separate bootstrap CLI. */
@Component
public final class LocalDemoLogin {
    public static final String COMPANY_CODE = "TNA-LOCAL-DEMO";
    public static final String EMAIL = "admin@local.example.test";
    public static final String LOGIN = "admin";
    public static final String PASSWORD = "admin";
    public static final UUID TENANT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    public static final UUID EMPLOYEE_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd1");
    public static final UUID ACCOUNT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd2");
    private final boolean enabled;

    public LocalDemoLogin(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local-demo"))
                && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalArgumentException("The local-demo profile requires the local profile");
        }
        enabled = environment.acceptsProfiles(Profiles.of("local & local-demo"));
    }

    public boolean enabled() {
        return enabled;
    }
}
