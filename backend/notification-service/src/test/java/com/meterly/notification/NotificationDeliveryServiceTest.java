package com.meterly.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void duplicateEventIsDeliveredExactlyOnce() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Boolean>) invocation.getArgument(0)).doInTransaction(transactionStatus));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class)))
                .thenReturn(tenantId.toString());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0);

        NotificationDeliveryService service =
                new NotificationDeliveryService(jdbcTemplate, transactionTemplate, registry);

        assertThat(service.deliver(eventId, tenantId, "INVOICE_GENERATED", "invoice_id=test")).isTrue();
        assertThat(service.deliver(eventId, tenantId, "INVOICE_GENERATED", "invoice_id=test")).isFalse();
        assertThat(registry.get("meterly.notification.delivered").counter().count()).isEqualTo(1.0);
    }
}
