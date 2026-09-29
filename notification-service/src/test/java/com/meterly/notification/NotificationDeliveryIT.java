package com.meterly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import com.meterly.common.event.NotificationEvent;
import com.meterly.notification.delivery.NotificationProcessor;
import com.meterly.notification.email.EmailSender;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwtConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "meterly.notification.retry.enabled=false"})
@Import({TestJwtConfiguration.class, NotificationDeliveryIT.TestEmail.class})
class NotificationDeliveryIT {
    static final AtomicInteger sends = new AtomicInteger();
    @Autowired NotificationProcessor processor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedpanda.registerProperties(registry);
    }
    @TestConfiguration
    static class TestEmail {
        @Bean @Primary EmailSender testSender() { return email -> sends.incrementAndGet(); }
    }
    @BeforeEach void reset() { MeterlyPostgres.truncateTenantData(); sends.set(0); }

    @Test
    void redeliverySendsOnceAndResolvesOnlyOwnTenantAdmins() {
        var tenant = UUID.randomUUID();
        var other = UUID.randomUUID();
        var db = MeterlyPostgres.ownerJdbc();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'First'), (?, 'Other')", tenant, other);
        db.update("INSERT INTO app_user(tenant_id,email,role,password_hash) VALUES (?, 'first@example.test','TENANT_ADMIN','x'), (?, 'other@example.test','TENANT_ADMIN','x')", tenant, other);
        var event = new NotificationEvent.InvoiceIssued(UUID.randomUUID(), tenant, Instant.now(),
                UUID.randomUUID(), "INV-TEST", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                new BigDecimal("274.00"), "USD");
        assertThat(processor.process(event).sent()).isEqualTo(1);
        assertThat(processor.process(event).sent()).isZero();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT recipient FROM notification", String.class)).isEqualTo("first@example.test");
        assertThat(db.queryForObject("SELECT status FROM notification", String.class)).isEqualTo("SENT");
    }
}
