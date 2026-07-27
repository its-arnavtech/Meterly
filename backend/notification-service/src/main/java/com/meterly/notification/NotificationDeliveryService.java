package com.meterly.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class NotificationDeliveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public NotificationDeliveryService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public boolean deliver(UUID eventId, UUID tenantId, String eventType, String summary) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "select set_config('app.tenant_id', ?, true)",
                    String.class,
                    tenantId.toString()
            );
            int inserted = jdbcTemplate.update("""
                    INSERT INTO notification_delivery
                        (event_id, tenant_id, event_type, payload_summary)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, tenantId, eventType, summary);
            if (inserted == 1) {
                meterRegistry.counter(
                        "meterly.notification.delivered",
                        "tenant_id", tenantId.toString(),
                        "event_type", eventType
                ).increment();
                LOGGER.info(
                        "Stub notification delivered event_id={} tenant_id={} event_type={} summary={}",
                        eventId,
                        tenantId,
                        eventType,
                        summary
                );
            } else {
                LOGGER.info("Duplicate notification suppressed event_id={} tenant_id={}", eventId, tenantId);
            }
            return inserted == 1;
        }));
    }
}
