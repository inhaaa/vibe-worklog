package kr.tna.common.auth;

import java.util.Optional;
import java.util.Set;
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
    public static final String APPROVER_EMAIL = "approver@local.example.test";
    public static final String APPROVER_LOGIN = "approver";
    public static final String APPROVER_PASSWORD = "approver";
    public static final UUID APPROVER_EMPLOYEE_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0201");
    public static final UUID APPROVER_ACCOUNT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0211");
    public static final Set<UUID> SHARED_EMPLOYEE_IDS = Set.of(EMPLOYEE_ID, APPROVER_EMPLOYEE_ID);
    private static final Persona APPLICANT = new Persona(LOGIN, PASSWORD, EMAIL, EMPLOYEE_ID, ACCOUNT_ID,
            Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    private static final Persona APPROVER = new Persona(APPROVER_LOGIN, APPROVER_PASSWORD, APPROVER_EMAIL,
            APPROVER_EMPLOYEE_ID, APPROVER_ACCOUNT_ID, Set.of("EMPLOYEE", "APPROVER"));
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

    public static Optional<Persona> persona(String login) {
        return LOGIN.equals(login) ? Optional.of(APPLICANT)
                : APPROVER_LOGIN.equals(login) ? Optional.of(APPROVER) : Optional.empty();
    }

    public record Persona(String login, String password, String email, UUID employeeId, UUID accountId, Set<String> roles) {
        public Persona { roles = Set.copyOf(roles); }
        @Override public String toString() { return "DemoPersona[" + login + "]"; }
    }
}
