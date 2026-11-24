package kr.tna.common.auth;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Pre-authentication access is deliberately separate from business MyBatis mappers. */
@Repository
public class AuthStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder = PasswordPolicy.passwordEncoder();

    public AuthStore(@Qualifier("authDataSource") DataSource dataSource, Clock clock) {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        this.clock = clock;
    }

    Optional<LoginIdentity> forLogin(String companyCode, String login) {
        return transaction.execute(status -> {
            var tenants = jdbc.query("SELECT id FROM platform.tenant_registry WHERE company_code = ? AND active",
                    (row, index) -> row.getObject("id", UUID.class), companyCode);
            if (tenants.isEmpty()) {
                return Optional.empty();
            }
            UUID tenant = tenants.getFirst();
            setTenant(tenant);
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
            var identities = jdbc.query("""
                    SELECT a.id, a.employee_id, a.auth_version, a.password_hash, e.employee_no, e.display_name
                    FROM tna.tenant_account a
                    JOIN tna.employee e ON e.tenant_id = a.tenant_id AND e.id = a.employee_id
                    WHERE a.tenant_id = ? AND %s = ? AND a.active AND a.activation_expires_at IS NULL AND e.active
                      AND e.joined_on <= ? AND (e.left_on IS NULL OR e.left_on >= ?)
                    """.formatted(login.contains("@") ? "a.email" : "e.employee_no"), (row, index) -> {
                UUID account = row.getObject("id", UUID.class);
                return new LoginIdentity(new TnaPrincipal(tenant, account,
                        row.getObject("employee_id", UUID.class), row.getLong("auth_version"),
                        roles(tenant, account), clock.instant()), row.getString("password_hash"),
                        row.getString("employee_no"), row.getString("display_name"));
            }, tenant, login, today, today);
            return identities.stream().filter(identity -> !identity.principal().roles().isEmpty()).findFirst();
        });
    }

    /** Only explicitly prepared, currently eligible synthetic accounts reach the public demo picker. */
    public List<DemoAccount> demoAccounts() {
        return LocalDemoLogin.personas().stream().flatMap(persona -> forLogin(LocalDemoLogin.COMPANY_CODE, persona.email()).stream()
                .filter(identity -> LocalDemoLogin.matches(persona, identity.principal()))
                .filter(identity -> identity.employeeNo().matches("[0-9]{5}"))
                .filter(identity -> matchesDemoPassword(persona, identity))
                .map(identity -> new DemoAccount(identity.employeeNo(), identity.employeeName(), persona.roles(), persona.password())))
                .toList();
    }

    private boolean matchesDemoPassword(LocalDemoLogin.Persona persona, LoginIdentity identity) {
        try { return encoder.matches(persona.password(), identity.passwordHash()); }
        catch (IllegalArgumentException invalidHash) { return false; }
    }

    public boolean isCurrent(TnaPrincipal principal) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            setTenant(principal.tenantId());
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
            var versions = jdbc.query("""
                    SELECT a.auth_version
                    FROM tna.tenant_account a
                    JOIN tna.employee e ON e.tenant_id = a.tenant_id AND e.id = a.employee_id
                    JOIN platform.tenant_registry r ON r.id = a.tenant_id
                    WHERE a.tenant_id = ? AND a.id = ? AND a.employee_id = ?
                      AND r.active AND a.active AND e.active
                      AND e.joined_on <= ? AND (e.left_on IS NULL OR e.left_on >= ?)
                    """, (row, index) -> row.getLong("auth_version"), principal.tenantId(),
                    principal.accountId(), principal.employeeId(), today, today);
            return versions.size() == 1 && versions.getFirst() == principal.authVersion()
                    && !principal.roles().isEmpty()
                    && roles(principal.tenantId(), principal.accountId()).equals(principal.roles());
        }));
    }

    private Set<String> roles(UUID tenant, UUID account) {
        return Set.copyOf(new HashSet<>(jdbc.query("""
                SELECT role FROM tna.role_grant WHERE tenant_id = ? AND account_id = ? AND active
                """, (row, index) -> row.getString("role"), tenant, account)));
    }

    private void setTenant(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }

    record LoginIdentity(TnaPrincipal principal, String passwordHash, String employeeNo, String employeeName) {
        LoginIdentity(TnaPrincipal principal, String passwordHash) { this(principal, passwordHash, "", ""); }
        @Override public String toString() { return "LoginIdentity[redacted]"; }
    }

    public record DemoAccount(String employeeNo, String employeeName, Set<String> roles, String password) {
        @Override public String toString() { return "DemoAccount[" + employeeNo + "]"; }
    }
}
