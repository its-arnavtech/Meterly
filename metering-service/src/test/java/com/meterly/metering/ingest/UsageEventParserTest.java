package com.meterly.metering.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * The topic is a trust boundary: anything that cannot be turned into a valid {@link UsageEventMessage}
 * must fail as {@link InvalidUsageEventException}, which the error handler dead-letters without retrying.
 */
class UsageEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final UsageEventParser parser = new UsageEventParser(objectMapper);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    @Test
    void parsesAValidRecord() throws Exception {
        UsageEventMessage event = new UsageEventMessage("req-1", tenantId, resourceId, 3,
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:00:01Z"), "request-1");

        assertThat(parser.parse(record(objectMapper.writeValueAsString(event)))).isEqualTo(event);
    }

    @Test
    void rejectsMalformedAndEmptyPayloads() {
        assertThatThrownBy(() -> parser.parse(record(null))).isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(record("   "))).isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(record("{"))).isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(record("\"a string\"")))
                .isInstanceOf(InvalidUsageEventException.class);
    }

    @Test
    void rejectsEventsThatBreakTheContract() {
        assertThatThrownBy(() -> parser.parse(record(json("req-1", 0))))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(record(json("req-1", 1_000_000_001L))))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(record(json("bad key!", 1))))
                .isInstanceOf(InvalidUsageEventException.class);
        assertThatThrownBy(() -> parser.parse(new ConsumerRecord<>(Topics.USAGE_EVENTS, 0, 0L,
                UUID.randomUUID().toString(), json("req-1", 1))))
                .isInstanceOf(InvalidUsageEventException.class)
                .hasMessageContaining("tenant_id");
    }

    private String json(String idempotencyKey, long quantity) {
        return """
                {"idempotency_key":"%s","tenant_id":"%s","resource_id":"%s","quantity":%d,
                 "occurred_at":"2026-09-21T10:00:00Z","received_at":"2026-09-21T10:00:01Z","request_id":"r"}
                """.formatted(idempotencyKey, tenantId, resourceId, quantity);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(Topics.USAGE_EVENTS, 0, 0L, tenantId.toString(), value);
    }
}
