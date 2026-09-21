package com.meterly.metering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class UsageConsumer {
    private final ObjectMapper mapper;
    private final UsageRepository repository;
    private final TransactionTemplate tx;
    private final UsageCache cache;
    private final Clock clock;
    private final MeterRegistry metrics;
    UsageConsumer(ObjectMapper mapper,UsageRepository repository,TransactionTemplate tx,UsageCache cache,Clock clock,MeterRegistry metrics) {
        this.mapper=mapper; this.repository=repository; this.tx=tx; this.cache=cache; this.clock=clock; this.metrics=metrics;
    }
    @KafkaListener(topics="usage-events",groupId="metering-service")
    public void consume(ConsumerRecord<String,String> record) {
        UsageEventMessage event;
        try { event=mapper.readValue(record.value(),UsageEventMessage.class); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid usage event",e); }
        if (!event.tenantId().toString().equals(record.key()) || event.quantity()>1_000_000_000 || !event.idempotencyKey().matches("[A-Za-z0-9._:\\-]{1,255}")) throw new IllegalArgumentException("Invalid usage event fields");
        BillingPeriod period=BillingPeriod.containing(event.occurredAt());
        var applied=TenantContext.callAs(event.tenantId(),()->tx.execute(s->repository.insert(event,period)));
        // Run only after COMMIT. Duplicate redelivery also repairs a projection missed by a crashed process.
        cache.project(event.tenantId(),event.resourceId(),period,applied.snapshot(),new UsageUpdate(event.tenantId(),event.resourceId(),period.start(),applied.snapshot().units(),applied.inserted()?event.quantity():0,event.occurredAt(),clock.instant()));
        metrics.counter("meterly_usage_events_total","outcome",applied.inserted()?"recorded":"duplicate").increment();
    }
}
