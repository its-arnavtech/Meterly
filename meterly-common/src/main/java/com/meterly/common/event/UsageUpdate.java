package com.meterly.common.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Live counter update. Published by metering-service on the Redis channel
 * {@code meterly:usage-updates} (fan-out across instances) and pushed to browsers on the STOMP
 * destination {@code /topic/tenants/{tenantId}/usage}.
 *
 * @param totalUnits the counter value for the open period after applying {@code delta}
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageUpdate(
        UUID tenantId,
        UUID resourceId,
        LocalDate periodStart,
        long totalUnits,
        long delta,
        Instant occurredAt,
        Instant recordedAt) {

    public static String stompDestination(UUID tenantId) {
        return "/topic/tenants/" + tenantId + "/usage";
    }
}
