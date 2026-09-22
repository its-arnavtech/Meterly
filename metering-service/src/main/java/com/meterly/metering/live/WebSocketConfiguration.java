package com.meterly.metering.live;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.metering.config.MeteringProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over native WebSocket at {@code /ws/usage}: in-memory simple broker on {@code /topic} with
 * server heartbeats (default 10s/10s), origins limited to the SPA origins, and every inbound frame
 * authorized by {@link StompAuthorizationInterceptor}. Updates reach the broker from Redis pub/sub
 * ({@link UsageUpdateRelay}), so a browser connected to any instance receives every update.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws/usage";

    private final MeterlyProperties meterlyProperties;
    private final MeteringProperties meteringProperties;
    private final StompAuthorizationInterceptor authorizationInterceptor;
    private final TaskScheduler heartbeatScheduler;

    /**
     * The broker's own {@code messageBrokerTaskScheduler} drives heartbeats; it is created by this very
     * configuration, hence the {@code @Lazy} proxy (the pattern recommended by Spring's reference docs).
     */
    public WebSocketConfiguration(MeterlyProperties meterlyProperties, MeteringProperties meteringProperties,
                                  StompAuthorizationInterceptor authorizationInterceptor,
                                  @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
        this.meterlyProperties = meterlyProperties;
        this.meteringProperties = meteringProperties;
        this.authorizationInterceptor = authorizationInterceptor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOrigins(meterlyProperties.cors().allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeatMillis = meteringProperties.stomp().heartbeat().toMillis();
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {heartbeatMillis, heartbeatMillis})
                .setTaskScheduler(heartbeatScheduler);
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorizationInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(10_000)
                .setTimeToFirstMessage(10_000);
    }
}
