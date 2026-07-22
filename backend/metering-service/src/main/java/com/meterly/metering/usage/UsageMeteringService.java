package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantTransactional;
import com.meterly.platform.usage.UsageEventMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UsageMeteringService {
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("uuuu-MM").withZone(ZoneOffset.UTC);
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public UsageMeteringService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate, SimpMessagingTemplate messagingTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
    }

    @TenantTransactional
    public boolean record(UsageEventMessage event) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO usage_event (id, tenant_id, resource_id, idempotency_key, quantity, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), event.tenantId(), event.resourceId(), event.idempotencyKey(),
                event.quantity(), Timestamp.from(event.occurredAt()));
        if (inserted == 0) return false;
        String key = redisKey(event.tenantId(), event.resourceId(), PERIOD.format(event.occurredAt()));
        Long total = redisTemplate.opsForValue().increment(key, event.quantity());
        messagingTemplate.convertAndSend("/topic/usage/" + event.tenantId(),
                new UsageUpdate(event.tenantId(), event.resourceId(), total == null ? 0 : total, java.time.Instant.now()));
        return true;
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
