package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import kr.tna.org.OrgException;
import kr.tna.common.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("local")
class FirstLoginIT {
    static final String OLD = "Synthetic first login old 43!", NEW = "Synthetic first login new 43!";
    @Autowired CompanyAuthenticationProvider provider;
    @Autowired FirstLoginService firstLogin;
    JdbcTemplate db; UUID tenant, employee, account; String company;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start(); p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER); p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl); p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER); p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID();
        company = ("FIRST-" + tenant).toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 최초 접속')", tenant, company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'2019031','합성 사원',DATE '2019-03-04')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,password_hash,active) VALUES (?,?,?,?,true)",
                tenant, account, employee, PasswordPolicy.passwordEncoder().encode(OLD));
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)", tenant, employee, account);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
    }

    @Test void reopenedAccountRejectsPreviousPassword() {
        assertDoesNotThrow(() -> login("2019031", OLD));
        db.update("UPDATE tna.tenant_account SET activation_expires_at=now()+interval '7 days' WHERE id=?", account);
        assertThrows(AuthenticationException.class, () -> login("2019031", OLD));
    }

    void pending() {
        db.update("UPDATE tna.tenant_account SET active=false,password_hash=NULL,activation_expires_at=now()+interval '7 days' WHERE id=?", account);
    }
    int failures() { return db.queryForObject("SELECT activation_failures FROM tna.tenant_account WHERE id=?", Integer.class, account); }
    void rejected(Runnable action) { assertEquals(422, assertThrows(OrgException.class, action::run).status()); }
    void activate(LocalDate birth, String password) { firstLogin.activate(company, "2019031", birth, password, "198.51.100." + failures()); }

    @Test void matchingBirthDateSetsPasswordAndEndsPendingState() {
        pending();
        activate(LocalDate.of(1990, 5, 12), NEW);
        assertDoesNotThrow(() -> login("2019031", NEW));
        assertNull(db.queryForObject("SELECT activation_expires_at FROM tna.tenant_account WHERE id=?", java.sql.Timestamp.class, account));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_ACTIVATED'", Integer.class, tenant));
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
    }

    @Test void wrongBirthDateCountsAndLocksAtFive() {
        pending();
        for (int i = 1; i <= 5; i++) { rejected(() -> activate(LocalDate.of(1990, 5, 13), NEW)); assertEquals(i, failures()); }
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        assertEquals(5, failures());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_LOCKED'", Integer.class, tenant));
        String audit = db.queryForObject("SELECT string_agg(before_state::text||after_state::text||input_hash,'') FROM tna.org_audit_event WHERE tenant_id=?", String.class, tenant);
        assertFalse(audit.contains("1990-05-1"));
    }

    @Test void expiredMissingBirthDateFormerEmployeeAndEmployeeNumberPasswordAreRejected() {
        pending();
        rejected(() -> activate(LocalDate.of(1990, 5, 12), "2019031"));
        db.update("UPDATE tna.tenant_account SET activation_expires_at=now()-interval '1 second' WHERE id=?", account);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        pending(); db.update("UPDATE tna.employee_personnel SET birth_date=NULL WHERE employee_id=?", employee);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        db.update("UPDATE tna.employee_personnel SET birth_date=DATE '1990-05-12' WHERE employee_id=?", employee);
        db.update("UPDATE tna.employee SET left_on=DATE '2020-01-01' WHERE id=?", employee);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        assertEquals(0, failures());
    }

    void login(String employeeNo, String password) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setParameter("companyCode", company);
        var token = UsernamePasswordAuthenticationToken.unauthenticated(employeeNo, password);
        token.setDetails(new CompanyAuthenticationDetails(request));
        provider.authenticate(token);
    }
}
