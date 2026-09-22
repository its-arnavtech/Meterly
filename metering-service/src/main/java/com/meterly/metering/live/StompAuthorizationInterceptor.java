package com.meterly.metering.live;

import com.meterly.common.event.UsageUpdate;
import com.meterly.common.security.CurrentUser;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authorizes every inbound STOMP frame on {@code /ws/usage}:
 * <ul>
 *   <li>CONNECT must carry {@code Authorization: Bearer <jwt>}, validated by the service's
 *       {@link JwtDecoder} (signature, issuer, audience, expiry, Meterly claims); the resulting
 *       authentication becomes the session user;</li>
 *   <li>SUBSCRIBE is allowed only to {@code /topic/tenants/{own tenant_id}/usage}, with an unexpired token;</li>
 *   <li>client SEND is rejected (the stream is read-only); UNSUBSCRIBE, DISCONNECT and heartbeats pass.</li>
 * </ul>
 * A rejection throws {@link AccessDeniedException}; Spring answers with a STOMP ERROR frame and closes
 * the connection.
 */
@Component
public class StompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthorizationInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final MeterlyJwtAuthenticationConverter authenticationConverter;
    private final Clock clock;

    public StompAuthorizationInterceptor(JwtDecoder jwtDecoder,
                                         MeterlyJwtAuthenticationConverter authenticationConverter, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            throw new AccessDeniedException("STOMP frame expected");
        }
        SimpMessageType type = accessor.getMessageType();
        if (type == null) {
            throw new AccessDeniedException("Unsupported frame");
        }
        switch (type) {
            case CONNECT -> accessor.setUser(authenticate(accessor));
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case UNSUBSCRIBE, DISCONNECT, HEARTBEAT -> {
                // Always allowed: they only reduce what the client receives.
            }
            case MESSAGE -> throw new AccessDeniedException("The usage stream is read-only");
            default -> throw new AccessDeniedException("Unsupported frame");
        }
        return message;
    }

    private JwtAuthenticationToken authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX) || header.length() == BEARER_PREFIX.length()) {
            throw new AccessDeniedException("A bearer token is required");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtException e) {
            log.debug("STOMP CONNECT rejected: {}", e.getClass().getSimpleName());
            throw new AccessDeniedException("Invalid bearer token");
        }
        if (!(authenticationConverter.convert(jwt) instanceof JwtAuthenticationToken authentication)) {
            throw new AccessDeniedException("Invalid bearer token");
        }
        return authentication;
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof JwtAuthenticationToken authentication)) {
            throw new AccessDeniedException("Not authenticated");
        }
        Jwt jwt = authentication.getToken();
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new AccessDeniedException("The bearer token has expired");
        }
        String allowed = UsageUpdate.stompDestination(CurrentUser.from(jwt).tenantId());
        if (!allowed.equals(accessor.getDestination())) {
            throw new AccessDeniedException("Subscriptions are limited to the own tenant's usage topic");
        }
    }
}
