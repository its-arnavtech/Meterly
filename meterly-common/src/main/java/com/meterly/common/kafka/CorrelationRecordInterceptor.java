package com.meterly.common.kafka;

import com.meterly.common.web.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Propagates the correlation ID across Kafka: {@link #attach} copies the current MDC value onto an
 * outgoing record; as a {@link RecordInterceptor} it restores it into the MDC of the consuming thread
 * (a fresh ID is generated for records produced without one).
 * Register on a listener container factory with {@code factory.setRecordInterceptor(new CorrelationRecordInterceptor<>())}.
 */
public class CorrelationRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    public static <K, V> ProducerRecord<K, V> attach(ProducerRecord<K, V> record) {
        CorrelationId.current().ifPresent(id ->
                record.headers().add(CorrelationId.KAFKA_HEADER, id.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        Header header = record.headers().lastHeader(CorrelationId.KAFKA_HEADER);
        String candidate = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitizeOrGenerate(candidate));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
