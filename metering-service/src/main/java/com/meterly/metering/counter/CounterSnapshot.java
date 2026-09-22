package com.meterly.metering.counter;

/**
 * A counter value together with the ledger version it reflects: the id of the last ledger row counted.
 * Versions only grow in commit order (writers of one counter are serialized by an advisory lock and
 * ledger ids are assigned under that lock), so "higher version wins" is a safe rule for every copy.
 */
public record CounterSnapshot(long totalUnits, long ledgerVersion) {

    public static final CounterSnapshot EMPTY = new CounterSnapshot(0, 0);

    public CounterSnapshot {
        if (totalUnits < 0 || ledgerVersion < 0) {
            throw new IllegalArgumentException("Counter values must not be negative");
        }
    }
}
