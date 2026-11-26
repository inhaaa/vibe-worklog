package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationSchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private final JdbcTemplate app = new JdbcTemplate(f.appDataSource());
    private final TenantTransactions tx = new TenantTransactions(f.appDataSource());
    private UUID tenant, employee, approver, account, request, event;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); approver = UUID.randomUUID();
        account = UUID.randomUUID(); request = UUID.randomUUID(); event = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 알림 고객사')", tenant, "NOTIFY-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N1','합성 직원',DATE '2026-01-01')", tenant, employee);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N2','합성 승인자',DATE '2026-01-01')", tenant, approver);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')", tenant, account, employee, account + "@example.test");
        admin.update("""
            INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
            VALUES (?,?,?,?,DATE '2026-01-01','합성',?,?)""", tenant, assignment, employee, approver, account, account);
        admin.update("""
            INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,current_approver_id,
              approver_assignment_id,approver_assignment_version,reason,from_date,to_date,client_command_id,attendance_type_version_id)
            SELECT ?,?,?,?,'ATTENDANCE_CORRECTION',?,?,0,'합성',DATE '2026-11-26',DATE '2026-11-26',?,v.id
              FROM tna.attendance_type t JOIN tna.attendance_type_version v ON v.tenant_id=t.tenant_id AND v.type_id=t.id AND v.revision=1
             WHERE t.tenant_id=? AND t.system_kind='ATTENDANCE_CORRECTION'""",
            tenant, request, employee, account, approver, assignment, UUID.randomUUID(), tenant);
        admin.update("""
            INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,to_status,reason,new_approver_id)
            VALUES (?,?,?,'SUBMITTED',?,'PENDING','합성',?)""", tenant, event, request, account, approver);
    }

    private int insert(UUID tenantId, UUID eventId, String kind) {
        return app.update("INSERT INTO tna.notification(tenant_id,id,recipient_employee_id,event_id,request_id,kind) VALUES (?,?,?,?,?,?)",
                tenantId, UUID.randomUUID(), approver, eventId, request, kind);
    }

    @Test void notificationIsTenantScopedWithNarrowGrants() {
        assertTrue(admin.queryForObject("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='tna' AND c.relname='notification'""", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.notification','DELETE')", Boolean.class));
        for (String column : new String[] {"read_at", "mail_state", "mail_attempts", "mail_next_at"})
            assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.notification',?,'UPDATE')", Boolean.class, column), column);
        for (String column : new String[] {"recipient_employee_id", "event_id", "request_id", "kind", "created_at"})
            assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.notification',?,'UPDATE')", Boolean.class, column), column);
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_auth','tna.notification','SELECT')", Boolean.class));

        assertEquals(1, tx.execute(tenant, () -> insert(tenant, event, "APPROVAL_REQUESTED")));
        assertEquals(1, tx.execute(tenant, () -> app.queryForObject("SELECT count(*) FROM tna.notification", Integer.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.notification", Integer.class)));
        assertEquals("PENDING", tx.execute(tenant, () -> app.queryForObject("SELECT mail_state FROM tna.notification", String.class)));
        assertNotNull(tx.execute(tenant, () -> app.queryForObject("SELECT mail_next_at FROM tna.notification", java.sql.Timestamp.class)));
        assertEquals("42501", sqlState(() -> tx.execute(UUID.randomUUID(), () -> insert(tenant, event, "APPROVED"))));
    }

    @Test void oneNotificationPerEventAndValuesAreChecked() {
        tx.execute(tenant, () -> insert(tenant, event, "APPROVAL_REQUESTED"));
        assertEquals("23505", sqlState(() -> tx.execute(tenant, () -> insert(tenant, event, "APPROVED"))));
        UUID other = UUID.randomUUID();
        admin.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,reason) VALUES (?,?,?,'REJECTED',?,'합성')",
                tenant, other, request, account);
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> insert(tenant, other, "WITHDRAWN"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_state='SENT' WHERE event_id=?", event))), "보낸 알림은 다음 시도 시각이 없어야 한다");
        assertEquals(1, tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_state='SENT',mail_next_at=NULL WHERE event_id=?", event)));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_attempts=4 WHERE event_id=?", event))));
    }

    private static String sqlState(Runnable action) {
        try { action.run(); } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException s) return s.getSQLState();
            throw e;
        }
        fail("SQL 오류가 나야 한다");
        return null;
    }
}
