package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.console.LocalDemoBootstrap;
import kr.tna.org.AccountData.InvitationForm;
import kr.tna.org.AccountData.RolesForm;
import kr.tna.org.AccountService;
import kr.tna.org.BulkData;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgBulkService;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("public-demo")
class PublicDemoIdentityGuardIT extends DemoIdentityServiceFixture {
    @Test
    void theSharedApproverUsesTheSameSingleAndBulkServiceProtection() {
        UUID employee = LocalDemoLogin.APPROVER_EMPLOYEE_ID;
        assertRefusedWithoutChanges(403, () -> employees.update(actor, employee, changedEmployee()));
        assertRefusedWithoutChanges(403, () -> accounts.roles(actor, employee, changedRoles()));
        assertRefusedWithoutChanges(403, () -> accounts.invite(actor, employee, invitation()));
        assertRefusedWithoutChanges(403, () -> bulk.employees(actor,
                employeeBatch(List.of(employeeRow(employee, "DEMO-001", BulkData.Operation.DEACTIVATE)))));
    }

    @Test
    void employeeChangesAreRejectedByUuidWithoutChangingDataOrAudit() {
        assertRefusedWithoutChanges(403, () -> employees.update(actor, LocalDemoLogin.EMPLOYEE_ID, changedEmployee()));
    }

    @Test
    void roleChangesAreRejectedByUuidWithoutChangingDataOrAudit() {
        assertRefusedWithoutChanges(403, () -> accounts.roles(actor, LocalDemoLogin.EMPLOYEE_ID, changedRoles()));
    }

    @Test
    void reinvitationIsRejectedByUuidBeforeTheNormalAccountValidation() {
        assertRefusedWithoutChanges(403, () -> accounts.invite(actor, LocalDemoLogin.EMPLOYEE_ID, invitation()));
    }

    @Test
    void employeeBulkCannotDeactivateTheSharedIdentity() {
        assertRefusedWithoutChanges(403, () -> bulk.employees(actor, employeeBatch(List.of(
                employeeRow(LocalDemoLogin.EMPLOYEE_ID, "LOCAL-DEMO", BulkData.Operation.DEACTIVATE)))));
    }

    @Test
    void employeeBulkRejectsTheEntireMixedBatchBeforeAnyEmployeeOrAuditChanges() {
        assertRefusedWithoutChanges(403, () -> bulk.employees(actor, employeeBatch(List.of(
                employeeRow(null, "NEW-SYNTHETIC", BulkData.Operation.CREATE),
                employeeRow(LocalDemoLogin.EMPLOYEE_ID, "LOCAL-DEMO", BulkData.Operation.UPDATE)))));
    }
}

@SpringBootTest
@ActiveProfiles({"local", "local-demo"})
class LocalDemoIdentityGuardIT extends DemoIdentityServiceFixture {
    @Test
    void localDemoStillAllowsEmployeeNameChanges() {
        employees.update(actor, LocalDemoLogin.EMPLOYEE_ID, changedEmployee());
        assertEquals("수정한 합성 담당자", employees.get(actor, LocalDemoLogin.EMPLOYEE_ID).displayName());
    }

    @Test
    void localDemoStillAllowsRoleChanges() {
        accounts.roles(actor, LocalDemoLogin.EMPLOYEE_ID, changedRoles());
        assertEquals(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN", "APPROVER"),
                accounts.get(actorWithVersion(2), LocalDemoLogin.EMPLOYEE_ID).roles());
    }

    @Test
    void localDemoKeepsTheExistingAcceptedAccountInvitationRule() {
        assertRefusedWithoutChanges(422, () -> accounts.invite(actor, LocalDemoLogin.EMPLOYEE_ID, invitation()));
    }

