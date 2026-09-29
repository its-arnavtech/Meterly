package com.meterly.metering.threshold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.NotificationEvent.UsageThresholdCrossed;
import com.meterly.common.event.Topics;
import com.meterly.metering.catalog.TenantCatalogRepository;
import com.meterly.metering.catalog.TenantCatalogRepository.PlanSummary;
import com.meterly.metering.counter.CounterKey;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Emits {@link UsageThresholdCrossed} exactly once per (tenant, resource, period, threshold) when a
 * resource's open-period total reaches 80% / 100% of the plan's included units.
 *
 * <p>Must run inside the transaction that changed the counter (holding its advisory lock): the
 * {@code usage_threshold} marker and the {@code outbox_event} row commit or roll back together with
 * the ledger row, and the marker's primary key makes a second emission impossible. Evaluating
 * "total reached the threshold" (not "this event crossed it") also heals a crossing missed earlier,
 * e.g. after reconciliation corrected a counter.
 */
@Component
public class ThresholdEvaluator {

    /** Threshold percentages; must match the CHECK constraint on {@code usage_threshold}. */
    public static final List<Integer> THRESHOLD_PERCENTS = List.of(80, 100);

    /** A threshold newly crossed in this transaction; {@code eventId} is also the outbox row id. */
    public record Crossing(int percent, UUID eventId) {
    }

    private final JdbcClient jdbc;
    private final TenantCatalogRepository catalog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ThresholdEvaluator(JdbcClient jdbc, TenantCatalogRepository catalog, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<Crossing> evaluate(CounterKey key, long totalUnits) {
        if (!key.period().equals(BillingPeriod.current(clock))) {
            return List.of();
        }
        Optional<PlanSummary> plan = catalog.plan(key.tenantId(), key.period());
        if (plan.isEmpty() || plan.get().includedUnits() <= 0) {
            return List.of();
        }
        long includedUnits = plan.get().includedUnits();
        List<Crossing> crossings = new ArrayList<>();
        for (int percent : THRESHOLD_PERCENTS) {
            if (reached(totalUnits, includedUnits, percent)) {
                UUID eventId = UUID.randomUUID();
                if (markCrossed(key, percent, eventId)) {
                    writeOutbox(key, percent, eventId, totalUnits, includedUnits);
                    crossings.add(new Crossing(percent, eventId));
                }
            }
        }
        return crossings;
    }

    /** {@code totalUnits >= includedUnits * percent / 100}, exact for any bigint values. */
    static boolean reached(long totalUnits, long includedUnits, int percent) {
        BigInteger scaledTotal = BigInteger.valueOf(totalUnits).multiply(BigInteger.valueOf(100));
        BigInteger scaledThreshold = BigInteger.valueOf(includedUnits).multiply(BigInteger.valueOf(percent));
        return scaledTotal.compareTo(scaledThreshold) >= 0;
    }

    private boolean markCrossed(CounterKey key, int percent, UUID eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO usage_threshold (tenant_id, resource_id, period_start, threshold_percent, event_id)
                        VALUES (:tenantId, :resourceId, :periodStart, :percent, :eventId)
                        ON CONFLICT (tenant_id, resource_id, period_start, threshold_percent) DO NOTHING
                        """)
                .param("tenantId", key.tenantId())
                .param("resourceId", key.resourceId())
                .param("periodStart", key.period().start())
                .param("percent", percent)
                .param("eventId", eventId)
                .update();
        return inserted == 1;
    }

    private void writeOutbox(CounterKey key, int percent, UUID eventId, long totalUnits, long includedUnits) {
        String resourceName = catalog.resource(key.tenantId(), key.resourceId())
                .map(TenantCatalogRepository.Resource::name)
                .orElse("Unknown resource");
        NotificationEvent event = new UsageThresholdCrossed(eventId, key.tenantId(), clock.instant(),
                key.resourceId(), resourceName, key.period().start(), percent, totalUnits, includedUnits);
        String payload;
        try {
            payload = objectMapper.writerFor(NotificationEvent.class).writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("UsageThresholdCrossed is not serializable", e);
        }
        jdbc.sql("""
                        INSERT INTO outbox_event (id, tenant_id, topic, event_key, event_type, payload)
                        VALUES (:id, :tenantId, :topic, :eventKey, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("tenantId", key.tenantId())
                .param("topic", Topics.NOTIFICATION_EVENTS)
                .param("eventKey", key.tenantId().toString())
                .param("eventType", NotificationEvent.USAGE_THRESHOLD_CROSSED)
                .param("payload", payload)
                .update();
    }
}
