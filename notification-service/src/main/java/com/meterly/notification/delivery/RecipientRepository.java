package com.meterly.notification.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notification recipients: the tenant's active administrators. Callers must hold a tenant-bound transaction. */
@Repository
public class RecipientRepository {

    private final JdbcClient jdbc;

    public RecipientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Emails of ACTIVE users with role TENANT_ADMIN. RLS restricts the rows to the bound tenant. */
    public List<String> activeAdminEmails(UUID tenantId) {
        return jdbc.sql("""
                        SELECT email
                          FROM app_user
                         WHERE tenant_id = :tenantId
                           AND role = 'TENANT_ADMIN'
                           AND status = 'ACTIVE'
                         ORDER BY lower(email)
                        """)
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
    }
}