    @Test
    void localDemoStillAllowsTheSharedEmployeeThroughTheBulkEditor() {
        bulk.employees(actor, employeeBatch(List.of(
                employeeRow(LocalDemoLogin.EMPLOYEE_ID, "LOCAL-DEMO", BulkData.Operation.UPDATE))));
        assertEquals("수정한 합성 담당자", employees.get(actor, LocalDemoLogin.EMPLOYEE_ID).displayName());
    }
}

abstract class DemoIdentityServiceFixture {
    @Autowired EmployeeService employees;
    @Autowired AccountService accounts;
    @Autowired OrgBulkService bulk;
    JdbcTemplate database;
    TnaPrincipal actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void seedOnlyTheReservedSyntheticIdentity() throws Exception {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        LocalDemoBootstrap.bootstrap(DatabaseFixture.start().migrationDataSource());
        actor = actorWithVersion(1);
    }

    @AfterEach
    void clearOnlyTheReservedSyntheticIdentity() {
        database.update("UPDATE tna.employee SET created_by=NULL,updated_by=NULL WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        for (String table : List.of("tna.attendance_type_version", "tna.attendance_type", "tna.common_code", "tna.common_code_group",
                "tna.org_audit_event", "tna.account_token", "tna.role_grant",
                "tna.tenant_account", "tna.employee")) {
            database.update("DELETE FROM " + table + " WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        }
        database.update("DELETE FROM platform.tenant_registry WHERE id=?", LocalDemoLogin.TENANT_ID);
    }

    EmployeeForm changedEmployee() {
        return new EmployeeForm("LOCAL-DEMO", "수정한 합성 담당자", LocalDate.of(2026, 1, 1),
                null, true, 0L, "합성 담당자 수정", UUID.randomUUID());
    }

    RolesForm changedRoles() {
        return new RolesForm(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN", "APPROVER"),
                0L, "합성 담당자 역할 변경", UUID.randomUUID());
    }

    InvitationForm invitation() {
        return new InvitationForm(LocalDemoLogin.EMAIL, 0L, "합성 담당자 재초대", UUID.randomUUID());
    }

    BulkData.Form<BulkData.EmployeeRow> employeeBatch(List<BulkData.EmployeeRow> rows) {
        return new BulkData.Form<>(UUID.randomUUID(), "공유 합성 사원 일괄 변경 검증", rows);
    }

    BulkData.EmployeeRow employeeRow(UUID employeeId, String number, BulkData.Operation operation) {
        return new BulkData.EmployeeRow(UUID.randomUUID().toString(), employeeId, employeeId == null ? null : 0L,
                operation, number, "수정한 합성 담당자", LocalDate.of(2026, 1, 1), null, true);
    }

    TnaPrincipal actorWithVersion(long version) {
        return new TnaPrincipal(LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID,
                LocalDemoLogin.EMPLOYEE_ID, version, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }

    void assertRefusedWithoutChanges(int status, Runnable operation) {
        String before = snapshot();
        assertEquals(status, assertThrows(OrgException.class, operation::run).status());
        assertEquals(before, snapshot());
    }

    private String snapshot() {
        return List.of(database.queryForList("SELECT * FROM platform.tenant_registry WHERE id=?", LocalDemoLogin.TENANT_ID),
                database.queryForList("SELECT * FROM tna.employee WHERE tenant_id=? ORDER BY id", LocalDemoLogin.TENANT_ID),
                database.queryForList("SELECT * FROM tna.tenant_account WHERE tenant_id=? ORDER BY id", LocalDemoLogin.TENANT_ID),
                database.queryForList("SELECT * FROM tna.role_grant WHERE tenant_id=? ORDER BY account_id,role", LocalDemoLogin.TENANT_ID),
                database.queryForList("SELECT * FROM tna.account_token WHERE tenant_id=? ORDER BY id", LocalDemoLogin.TENANT_ID),
                database.queryForList("SELECT * FROM tna.org_audit_event WHERE tenant_id=? ORDER BY id", LocalDemoLogin.TENANT_ID)).toString();
    }
}
