package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeeImportRows.Row;
import kr.tna.org.EmployeeImportService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class EmployeeImportWebIT {
    @Autowired EmployeeImportService imports;
    JdbcTemplate db;
    UUID tenant;
    kr.tna.common.auth.TnaPrincipal manager;
    UUID team;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일괄 등록')",
                tenant, "IMPORT-" + tenant);
        manager = member("ADMIN", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        team = organization("D10", null);
    }

    static Row row(String id, String no, String name, String joined, String birth, String org) {
        return new Row(id, no, name, joined, birth, org, "사원", "");
    }

    @Test void previewIssuesNumbersAcrossTheBatchAndFlagsDatabaseConflicts() {
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'19001','기존',DATE '2019-01-01')",
                tenant, UUID.randomUUID());
        var preview = imports.preview(manager, List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-05-01", "1991-01-01", "D10"),
                row("c", "19001", "다", "2019-06-01", "1992-01-01", "D10"),
                row("d", "77", "라", "2019-06-01", "1992-01-01", "D10"),
                row("e", "77", "마", "2019-06-01", "1992-01-01", "D10"),
                row("f", "", "바", "2019-06-01", "1992-01-01", "NOPE")));
        assertFalse(preview.valid());
        assertEquals("19002", preview.rows().get(0).employeeNo());
        assertEquals("19003", preview.rows().get(1).employeeNo());
        assertEquals("이미 등록된 사번입니다.", preview.rows().get(2).errors().getFirst().message());
        assertEquals("붙여넣은 행 안에서 사번이 겹칩니다.", preview.rows().get(3).errors().getFirst().message());
        assertEquals("활성 조직 코드가 아닙니다.", preview.rows().get(5).errors().getFirst().message());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant) - 1);
    }

    @Test void previewRejectsEmptyAndOversizedBatches() {
        var tooMany = new java.util.ArrayList<Row>();
        for (int i = 0; i < 501; i++) tooMany.add(row("r" + i, "", "가", "2019-03-04", "1990-01-01", "D10"));
        assertEquals(422, assertThrows(OrgException.class, () -> imports.preview(manager, tooMany)).status());
    }

    private UUID organization(String code, UUID parent) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,parent_id,created_by,updated_by) VALUES (?,?,?,?,?,?,?)",
                tenant, id, code, "원본 " + code, parent, manager.accountId(), manager.accountId());
        return id;
    }

    private kr.tna.common.auth.TnaPrincipal member(String number, java.util.Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new kr.tna.common.auth.TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }
}
