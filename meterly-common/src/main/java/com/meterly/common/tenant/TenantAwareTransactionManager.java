package com.meterly.common.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * JDBC transaction manager that binds the RLS tenant at the start of every transaction.
 *
 * <p>Immediately after {@code BEGIN} it runs {@code SELECT set_config('app.tenant_id', ?, true)}. The
 * third argument ({@code is_local = true}) makes this equivalent to {@code SET LOCAL}: the value lives
 * only until COMMIT/ROLLBACK, so it cannot leak to the next borrower of a pooled connection. When no
 * tenant is bound the setting is explicitly cleared to {@code ''}, which the policies treat as "no
 * tenant" and therefore match zero rows: forgetting to bind a tenant fails closed.
 *
 * <p>Consequence for callers: tenant-scoped SQL must run inside a transaction
 * ({@code @Transactional} or {@code TransactionTemplate}). Auto-commit statements outside a
 * transaction see no tenant and return nothing.
 */
public class TenantAwareTransactionManager extends DataSourceTransactionManager {

    static final String BIND_TENANT_SQL = "select set_config('app.tenant_id', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        String tenant = TenantContext.current().map(UUID::toString).orElse("");
        try (PreparedStatement statement = con.prepareStatement(BIND_TENANT_SQL)) {
            statement.setString(1, tenant);
            statement.execute();
        }
    }
}
