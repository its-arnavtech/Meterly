package com.meterly.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.redis.RedisKeys;
import com.meterly.common.security.CurrentUser;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
class UsageWebSocket implements WebSocketMessageBrokerConfigurer {
    private final JwtDecoder decoder;
    private final MeterlyJwtAuthenticationConverter converter;
    private final MeterlyProperties properties;
    private final Clock clock;
    UsageWebSocket(JwtDecoder decoder,MeterlyJwtAuthenticationConverter converter,MeterlyProperties properties,Clock clock) {
        this.decoder=decoder; this.converter=converter; this.properties=properties; this.clock=clock;
    }
    @Bean
    ThreadPoolTaskScheduler stompHeartbeatScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("stomp-heartbeat-"); return scheduler;
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/usage").setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[]{10000,10000}).setTaskScheduler(stompHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app"); registry.setPreservePublishOrder(true);
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16*1024).setSendBufferSizeLimit(128*1024).setSendTimeLimit(10000).setTimeToFirstMessage(10000);
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
                var accessor=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if (accessor==null) throw new AccessDeniedException("STOMP required");
                if (accessor.getCommand()==StompCommand.CONNECT) {
                    String header=accessor.getFirstNativeHeader("Authorization");
                    if (header==null || !header.startsWith("Bearer ")) throw new AccessDeniedException("Bearer token required");
                    var jwt=decoder.decode(header.substring(7)); CurrentUser.from(jwt);
                    accessor.setUser(converter.convert(jwt)); return message;
                }
                if (accessor.getCommand()==StompCommand.DISCONNECT) return message;
                if (!(accessor.getUser() instanceof JwtAuthenticationToken auth) || auth.getToken().getExpiresAt()==null || !auth.getToken().getExpiresAt().isAfter(clock.instant()))
                    throw new AccessDeniedException("Session token expired or missing");
                if (accessor.getCommand()==StompCommand.SUBSCRIBE && !UsageUpdate.stompDestination(CurrentUser.from(auth.getToken()).tenantId()).equals(accessor.getDestination()))
                    throw new AccessDeniedException("Tenant destination forbidden");
                if (accessor.getCommand()==StompCommand.SEND) throw new AccessDeniedException("Usage stream is read only");
                return message;
            }
        });
    }
    @Bean
    RedisMessageListenerContainer usageFanout(RedisConnectionFactory factory,SimpMessagingTemplate messaging,ObjectMapper mapper) {
        var container=new RedisMessageListenerContainer(); container.setConnectionFactory(factory);
        container.addMessageListener((message,pattern)->{
            try {
                String json=new String(message.getBody(),StandardCharsets.UTF_8); UsageUpdate update=mapper.readValue(json,UsageUpdate.class);
                messaging.convertAndSend(UsageUpdate.stompDestination(update.tenantId()),json,Map.of(MessageHeaders.CONTENT_TYPE,MediaType.APPLICATION_JSON));
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(UsageWebSocket.class).warn("Invalid usage pub/sub payload rejected"); }
        },new ChannelTopic(RedisKeys.USAGE_UPDATES_CHANNEL));
        return container;
    }
}
