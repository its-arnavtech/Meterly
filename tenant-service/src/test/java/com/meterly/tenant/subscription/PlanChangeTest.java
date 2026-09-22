package com.meterly.tenant.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The plan-change rules of contract section 1: next period, no proration, re-select to cancel. */
class PlanChangeTest {

    private static final UUID STARTER = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID GROWTH = UUID.fromString("0a000000-0000-4000-8000-000000000002");
    private static final UUID SCALE = UUID.fromString("0a000000-0000-4000-8000-000000000003");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private static final LocalDate NEXT_PERIOD = LocalDate.of(2026, 10, 1);

    private static SubscriptionRepository.Row subscription(UUID planId, LocalDate start, LocalDate end) {
        return new SubscriptionRepository.Row(UUID.randomUUID(), planId, start, end, "ACTIVE");
    }

    @Test
    void schedulesADifferentPlanForTheStartOfTheNextPeriod() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 8, 3), null);

        PlanChange change = PlanChange.decide(Optional.of(current), Optional.empty(), GROWTH, TODAY);

        assertThat(change).isEqualTo(new PlanChange.Schedule(current.id(), GROWTH, NEXT_PERIOD));
    }

    @Test
    void doesNothingWhenTheCurrentPlanIsReselectedWithNothingPending() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 8, 3), null);

        assertThat(PlanChange.decide(Optional.of(current), Optional.empty(), STARTER, TODAY))
                .isEqualTo(new PlanChange.NoChange());
    }

    @Test
    void reselectingTheCurrentPlanCancelsAPendingChange() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 8, 3), NEXT_PERIOD);
        SubscriptionRepository.Row pending = subscription(GROWTH, NEXT_PERIOD, null);

        assertThat(PlanChange.decide(Optional.of(current), Optional.of(pending), STARTER, TODAY))
                .isEqualTo(new PlanChange.KeepCurrent(current.id()));
    }

    @Test
    void repeatingTheSamePendingChangeIsIdempotent() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 8, 3), NEXT_PERIOD);
        SubscriptionRepository.Row pending = subscription(GROWTH, NEXT_PERIOD, null);

        assertThat(PlanChange.decide(Optional.of(current), Optional.of(pending), GROWTH, TODAY))
                .isEqualTo(new PlanChange.NoChange());
    }

    @Test
    void replacesAPendingChangeWithADifferentPlan() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 8, 3), NEXT_PERIOD);
        SubscriptionRepository.Row pending = subscription(GROWTH, NEXT_PERIOD, null);

        assertThat(PlanChange.decide(Optional.of(current), Optional.of(pending), SCALE, TODAY))
                .isEqualTo(new PlanChange.Schedule(current.id(), SCALE, NEXT_PERIOD));
    }

    @Test
    void startsImmediatelyWhenNoSubscriptionCoversToday() {
        assertThat(PlanChange.decide(Optional.empty(), Optional.empty(), GROWTH, TODAY))
                .isEqualTo(new PlanChange.StartNow(GROWTH, TODAY));
    }

    @Test
    void schedulesAtTheYearBoundary() {
        SubscriptionRepository.Row current = subscription(STARTER, LocalDate.of(2026, 12, 1), null);

        assertThat(PlanChange.decide(Optional.of(current), Optional.empty(), GROWTH, LocalDate.of(2026, 12, 31)))
                .isEqualTo(new PlanChange.Schedule(current.id(), GROWTH, LocalDate.of(2027, 1, 1)));
    }
}
