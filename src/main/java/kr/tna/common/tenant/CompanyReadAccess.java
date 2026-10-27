package kr.tna.common.tenant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Company-wide reads have their own scope and never grant administrative mutation authority. */
@Component
public final class CompanyReadAccess {
    private final TenantTransactions transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CompanyReadAccess(TenantTransactions transactions, @Qualifier("dataSource") DataSource source, Clock clock) {
        this.transactions = transactions;
        this.jdbc = new JdbcTemplate(source);
        this.clock = clock;
    }

    public <T> T read(TnaPrincipal actor, Supplier<T> action) {
        if (actor == null || !actor.roles().contains("EMPLOYEE")
                || !actor.roles().contains("ATTENDANCE_ADMIN") && !actor.roles().contains("COMPANY_REPRESENTATIVE")) {
            throw OrgException.forbidden();
        }
        try {
            return transactions.execute(actor.tenantId(), () -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(? AS text),0))",
                        Object.class, actor.tenantId());
                requireCurrent(actor);
                T result = action.get();
                requireCurrent(actor);
                return result;
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw new OrgException(503, "회사 자료를 확인할 수 없습니다. 잠시 후 다시 조회해 주세요.");
        }
    }

    private void requireCurrent(TnaPrincipal actor) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        int allowed = jdbc.queryForObject("""
                SELECT count(*) FROM tna.tenant_account a
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                JOIN platform.tenant_registry r ON r.id=a.tenant_id
                WHERE a.tenant_id=? AND a.id=? AND a.employee_id=? AND a.auth_version=?
                  AND a.active AND a.credential_set AND e.active AND r.active
                  AND e.joined_on<=? AND (e.left_on IS NULL OR e.left_on>=?)
                  AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id
                      AND g.active AND g.role='EMPLOYEE')
                  AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id
                      AND g.active AND ((g.role='ATTENDANCE_ADMIN' AND ?) OR (g.role='COMPANY_REPRESENTATIVE' AND ?)))
                """, Integer.class, actor.tenantId(), actor.accountId(), actor.employeeId(), actor.authVersion(),
                today, today, actor.roles().contains("ATTENDANCE_ADMIN"), actor.roles().contains("COMPANY_REPRESENTATIVE"));
        if (allowed != 1) throw OrgException.forbidden();
    }
}
