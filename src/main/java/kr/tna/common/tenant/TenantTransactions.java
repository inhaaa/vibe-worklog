package kr.tna.common.tenant;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Binds an authenticated tenant to the current database transaction only. */
public final class TenantTransactions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TenantTransactions(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public <T> T execute(UUID tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "An authenticated tenant is required");
        Objects.requireNonNull(action, "A tenant-scoped operation is required");
        return transactions.execute(status -> {
            String tenant = tenantId.toString();
            String current = jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
            if (current != null && !current.isEmpty() && !current.equals(tenant)) {
                throw new IllegalStateException("A transaction cannot switch tenants");
            }
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
            return action.get();
        });
    }
}
