package com.meterly.ingestion.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.kafka.CorrelationRecordInterceptor;
import com.meterly.common.web.ApiException;
import com.meterly.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link UsageEventMessage} to {@value Topics#USAGE_EVENTS}, keyed by tenant id, and blocks
 * for the broker's acknowledgement (the producer is configured with {@code acks=all} and
 * {@code enable.idempotence=true} in {@code application.yml}). A timeout or send failure becomes
 * {@code 503 INGESTION_UNAVAILABLE}: the caller can safely retry with the same idempotency key.
 */
@Component
public class UsageEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private final Duration publishTimeout;

    public UsageEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
            MeterRegistry metrics, IngestionProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.publishTimeout = properties.publishTimeout();
    }

    public void publish(UsageEventMessage message) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            String payload = objectMapper.writeValueAsString(message);
            ProducerRecord<String, String> record = CorrelationRecordInterceptor.attach(
                    new ProducerRecord<>(Topics.USAGE_EVENTS, message.tenantId().toString(), payload));
            kafka.send(record).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize UsageEventMessage", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException e) {
            throw unavailable();
        } finally {
            sample.stop(metrics.timer("meterly.ingestion.publish.latency"));
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INGESTION_UNAVAILABLE",
                "Usage event broker is temporarily unavailable; retry with the same idempotency key");
    }
}
