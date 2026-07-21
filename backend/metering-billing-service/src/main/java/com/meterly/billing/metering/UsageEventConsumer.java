package com.meterly.billing.metering;

import com.meterly.billing.tenant.TenantContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UsageEventConsumer {
    private final UsageMeteringService meteringService;

    public UsageEventConsumer(UsageMeteringService meteringService) {
        this.meteringService = meteringService;
    }

    @KafkaListener(topics = "usage-events")
    public void consume(UsageEventMessage event) {
        try (TenantContext.Scope ignored = TenantContext.open(event.tenantId())) {
            meteringService.record(event);
        }
    }
}

