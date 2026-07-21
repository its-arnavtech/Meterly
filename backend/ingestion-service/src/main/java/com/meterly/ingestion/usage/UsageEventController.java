package com.meterly.ingestion.usage;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/v1/usage-events")
public class UsageEventController {
    private final KafkaTemplate<String, UsageEventMessage> kafkaTemplate;
    private final UUID tenantId;
    private final UUID defaultResourceId;

    public UsageEventController(
            KafkaTemplate<String, UsageEventMessage> kafkaTemplate,
            @Value("${meterly.ingestion.tenant-id}") UUID tenantId,
            @Value("${meterly.ingestion.resource-id}") UUID defaultResourceId
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.tenantId = tenantId;
        this.defaultResourceId = defaultResourceId;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody UsageEventRequest request) throws Exception {
        UsageEventMessage event = new UsageEventMessage(
                tenantId,
                request.resourceId() == null ? defaultResourceId : request.resourceId(),
                request.idempotencyKey(),
                request.quantity(),
                request.occurredAt() == null ? Instant.now() : request.occurredAt()
        );
        kafkaTemplate.send("usage-events", tenantId.toString(), event).get(5, TimeUnit.SECONDS);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "idempotency_key", request.idempotencyKey()
        ));
    }
}

