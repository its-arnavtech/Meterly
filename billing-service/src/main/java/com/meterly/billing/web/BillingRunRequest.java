package com.meterly.billing.web;

/**
 * Body of {@code POST /v1/billing/run} and {@code POST /internal/v1/billing/run-all}:
 * {@code { "period_start": "2026-08-01" }}, optional (default: the previous month). Kept as text so an
 * unparseable date is reported as {@code 400 INVALID_PERIOD} like any other invalid period.
 */
public record BillingRunRequest(String periodStart) {
}
