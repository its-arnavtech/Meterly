package com.meterly.metering.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.redis.RedisKeys;
import com.meterly.metering.MeteringMetrics;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Fan-out of live usage updates: every metering instance subscribes to the Redis channel
 * {@value RedisKeys#USAGE_UPDATES_CHANNEL} and forwards each {@link UsageUpdate} to its local STOMP
 * subscribers on {@code /topic/tenants/{tenantId}/usage}. The instance that recorded an event is
 * therefore not necessarily the one holding the browser's WebSocket.
 */
@Configuration(proxyBeanMethods = false)
public class UsageUpdateRelay {

    private static final Logger log = LoggerFactory.getLogger(UsageUpdateRelay.class);

    @Bean
    RedisMessageListenerContainer usageUpdateListenerContainer(RedisConnectionFactory connectionFactory,
                                                               MessageListener usageUpdateForwarder) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(usageUpdateForwarder, new ChannelTopic(RedisKeys.USAGE_UPDATES_CHANNEL));
        return container;
    }

    @Bean
    MessageListener usageUpdateForwarder(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper,
                                         MeteringMetrics metrics) {
        return (Message message, byte[] pattern) -> {
            UsageUpdate update;
            try {
                update = objectMapper.readValue(message.getBody(), UsageUpdate.class);
            } catch (IOException | RuntimeException e) {
                metrics.liveUpdate("rejected");
                log.warn("Ignoring malformed usage update on {} ({})", RedisKeys.USAGE_UPDATES_CHANNEL,
                        e.getClass().getSimpleName());
                return;
            }
            if (update == null || update.tenantId() == null || update.resourceId() == null) {
                metrics.liveUpdate("rejected");
                log.warn("Ignoring usage update without tenant or resource on {}", RedisKeys.USAGE_UPDATES_CHANNEL);
                return;
            }
            messagingTemplate.convertAndSend(UsageUpdate.stompDestination(update.tenantId()), update);
            metrics.liveUpdate("forwarded");
        };
    }
}
