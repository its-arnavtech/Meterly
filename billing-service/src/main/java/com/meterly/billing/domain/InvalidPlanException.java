package com.meterly.billing.domain;

/**
 * A plan's pricing configuration cannot be priced unambiguously (negative price, gap or overlap between
 * tiers, missing open-ended tier, ...). Pricing such a plan could silently under- or over-bill, so the
 * engine refuses instead.
 */
public final class InvalidPlanException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidPlanException(String message) {
        super(message);
    }
}
