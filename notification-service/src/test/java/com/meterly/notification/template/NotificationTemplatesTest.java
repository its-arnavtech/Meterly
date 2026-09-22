package com.meterly.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.common.event.NotificationEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

    private final NotificationTemplates templates = new NotificationTemplates();

    @Test
    void invoiceEmailShowsExactTotalAndInclusivePeriod() {
        var invoice = new NotificationEvent.InvoiceIssued(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), "INV-202608-000001", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                new BigDecimal("1278.69"), "USD");

        RenderedEmail email = templates.render(invoice);

        assertThat(email.type()).isEqualTo(NotificationEvent.INVOICE_ISSUED);
        assertThat(email.subject()).contains("INV-202608-000001");
        assertThat(email.body()).contains("USD 1,278.69", "2026-08-01 to 2026-08-31");
    }

    @Test
    void thresholdEmailExplainsWhereUsageStands() {
        var threshold = new NotificationEvent.UsageThresholdCrossed(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), UUID.randomUUID(), "API calls", LocalDate.of(2026, 9, 1), 100, 10_250, 10_000);

        RenderedEmail email = templates.render(threshold);

        assertThat(email.type()).isEqualTo(NotificationEvent.USAGE_THRESHOLD_CROSSED);
        assertThat(email.subject()).contains("API calls", "100%");
        assertThat(email.body()).contains("10,250 units", "10,000 units", "billed at your plan's tiered rates");
    }

    @Test
    void tenantControlledTextCannotInjectHeaderLines() {
        var threshold = new NotificationEvent.UsageThresholdCrossed(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), UUID.randomUUID(), "API\r\nBcc: attacker@example.com", LocalDate.of(2026, 9, 1),
                80, 8_000, 10_000);

        assertThat(templates.render(threshold).subject()).doesNotContain("\r", "\n");
    }

    @Test
    void moneyFormattingIsExactAndGrouped() {
        assertThat(NotificationTemplates.formatMoney(new BigDecimal("0.1"), "USD")).isEqualTo("USD 0.10");
        assertThat(NotificationTemplates.formatMoney(new BigDecimal("1234567.89"), "USD")).isEqualTo("USD 1,234,567.89");
        assertThat(NotificationTemplates.formatMoney(new BigDecimal("0.015"), "USD")).isEqualTo("USD 0.02");
        assertThat(NotificationTemplates.formatMoney(new BigDecimal("0.025"), "USD")).isEqualTo("USD 0.02");
        assertThat(NotificationTemplates.formatUnits(1_000_000L)).isEqualTo("1,000,000");
    }
}
