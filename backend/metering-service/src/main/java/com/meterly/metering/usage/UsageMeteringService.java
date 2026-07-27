package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantTransactional;
import com.meterly.platform.usage.UsageEventMessage;
import com.meterly.platform.notification.UsageThresholdCrossedMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UsageMeteringService {
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("uuuu-MM").withZone(ZoneOffset.UTC);
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaTemplate<String, UsageThresholdCrossedMessage> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final long notificationThreshold;

    public UsageMeteringService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            SimpMessagingTemplate messagingTemplate,
            ObjectProvider<KafkaTemplate<String, UsageThresholdCrossedMessage>> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${meterly.notifications.usage-threshold:1000}") long notificationThreshold
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.kafkaTemplate = kafkaTemplate.getIfAvailable();
        this.meterRegistry = meterRegistry;
        this.notificationThreshold = notificationThreshold;
    }

    @TenantTransactional
    public boolean record(UsageEventMessage event) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO usage_event (id, tenant_id, resource_id, idempotency_key, quantity, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), event.tenantId(), event.resourceId(), event.idempotencyKey(),
                event.quantity(), Timestamp.from(event.occurredAt()));
        if (inserted == 0) return false;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Usage metering requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishCommittedEvent(event);
            }
        });
        return true;
    }

    private void publishCommittedEvent(UsageEventMessage event) {
        String key = redisKey(event.tenantId(), event.resourceId(), PERIOD.format(event.occurredAt()));
        Long total = redisTemplate.opsForValue().increment(key, event.quantity());
        messagingTemplate.convertAndSend("/topic/usage/" + event.tenantId(),
                new UsageUpdate(event.tenantId(), event.resourceId(), total == null ? 0 : total, java.time.Instant.now()));
        long currentTotal = total == null ? 0 : total;
        meterRegistry.counter("meterly.usage.metered", "tenant_id", event.tenantId().toString()).increment(event.quantity());
        if (currentTotal >= notificationThreshold && currentTotal - event.quantity() < notificationThreshold) {
            String eventKey = event.tenantId() + ":" + event.resourceId() + ":" + PERIOD.format(event.occurredAt())
                    + ":" + notificationThreshold;
            UUID notificationEventId = UUID.nameUUIDFromBytes(eventKey.getBytes(StandardCharsets.UTF_8));
            if (kafkaTemplate != null) {
                kafkaTemplate.send("usage-threshold-crossed", event.tenantId().toString(),
                        new UsageThresholdCrossedMessage(
                                notificationEventId,
                                event.tenantId(),
                                event.resourceId(),
                                currentTotal,
                                notificationThreshold,
                                java.time.Instant.now()
                        ));
            }
        }
    }

    @TenantTransactional
    public long currentUsage(UUID tenantId, UUID resourceId, String period) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM metered_resource WHERE id = ?", Integer.class, resourceId);
        if (count == null || count == 0) throw new ResponseStatusException(NOT_FOUND, "Resource not found");
        String value = redisTemplate.opsForValue().get(redisKey(tenantId, resourceId, period));
        return value == null ? 0 : Long.parseLong(value);
    }

    @TenantTransactional
    public long authoritativeUsage(UUID resourceId) {
        Long total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM usage_event WHERE resource_id = ?", Long.class, resourceId);
        return total == null ? 0 : total;
    }

    static String redisKey(UUID tenantId, UUID resourceId, String period) {
        return "usage:" + tenantId + ":" + resourceId + ":" + period;
    }
}
