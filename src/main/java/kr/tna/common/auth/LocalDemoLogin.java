package kr.tna.common.auth;

import java.util.Optional;
import java.util.List;
import java.util.Set;
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
    public static final String APPROVER_EMAIL = "approver@local.example.test";
    public static final String APPROVER_LOGIN = "approver";
    public static final String APPROVER_PASSWORD = "approver";
    public static final UUID APPROVER_EMPLOYEE_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0201");
    public static final UUID APPROVER_ACCOUNT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0211");
    private static final Persona APPLICANT = new Persona(LOGIN, PASSWORD, EMAIL, EMPLOYEE_ID, ACCOUNT_ID,
            Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    private static final Persona APPROVER = new Persona(APPROVER_LOGIN, APPROVER_PASSWORD, APPROVER_EMAIL,
            APPROVER_EMPLOYEE_ID, APPROVER_ACCOUNT_ID, Set.of("EMPLOYEE", "APPROVER"));
    private static final List<Persona> PERSONAS = List.of(APPLICANT, APPROVER,
            new Persona("employee", "employee", "employee@local.example.test",
                    UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0202"), UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0212"), Set.of("EMPLOYEE")),
            new Persona("representative", "representative", "representative@local.example.test",
                    UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0203"), UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0213"),
                    Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE", "APPROVER")),
            new Persona("dual", "dualdemo", "dual@local.example.test",
                    UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0204"), UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0214"),
                    Set.of("EMPLOYEE", "ATTENDANCE_ADMIN", "APPROVER")));
    public static final Set<UUID> SHARED_EMPLOYEE_IDS = PERSONAS.stream().map(Persona::employeeId).collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    public static Optional<Persona> persona(String login) {
        return PERSONAS.stream().filter(persona -> persona.login().equals(login)).findFirst();
    }

    public static List<Persona> personas() { return PERSONAS; }

    public static boolean matches(Persona persona, TnaPrincipal principal) {
        return TENANT_ID.equals(principal.tenantId()) && persona.accountId().equals(principal.accountId())
                && persona.employeeId().equals(principal.employeeId()) && persona.roles().equals(principal.roles());
    }

    public record Persona(String login, String password, String email, UUID employeeId, UUID accountId, Set<String> roles) {
        public Persona { roles = Set.copyOf(roles); }
        @Override public String toString() { return "DemoPersona[" + login + "]"; }
    }
}
