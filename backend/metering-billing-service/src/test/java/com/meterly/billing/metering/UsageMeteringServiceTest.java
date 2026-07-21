package com.meterly.billing.metering;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageMeteringServiceTest {
    @Test
    void newEventPublishesLiveUpdateWithinTwoSeconds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString(), any(Long.class))).thenReturn(1L);
        UsageMeteringService service = new UsageMeteringService(jdbc, redis, messaging);
        UsageEventMessage event = new UsageEventMessage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now());

        Instant started = Instant.now();
        assertThat(service.record(event)).isTrue();

        verify(messaging).convertAndSend(anyString(), any(UsageUpdate.class));
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
    }
}

