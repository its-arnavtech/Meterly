package com.meterly.tenant.resource;

import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code metered_resource} table (RLS-scoped). Names are unique per tenant. */
@Repository
public class ResourceRepository {

    public static final String DEFAULT_NAME = "API calls";
    public static final String DEFAULT_UNIT_LABEL = "call";

    private final JdbcClient jdbc;

    public ResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID tenantId, String name, String unitLabel) {
        jdbc.sql("""
                        INSERT INTO metered_resource (id, tenant_id, name, unit_label)
                        VALUES (:id, :tenantId, :name, :unitLabel)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("unitLabel", unitLabel)
                .update();
    }

    public Optional<Resource> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, unit_label, status, created_at
                        FROM metered_resource
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(ResourceRepository::map)
                .optional();
    }

    public List<Resource> list() {
        return jdbc.sql("""
                        SELECT id, name, unit_label, status, created_at
                        FROM metered_resource
                        ORDER BY created_at, id
                        """)
                .query(ResourceRepository::map)
                .list();
    }

    public List<UUID> activeIds() {
        return jdbc.sql("""
                        SELECT id
                        FROM metered_resource
                        WHERE status = 'ACTIVE'
                        ORDER BY created_at, id
                        """)
                .query(UUID.class)
                .list();
    }

    public void update(UUID id, String name, String unitLabel, Resource.ResourceStatus status) {
        jdbc.sql("""
                        UPDATE metered_resource
                        SET name = :name, unit_label = :unitLabel, status = :status
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("unitLabel", unitLabel)
                .param("status", status.name())
                .update();
    }

    private static Resource map(ResultSet rs, int row) throws SQLException {
        return new Resource(
                JdbcColumns.uuid(rs, "id"),
                rs.getString("name"),
                rs.getString("unit_label"),
                Resource.ResourceStatus.valueOf(rs.getString("status")),
                JdbcColumns.instant(rs, "created_at"));
    }
}
