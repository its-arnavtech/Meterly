package com.meterly.metering.reconciliation;

/** Result of a reconciliation run: {@code { "tenants_checked", "counters_checked", "counters_corrected" }}. */
public record ReconciliationSummary(int tenantsChecked, int countersChecked, int countersCorrected) {
}
