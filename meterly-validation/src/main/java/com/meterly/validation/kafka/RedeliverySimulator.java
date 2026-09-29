package com.meterly.validation.kafka;

import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.validation.http.HarnessException;
import com.meterly.validation.json.Json;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Produces {@link UsageEventMessage}s directly to {@code usage-events} (bypassing ingestion-service
 * entirely) to simulate a Kafka-level redelivery: the same {@code (tenant_id, idempotency_key)} pair
 * metering-service already committed to the ledger, delivered again. Reuses meterly-common's own
 * record and topic name, so the JSON shape is byte-for-byte what ingestion-service would have sent
 * (docs/api-contract.md §6).
 */
public final class RedeliverySimulator implements AutoCloseable {

    private final KafkaProducer<String, String> producer;

    public RedeliverySimulator(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "meterly-validation-redelivery");
        this.producer = new KafkaProducer<>(props);
    }

    public void redeliver(UsageEventMessage message) {
        try {
            String json = Json.mapper().writeValueAsString(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    Topics.USAGE_EVENTS, message.tenantId().toString(), json);
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new HarnessException("Failed to redeliver " + message.idempotencyKey() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
