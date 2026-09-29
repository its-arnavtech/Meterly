package com.meterly.metering.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.common.event.UsageUpdate;
import com.meterly.common.security.Role;
import com.meterly.metering.MeteringIntegrationTest;
import com.meterly.testsupport.TestJwt;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The live dashboard channel (POC hypothesis 3, contract section 3): a browser subscribed to its own
 * tenant topic receives the {@link UsageUpdate} of a freshly produced usage event well within two
 * seconds, cannot subscribe to another tenant's topic, and cannot connect without a token.
 */
class UsageLiveUpdateIT extends MeteringIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UsageLiveUpdateIT.class);
    private static final Duration LATENCY_BUDGET = Duration.ofSeconds(2);
    private static final int TRIALS = 5;

    private static ThreadPoolTaskScheduler clientScheduler;

    @BeforeAll
    static void startClientScheduler() {
        clientScheduler = new ThreadPoolTaskScheduler();
        clientScheduler.setPoolSize(1);
        clientScheduler.setThreadNamePrefix("stomp-client-");
        clientScheduler.initialize();
    }

    @AfterAll
    static void stopClientScheduler() {
        clientScheduler.shutdown();
    }

    /** Collects MESSAGE payloads and ERROR frames of one STOMP session. */
    private static final class RecordingHandler extends StompSessionHandlerAdapter {

        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<String> failure = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String body = payload == null ? "" : new String((byte[]) payload, StandardCharsets.UTF_8);
            if (StompCommand.ERROR.name().equals(String.valueOf(headers.getFirst("command")))
                    || headers.getFirst("message") != null) {
                failure.complete("ERROR: " + headers.getFirst("message"));
                return;
            }
            messages.add(body);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            failure.complete("EXCEPTION: " + exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            failure.complete("TRANSPORT: " + exception);
        }
    }

    @Test
    void subscribedDashboardReceivesUpdatesWithinTwoSeconds() throws Exception {
        TenantFixture tenant = givenTenant("Live Inc");
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(bearerFor(tenant.tenantId()), handler);
        session.subscribe(UsageUpdate.stompDestination(tenant.tenantId()), handler);

        // Warm-up: subscription registration is asynchronous, and the first event primes every path.
        publish(usageEvent(tenant, "warmup-" + UUID.randomUUID(), 1));
        assertThat(handler.messages.poll(20, TimeUnit.SECONDS)).isNotNull();

        List<Long> latencies = new ArrayList<>(TRIALS);
        long total = 1;
        for (int trial = 0; trial < TRIALS; trial++) {
            long startedAt = System.nanoTime();
            publish(usageEvent(tenant, "live-" + UUID.randomUUID(), 2));
            total += 2;
            String payload = handler.messages.poll(LATENCY_BUDGET.toSeconds() + 3, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            latencies.add(millis);
            assertThat(payload).as("update for trial %d", trial).isNotNull();
            log.info("STOMP update latency (produce -> browser) trial {}: {} ms", trial + 1, millis);

            assertThat(payload).contains("\"tenant_id\":\"" + tenant.tenantId() + "\"")
                    .contains("\"resource_id\":\"" + tenant.resourceId() + "\"")
                    .contains("\"period_start\":\"" + today().withDayOfMonth(1) + "\"")
                    .contains("\"total_units\":" + total)
                    .contains("\"delta\":2");
            UsageUpdate update = objectMapper.readValue(payload, UsageUpdate.class);
            assertThat(update.tenantId()).isEqualTo(tenant.tenantId());
            assertThat(update.totalUnits()).isEqualTo(total);
            assertThat(update.recordedAt()).isNotNull();
        }
        long max = latencies.stream().mapToLong(Long::longValue).max().orElseThrow();
        log.info("STOMP update latencies {} ms (max {} ms, budget {} ms)", latencies, max, LATENCY_BUDGET.toMillis());
        assertThat(max).isLessThan(LATENCY_BUDGET.toMillis());
        session.disconnect();
    }

    @Test
    void subscribingToAnotherTenantsTopicIsRejected() throws Exception {
        TenantFixture tenant = givenTenant("Nosy Inc");
        UUID otherTenant = UUID.randomUUID();
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(bearerFor(tenant.tenantId()), handler);

        session.subscribe(UsageUpdate.stompDestination(otherTenant), handler);

        assertThat(handler.failure.get(20, TimeUnit.SECONDS)).isNotBlank();
        assertThat(handler.messages).isEmpty();
    }

    @Test
    void connectingWithoutATokenIsRejected() throws Exception {
        RecordingHandler handler = new RecordingHandler();

        StompSession session;
        try {
            session = connect(null, handler);
        } catch (ExecutionException | TimeoutException rejectedBeforeConnected) {
            assertThat(handler.messages).isEmpty();
            return;
        }
        // The server may also accept the socket and answer the CONNECT frame with ERROR.
        assertThat(handler.failure.get(20, TimeUnit.SECONDS)).isNotBlank();
        assertThat(session.isConnected()).isFalse();
    }

    private String bearerFor(UUID tenantId) {
        return TestJwt.bearer(tenantId, UUID.randomUUID(), Role.TENANT_USER);
    }

    private StompSession connect(String authorization, RecordingHandler handler) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setTaskScheduler(clientScheduler);
        StompHeaders connectHeaders = new StompHeaders();
        if (authorization != null) {
            connectHeaders.add("Authorization", authorization);
        }
        return client.connectAsync("ws://localhost:" + port + WebSocketConfiguration.ENDPOINT,
                new WebSocketHttpHeaders(), connectHeaders, handler).get(20, TimeUnit.SECONDS);
    }
}
