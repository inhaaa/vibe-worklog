package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgData.Organization;
import kr.tna.org.OrganizationAdminMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class UuidMappingIT {
    @Autowired SqlSessionFactory sessions;
    @Autowired TenantTransactions transactions;
    @Autowired EmployeeAdminMapper employees;
    @Autowired OrganizationAdminMapper organizations;
    @Autowired @Qualifier("dataSource") DataSource application;

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
    void syntheticIdentities() {
        DatabaseFixture.start().seedIdentity("synthetic-uuid-mapping-not-a-login-hash");
    }

    @Test
    void registersAnExplicitUuidHandlerWithoutAGlobalOtherFallback() {
        var registry = sessions.getConfiguration().getTypeHandlerRegistry();
        var handler = registry.getTypeHandler(UUID.class, JdbcType.OTHER);

        assertNotNull(handler, "PostgreSQL UUID parameters need an explicit handler after MyBatis 3.6");
        assertSame(handler, registry.getTypeHandler(UUID.class, null));
        assertNull(registry.getTypeHandler(JdbcType.OTHER));
    }

    @Test
    void employeeUuidParametersAndRecordResultsKeepTheApplicationTenantBoundary() {
        var jdbc = new JdbcTemplate(application);
        transactions.execute(DatabaseFixture.TENANT_A, () -> {
            assertEquals(DatabaseFixture.APP_USER, jdbc.queryForObject("SELECT current_user", String.class));
            var employee = employees.get(DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
            assertNotNull(employee);
            assertEquals(DatabaseFixture.EMPLOYEE_A, employee.id());
            assertEquals(UUID.class, employee.id().getClass());
            assertNull(employees.get(DatabaseFixture.TENANT_B, DatabaseFixture.EMPLOYEE_B));
            return null;
        });
        assertNull(employees.get(DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A));
    }

    @Test
    void organizationUuidWritesAndNullableRecordFieldsPreserveThePostgresUuidType() {
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T02:03:04.567Z");
        var jdbc = new JdbcTemplate(application);
        transactions.execute(DatabaseFixture.TENANT_A, () -> {
            assertEquals(1, organizations.insert(DatabaseFixture.TENANT_A,
                    new Organization(parent, "U-" + parent, "합성 상위 조직", null, true, 0),
                    DatabaseFixture.ACCOUNT_A, createdAt));
            assertNull(organizations.find(DatabaseFixture.TENANT_A, parent).parentId());
            assertEquals(1, organizations.insert(DatabaseFixture.TENANT_A,
                    new Organization(child, "U-" + child, "합성 하위 조직", parent, true, 0),
                    DatabaseFixture.ACCOUNT_A, createdAt));
            assertEquals(parent, organizations.find(DatabaseFixture.TENANT_A, child).parentId());
            assertEquals(1, organizations.update(DatabaseFixture.TENANT_A,
                    new Organization(child, "U-" + child, "합성 하위 조직", null, true, 0),
                    DatabaseFixture.ACCOUNT_A));
            assertNull(organizations.find(DatabaseFixture.TENANT_A, child).parentId());
            jdbc.query("SELECT id, parent_id FROM tna.organization WHERE tenant_id=? AND id=?", rows -> {
                assertEquals(Types.OTHER, rows.getMetaData().getColumnType(1));
                assertEquals(Types.OTHER, rows.getMetaData().getColumnType(2));
                assertEquals(child, rows.getObject(1, UUID.class));
                assertNull(rows.getObject(2, UUID.class));
            }, DatabaseFixture.TENANT_A, child);
            return null;
        });
    }
}
