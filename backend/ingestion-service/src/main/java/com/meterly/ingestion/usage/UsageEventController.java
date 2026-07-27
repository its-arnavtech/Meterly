package com.meterly.ingestion.usage;

import com.meterly.ingestion.security.ApiKeyPrincipal;
import com.meterly.platform.usage.UsageEventMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
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
    private final MeterRegistry meterRegistry;

    public UsageEventController(
            KafkaTemplate<String, UsageEventMessage> kafkaTemplate,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            Authentication authentication,
            @Valid @RequestBody UsageEventRequest request
    ) throws Exception {
        ApiKeyPrincipal principal = (ApiKeyPrincipal) authentication.getPrincipal();
        if (!principal.resourceIds().contains(request.resourceId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Resource is not owned by API-key tenant"));
        }
        UsageEventMessage event = new UsageEventMessage(
                principal.tenantId(),
                request.resourceId(),
                request.idempotencyKey(),
                request.quantity(),
                request.occurredAt() == null ? Instant.now() : request.occurredAt()
        );
        kafkaTemplate.send("usage-events", principal.tenantId().toString(), event).get(5, TimeUnit.SECONDS);
        if (meterRegistry != null) {
            meterRegistry.counter("meterly.usage.ingestion", "tenant_id", principal.tenantId().toString()).increment();
        }
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "idempotency_key", request.idempotencyKey()
        ));
    }
}
