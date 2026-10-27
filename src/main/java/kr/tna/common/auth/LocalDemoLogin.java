package kr.tna.common.auth;

import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Explicit synthetic identity shared with the separate bootstrap CLI. */
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
    private final boolean publicDemo;

    public LocalDemoLogin(Environment environment) {
        publicDemo = environment.acceptsProfiles(Profiles.of("public-demo"));
        if (publicDemo && (environment.getActiveProfiles().length != 1
                || !"public-demo".equals(environment.getActiveProfiles()[0]))) {
            throw new IllegalArgumentException("public-demo must be the only explicitly active profile");
        }
        if (publicDemo && environment.getProperty("tna.operator.enabled", Boolean.class, false)) {
            throw new IllegalArgumentException("The operator console is unavailable in public-demo");
        }
        if (environment.acceptsProfiles(Profiles.of("local-demo"))
                && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalArgumentException("The local-demo profile requires the local profile");
        }
        enabled = publicDemo || environment.acceptsProfiles(Profiles.of("local & local-demo"));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean publicDemo() {
        return publicDemo;
    }
}
