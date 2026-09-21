package com.meterly.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void usageEventRoundTripsWithSnakeCaseFields() throws Exception {
        var event = new UsageEventMessage("key-1", UUID.randomUUID(), UUID.randomUUID(), 3,
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:00:01Z"), "req-1");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"idempotency_key\":\"key-1\"", "\"tenant_id\"", "\"occurred_at\":\"2026-09-21T10:00:00Z\"");
        assertThat(mapper.readValue(json, UsageEventMessage.class)).isEqualTo(event);
    }

    @Test
    void notificationEventsCarryATypeDiscriminatorAndMoneyAsString() throws Exception {
        NotificationEvent event = new NotificationEvent.InvoiceIssued(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-09-01T02:00:00Z"), UUID.randomUUID(), "INV-202608-000001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), new BigDecimal("278.69"), "USD");

        String json = mapper.writeValueAsString(event);
        NotificationEvent back = mapper.readValue(json, NotificationEvent.class);

        assertThat(json).contains("\"type\":\"invoice.issued\"", "\"total_amount\":\"278.69\"");
        String summary = switch (back) {
            case NotificationEvent.InvoiceIssued invoice -> "invoice " + invoice.invoiceNumber();
            case NotificationEvent.UsageThresholdCrossed threshold -> "threshold " + threshold.thresholdPercent();
        };
        assertThat(summary).isEqualTo("invoice INV-202608-000001");
        assertThat(back).isEqualTo(event);
    }
}
