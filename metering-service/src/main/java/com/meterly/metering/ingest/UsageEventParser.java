package com.meterly.metering.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.event.UsageEventMessage;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code usage-events} record into a validated {@link UsageEventMessage}. Ingestion already
 * validates requests, but the topic is a trust boundary (other producers, replays, tooling), so the
 * contract's invariants are re-checked here. Failures throw {@link InvalidUsageEventException}.
 */
@Component
public class UsageEventParser {

    /** Same rule as ingestion: 1..255 chars of {@code [A-Za-z0-9._:-]}. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:\\-]{1,255}");
    static final long MAX_QUANTITY = 1_000_000_000L;

    private final ObjectMapper objectMapper;

    public UsageEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public UsageEventMessage parse(ConsumerRecord<String, String> record) {
        if (record.value() == null || record.value().isBlank()) {
            throw new InvalidUsageEventException("Usage event record has no value");
        }
        UsageEventMessage event;
        try {
            event = objectMapper.readValue(record.value(), UsageEventMessage.class);
        } catch (JsonProcessingException | RuntimeException e) {
            // Includes violations of UsageEventMessage's compact-constructor invariants (null ids,
            // blank key, quantity <= 0), which Jackson reports as mapping exceptions.
            throw new InvalidUsageEventException("Usage event is not valid UsageEventMessage JSON", e);
        }
        if (event == null) {
            throw new InvalidUsageEventException("Usage event record holds JSON null");
        }
        if (!IDEMPOTENCY_KEY.matcher(event.idempotencyKey()).matches()) {
            throw new InvalidUsageEventException("idempotency_key must be 1..255 chars of [A-Za-z0-9._:-]");
        }
        if (event.quantity() > MAX_QUANTITY) {
            throw new InvalidUsageEventException("quantity must be at most " + MAX_QUANTITY);
        }
        if (record.key() != null && !record.key().equals(event.tenantId().toString())) {
            throw new InvalidUsageEventException("Record key does not match the event's tenant_id");
        }
        return event;
    }
}
