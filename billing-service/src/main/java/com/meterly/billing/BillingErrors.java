package com.meterly.billing;

import com.meterly.common.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The billing error codes of the API contract (section 4), as {@link ApiException} factories. */
public final class BillingErrors {

    public static final String INVALID_PERIOD = "INVALID_PERIOD";
    public static final String PERIOD_NOT_CLOSED = "PERIOD_NOT_CLOSED";
    public static final String NO_SUBSCRIPTION = "NO_SUBSCRIPTION";
    public static final String INVOICE_NOT_FOUND = "INVOICE_NOT_FOUND";

    private BillingErrors() {
    }

    public static ApiException invalidPeriod(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_PERIOD, message,
                Map.of("errors", List.of(Map.of("field", "period_start", "message", message))));
    }

    public static ApiException periodNotClosed(Object period) {
        return ApiException.unprocessable(PERIOD_NOT_CLOSED,
                "Billing period " + period + " has not ended yet; only closed periods can be invoiced");
    }

    public static ApiException noSubscription(Object period) {
        return ApiException.unprocessable(NO_SUBSCRIPTION, "No subscription covers billing period " + period);
    }

    public static ApiException invoiceNotFound() {
        return ApiException.notFound(INVOICE_NOT_FOUND, "Invoice not found");
    }

    public static boolean isNoSubscription(ApiException e) {
        return NO_SUBSCRIPTION.equals(e.code());
    }
}
