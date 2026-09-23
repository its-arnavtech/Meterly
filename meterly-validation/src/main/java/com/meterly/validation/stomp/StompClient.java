package com.meterly.validation.stomp;

import com.meterly.validation.http.HarnessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A minimal STOMP 1.2 client over the JDK's native {@link WebSocket} (no SockJS: metering-service
 * registers a plain endpoint, see {@code UsageWebSocket.registerStompEndpoints}). Used only to measure
 * ingestion-to-dashboard latency, so it supports exactly what that needs: CONNECT with a bearer token,
 * one SUBSCRIBE, and reading back MESSAGE frames.
 */
public final class StompClient implements WebSocket.Listener, AutoCloseable {

    private volatile WebSocket socket;
    private final StompFrameReader reader = new StompFrameReader();
    private final BlockingQueue<StompFrame> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<StompFrame> connectedFrame = new CompletableFuture<>();

    private StompClient() {
    }

    /** Opens the WebSocket, sends CONNECT, and blocks until the server's CONNECTED frame arrives. */
    public static StompClient connect(HttpClient client, URI wsUri, String jwt, Duration timeout) {
        StompClient stompClient = new StompClient();
        try {
            WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(wsUri, stompClient)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            stompClient.socket = socket;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("accept-version", "1.2");
            headers.put("heart-beat", "0,0");
            headers.put("Authorization", "Bearer " + jwt);
            socket.sendText(StompCodec.encode(new StompFrame(StompFrame.CONNECT, headers, null)), true);
            stompClient.connectedFrame.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return stompClient;
        } catch (Exception e) {
            throw new HarnessException("STOMP connect to " + wsUri + " failed: " + e.getMessage(), e);
        }
    }

    public void subscribe(String destination, String subscriptionId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id", subscriptionId);
        headers.put("destination", destination);
        headers.put("ack", "auto");
        socket.sendText(StompCodec.encode(new StompFrame(StompFrame.SUBSCRIBE, headers, null)), true);
    }

    /** Waits up to {@code timeout} for the next MESSAGE frame (heartbeats and other frames are filtered out). */
    public Optional<StompFrame> pollMessage(Duration timeout) {
        try {
            return Optional.ofNullable(inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        for (StompFrame frame : reader.feed(data)) {
            switch (frame.command()) {
                case StompFrame.CONNECTED -> connectedFrame.complete(frame);
                case StompFrame.MESSAGE -> inbox.offer(frame);
                case StompFrame.ERROR -> {
                    connectedFrame.completeExceptionally(new HarnessException("STOMP ERROR: " + frame.body()));
                    inbox.offer(frame);
                }
                default -> {
                    // CONNECT/SUBSCRIBE/DISCONNECT never arrive from the server; ignore anything else.
                }
            }
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connectedFrame.completeExceptionally(error);
    }

    @Override
    public void close() {
        WebSocket current = this.socket;
        if (current != null && !current.isOutputClosed()) {
            try {
                current.sendText(StompCodec.encode(new StompFrame(StompFrame.DISCONNECT, Map.of(), null)), true);
            } catch (RuntimeException ignored) {
                // Best-effort; the harness is tearing down regardless.
            }
            current.abort();
        }
    }
}
