package com.meterly.tenant.plan;

import com.meterly.tenant.support.JdbcColumns;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The global plan catalog ({@code plan}, {@code pricing_tier}); not tenant-scoped and read-only. */
@Repository
public class PlanRepository {

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> findActive() {
        List<PlanRow> rows = jdbc.sql("""
                        SELECT id, code, name, base_price, included_units, currency
                        FROM plan
                        WHERE active
                        ORDER BY sort_order, code
                        """)
                .query(PlanRepository::mapRow)
                .list();
        Map<UUID, List<Plan.Tier>> tiers = jdbc.sql("""
                        SELECT t.plan_id, t.from_unit, t.to_unit, t.unit_price
                        FROM pricing_tier t
                        JOIN plan p ON p.id = t.plan_id
                        WHERE p.active
                        ORDER BY t.plan_id, t.from_unit
                        """)
                .query((rs, row) -> Map.entry(JdbcColumns.uuid(rs, "plan_id"), mapTier(rs)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return rows.stream()
                .map(row -> row.withTiers(tiers.getOrDefault(row.id(), List.of())))
                .toList();
    }

    public Optional<Plan> findActiveByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, base_price, included_units, currency
                        FROM plan
                        WHERE code = :code AND active
                        """)
                .param("code", code)
                .query(PlanRepository::mapRow)
                .optional()
                .map(row -> row.withTiers(tiersOf(row.id())));
    }

    /** Any plan, active or retired: existing subscriptions keep referring to retired plans. */
    public Optional<Plan> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, base_price, included_units, currency
                        FROM plan
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(PlanRepository::mapRow)
                .optional()
                .map(row -> row.withTiers(tiersOf(row.id())));
    }

    private List<Plan.Tier> tiersOf(UUID planId) {
        return jdbc.sql("""
                        SELECT from_unit, to_unit, unit_price
                        FROM pricing_tier
                        WHERE plan_id = :planId
                        ORDER BY from_unit
                        """)
                .param("planId", planId)
                .query((rs, row) -> mapTier(rs))
                .list();
    }

    private static PlanRow mapRow(ResultSet rs, int row) throws SQLException {
        return new PlanRow(
                JdbcColumns.uuid(rs, "id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBigDecimal("base_price"),
                rs.getLong("included_units"),
                rs.getString("currency"));
    }

    private static Plan.Tier mapTier(ResultSet rs) throws SQLException {
        return new Plan.Tier(
                rs.getLong("from_unit"),
                JdbcColumns.nullableLong(rs, "to_unit"),
                rs.getBigDecimal("unit_price"));
    }

    private record PlanRow(UUID id, String code, String name, BigDecimal basePrice,
                           long includedUnits, String currency) {

        Plan withTiers(List<Plan.Tier> tiers) {
            return new Plan(id, code, name, basePrice, includedUnits, currency, tiers);
        }
    }
}
