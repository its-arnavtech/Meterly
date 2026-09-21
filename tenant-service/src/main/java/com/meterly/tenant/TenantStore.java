package com.meterly.tenant;

import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit SQL only. Every tenant operation enters through inTenant before starting a transaction. */
@Component
public class TenantStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    public TenantStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }
    public <T> T inTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> tx.execute(status -> work.get()));
    }
    public List<Map<String,Object>> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, index) -> row(rs)).list();
    }
    public Optional<Map<String,Object>> optional(String sql, Object... args) {
        return rows(sql,args).stream().findFirst();
    }
    public Map<String,Object> required(String sql, String code, Object... args) {
        return optional(sql,args).orElseThrow(() -> ApiException.notFound(code,"Resource not found"));
    }
    public int update(String sql, Object... args) { return jdbc.sql(sql).params(args).update(); }
    public void lockTenant() {
        required("select id from tenant where id=? for update", "TENANT_NOT_FOUND", TenantContext.require());
    }
    public static UUID uuid(Map<String,Object> row, String name) { return (UUID) row.get(name); }
    private static Map<String,Object> row(ResultSet rs) throws SQLException {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i=1;i<=rs.getMetaData().getColumnCount();i++) {
            Object value = rs.getObject(i);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
            if (value instanceof java.sql.Date date) value = date.toLocalDate();
            row.put(rs.getMetaData().getColumnLabel(i), value);
        }
        return row;
    }
}
