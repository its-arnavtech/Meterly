package com.meterly.notification.template;

import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.NotificationEvent.InvoiceIssued;
import com.meterly.common.event.NotificationEvent.UsageThresholdCrossed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders the subject and plain-text body for each {@link NotificationEvent} type.
 *
 * <p>{@link #render} is the single dispatch point: an exhaustive pattern-matching {@code switch} over the
 * sealed event hierarchy with no {@code default}, so adding an event type to {@code meterly-common}
 * fails compilation here until it has a template.
 *
 * <p>Money is formatted from {@link BigDecimal} with string operations only (never through a
 * {@code double}); billing periods are {@code [period_start, period_end)} and are shown with an
 * inclusive last day.
 */
@Component
public class NotificationTemplates {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

    private static final String INVOICE_SUBJECT = "Your Meterly invoice %s for %s";
    private static final String INVOICE_BODY = """
            Hello,

            Your Meterly invoice for %s has been issued.

              Invoice number:  %s
              Billing period:  %s to %s
              Total:           %s

            You can view the invoice and its line items in the Meterly console under Billing > Invoices.

            You are receiving this email as an administrator of your Meterly account.
            Meterly Billing
            """;

    private static final String THRESHOLD_SUBJECT = "Usage alert: %s reached %d%% of included units (%s)";
    private static final String THRESHOLD_BODY = """
            Hello,

            Usage of "%s" has reached %d%% of the units included in your plan for %s.

              Resource:        %s
              Threshold:       %d%%
              Used so far:     %s units
              Included units:  %s units
              Billing period:  %s to %s

            %s

            Live usage is available on the Meterly dashboard.

            You are receiving this email as an administrator of your Meterly account.
            Meterly
            """;

    private static final String BELOW_ALLOWANCE_NOTE =
            "Usage above the included units will be billed at your plan's tiered rates.";
    private static final String OVER_ALLOWANCE_NOTE =
            "Further usage is billed at your plan's tiered rates and will appear on this period's invoice.";

    public RenderedEmail render(NotificationEvent event) {
        return switch (event) {
            case InvoiceIssued invoice -> invoiceIssued(invoice);
            case UsageThresholdCrossed threshold -> usageThresholdCrossed(threshold);
        };
    }

    private RenderedEmail invoiceIssued(InvoiceIssued invoice) {
        String month = monthLabel(invoice.periodStart());
        String subject = INVOICE_SUBJECT.formatted(invoice.invoiceNumber(), month);
        String body = INVOICE_BODY.formatted(
                month,
                invoice.invoiceNumber(),
                invoice.periodStart(),
                invoice.periodEnd().minusDays(1),
                formatMoney(invoice.totalAmount(), invoice.currency()));
        return new RenderedEmail(NotificationEvent.INVOICE_ISSUED, singleLine(subject), body);
    }

    private RenderedEmail usageThresholdCrossed(UsageThresholdCrossed threshold) {
        String month = monthLabel(threshold.periodStart());
        int percent = threshold.thresholdPercent();
        String subject = THRESHOLD_SUBJECT.formatted(threshold.resourceName(), percent, month);
        String body = THRESHOLD_BODY.formatted(
                threshold.resourceName(), percent, month,
                threshold.resourceName(),
                percent,
                formatUnits(threshold.totalUnits()),
                formatUnits(threshold.includedUnits()),
                threshold.periodStart(),
                threshold.periodStart().plusMonths(1).minusDays(1),
                percent >= 100 ? OVER_ALLOWANCE_NOTE : BELOW_ALLOWANCE_NOTE);
        return new RenderedEmail(NotificationEvent.USAGE_THRESHOLD_CROSSED, singleLine(subject), body);
    }

    /** {@code USD 1,234.50}: exactly two decimals (HALF_EVEN, the platform's rounding rule), grouped thousands. */
    static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_EVEN);
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String grouped = group(plain.substring(0, dot)) + plain.substring(dot);
        return currency + " " + (scaled.signum() < 0 ? "-" : "") + grouped;
    }

    /** {@code 10000} becomes {@code 10,000}. */
    static String formatUnits(long units) {
        String digits = Long.toString(Math.abs(units));
        return (units < 0 ? "-" : "") + group(digits);
    }

    private static String group(String digits) {
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    private static String monthLabel(LocalDate periodStart) {
        return YearMonth.from(periodStart).format(MONTH);
    }

    /** Subjects contain tenant-controlled text (resource names): no CR/LF or other control characters. */
    private static String singleLine(String subject) {
        return CONTROL_CHARS.matcher(subject).replaceAll(" ").strip();
    }
}
