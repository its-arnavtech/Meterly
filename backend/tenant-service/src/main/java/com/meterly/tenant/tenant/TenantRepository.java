package com.meterly.tenant.tenant;

import com.meterly.platform.tenant.TenantTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantRepository {
    private final JdbcTemplate jdbcTemplate;

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TenantTransactional
    public void create(
            UUID tenantId,
            String name,
            UUID adminId,
            String adminEmail,
            String passwordHash,
            UUID planId,
            UUID subscriptionId,
            UUID resourceId,
            UUID apiKeyId,
            String apiKeyHash
    ) {
        Integer planCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM plan WHERE id = ?", Integer.class, planId);
        if (planCount == null || planCount != 1) {
            throw new IllegalArgumentException("Unknown plan_id");
        }
        jdbcTemplate.update(
                "INSERT INTO tenant (id, name, status) VALUES (?, ?, 'ACTIVE')", tenantId, name);
        jdbcTemplate.update("""
                INSERT INTO app_user (id, tenant_id, email, role, password_hash)
                VALUES (?, ?, ?, 'TENANT_ADMIN', ?)
                """, adminId, tenantId, adminEmail, passwordHash);
        jdbcTemplate.update("""
                INSERT INTO subscription (id, tenant_id, plan_id, start_date, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, subscriptionId, tenantId, planId, Date.valueOf(LocalDate.now()));
        jdbcTemplate.update("""
                INSERT INTO metered_resource (id, tenant_id, name, unit_label)
                VALUES (?, ?, 'API calls', 'call')
                """, resourceId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO api_key (id, tenant_id, label, key_hash)
                VALUES (?, ?, 'Initial onboarding key', ?)
                """, apiKeyId, tenantId, apiKeyHash);
    }

    @TenantTransactional
    public TenantView currentTenant() {
        return jdbcTemplate.queryForObject("""
                SELECT id, name, status, created_at FROM tenant
                """, (resultSet, rowNumber) -> new TenantView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class)
        ));
    }

    @TenantTransactional
    public TenantView updateCurrent(String name) {
        jdbcTemplate.update("UPDATE tenant SET name = ?", name);
        return currentTenant();
    }

    @TenantTransactional
    public void suspendCurrent() {
        jdbcTemplate.update("UPDATE tenant SET status = 'SUSPENDED'");
    }

    @TenantTransactional
    public List<ResourceView> listResources() {
        return jdbcTemplate.query("""
                SELECT id, name, unit_label FROM metered_resource ORDER BY name
                """, (resultSet, rowNumber) -> new ResourceView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("unit_label")
        ));
    }

    @TenantTransactional
    public List<UUID> listResourceIds() {
        return jdbcTemplate.queryForList("SELECT id FROM metered_resource ORDER BY id", UUID.class);
    }

    @TenantTransactional
    public UserView createUser(
            UUID userId,
            UUID tenantId,
            String email,
            String role,
            String passwordHash
    ) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, tenant_id, email, role, password_hash)
                VALUES (?, ?, ?, ?, ?)
                """, userId, tenantId, email, role, passwordHash);
        return new UserView(userId, email, role);
    }

    @TenantTransactional
    public ApiKeyView createApiKey(
            UUID keyId,
            UUID tenantId,
            String label,
            String keyHash,
            String rawKey
    ) {
        jdbcTemplate.update("""
                INSERT INTO api_key (id, tenant_id, label, key_hash)
                VALUES (?, ?, ?, ?)
                """, keyId, tenantId, label, keyHash);
        return new ApiKeyView(keyId, label, rawKey);
    }

    public Optional<UUID> lookupTenantByApiKeyHash(String keyHash) {
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT api_key_lookup_tenant(?)", UUID.class, keyHash);
        return Optional.ofNullable(tenantId);
    }

    @TenantTransactional
    public PlanView currentPlan() {
        PlanSummary plan = jdbcTemplate.queryForObject("""
                SELECT p.id, p.name, p.base_price, p.included_units
                FROM subscription s
                JOIN plan p ON p.id = s.plan_id
                WHERE s.status = 'ACTIVE'
                  AND s.start_date <= current_date
                  AND (s.end_date IS NULL OR s.end_date >= current_date)
                ORDER BY s.start_date DESC
                LIMIT 1
                """, (resultSet, rowNumber) -> new PlanSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBigDecimal("base_price"),
                resultSet.getLong("included_units")
        ));
        List<PricingTierView> tiers = jdbcTemplate.query("""
                SELECT from_unit, to_unit, unit_price
                FROM pricing_tier WHERE plan_id = ? ORDER BY from_unit
                """, (resultSet, rowNumber) -> new PricingTierView(
                resultSet.getLong("from_unit"),
                (Long) resultSet.getObject("to_unit"),
                resultSet.getBigDecimal("unit_price")
        ), plan.id());
        return new PlanView(plan.id(), plan.name(), plan.basePrice(), plan.includedUnits(), tiers);
    }

    public record TenantView(UUID id, String name, String status, OffsetDateTime createdAt) {
    }

    public record ResourceView(UUID id, String name, String unitLabel) {
    }

    public record UserView(UUID id, String email, String role) {
    }

    public record ApiKeyView(UUID id, String label, String apiKey) {
    }

    public record PricingTierView(long fromUnit, Long toUnit, BigDecimal unitPrice) {
    }

    public record PlanView(
            UUID id,
            String name,
            BigDecimal basePrice,
            long includedUnits,
            List<PricingTierView> tiers
    ) {
    }

    private record PlanSummary(UUID id, String name, BigDecimal basePrice, long includedUnits) {
    }
}
