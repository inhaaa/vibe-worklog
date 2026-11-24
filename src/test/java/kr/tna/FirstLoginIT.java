package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
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

    void login(String employeeNo, String password) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setParameter("companyCode", company);
        var token = UsernamePasswordAuthenticationToken.unauthenticated(employeeNo, password);
        token.setDetails(new CompanyAuthenticationDetails(request));
        provider.authenticate(token);
    }
}
