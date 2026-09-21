package com.meterly.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** A single Redis 7 container per test JVM. */
public final class MeterlyRedis {

    public static final String IMAGE = "redis:7.4-alpine";

    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);

    private MeterlyRedis() {
    }

    public static synchronized GenericContainer<?> start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.data.redis.host", CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> CONTAINER.getMappedPort(6379));
    }

    public static String host() {
        return start().getHost();
    }

    public static int port() {
        return start().getMappedPort(6379);
    }
}
