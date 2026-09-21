package com.meterly.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A single Redpanda container per test JVM. Redpanda speaks the Kafka wire protocol, so services use
 * the standard Kafka client against it, exactly as they do in Docker Compose.
 */
public final class MeterlyRedpanda {

    public static final String IMAGE = "redpandadata/redpanda:v24.3.18";

    private static final RedpandaContainer CONTAINER = new RedpandaContainer(IMAGE);

    private MeterlyRedpanda() {
    }

    public static synchronized RedpandaContainer start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.kafka.bootstrap-servers", CONTAINER::getBootstrapServers);
    }

    public static String bootstrapServers() {
        return start().getBootstrapServers();
    }
}
