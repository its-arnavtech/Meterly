package com.meterly.metering.config;

import com.meterly.platform.auth.AuthPrincipal;
import com.meterly.platform.auth.JwtService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class MessagingConfiguration implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwtService;

    public MessagingConfiguration(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    NewTopic usageEventsTopic() {
        return TopicBuilder.name("usage-events").partitions(3).replicas(1).build();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/usage").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) return message;
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String header = accessor.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) {
                        throw new IllegalArgumentException("A bearer token is required");
                    }
                    AuthPrincipal principal = jwtService.verify(header.substring(7))
                            .orElseThrow(() -> new IllegalArgumentException("Invalid bearer token"));
                    accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                            java.util.List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))));
                } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken token)
                            || !(token.getPrincipal() instanceof AuthPrincipal principal)
                            || !("/topic/usage/" + principal.tenantId()).equals(accessor.getDestination())) {
                        throw new IllegalArgumentException("Cross-tenant subscription denied");
                    }
                }
                return message;
            }
        });
    }
}
