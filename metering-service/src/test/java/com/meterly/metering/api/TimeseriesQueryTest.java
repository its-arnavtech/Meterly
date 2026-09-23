package com.meterly.metering.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterly.common.web.ApiException;
import com.meterly.metering.api.TimeseriesQuery.Granularity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Defaults, parsing and range limits of the {@code /v1/usage/timeseries} query. */
class TimeseriesQueryTest {

    private static final UUID RESOURCE = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T17:04:05Z"), ZoneOffset.UTC);

    @Test
    void defaultsToTheCurrentPeriodInDailyBuckets() {
        TimeseriesQuery query = TimeseriesQuery.parse(RESOURCE.toString(), null, null, null, CLOCK);

        assertThat(query.granularity()).isEqualTo(Granularity.DAY);
        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(query.bucketStarts()).hasSize(30).first().isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void defaultsToTheLastTwentyFourBucketsForHourlyGranularity() {
        TimeseriesQuery query = TimeseriesQuery.parse(RESOURCE.toString(), "hour", null, null, CLOCK);

        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-20T18:00:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-09-21T18:00:00Z"));
        assertThat(query.bucketStarts()).hasSize(24);
    }

    @Test
    void acceptsInstantsAndPlainDates() {
        TimeseriesQuery query = TimeseriesQuery.parse(RESOURCE.toString(), "DAY", "2026-09-02",
                "2026-09-04T12:00:00Z", CLOCK);

        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-09-04T12:00:00Z"));
        // The last bucket is partial but still reported.
        assertThat(query.bucketStarts()).hasSize(3);
    }

    @Test
    void rejectsUnknownGranularityEmptyRangesAndRangesThatAreTooWide() {
        assertThatThrownBy(() -> TimeseriesQuery.parse(RESOURCE.toString(), "week", null, null, CLOCK))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code()).isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> TimeseriesQuery.parse(RESOURCE.toString(), "day", "2026-09-05", "2026-09-05", CLOCK))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeseriesQuery.parse(RESOURCE.toString(), "hour", "2026-09-01", "2026-09-21", CLOCK))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeseriesQuery.parse(RESOURCE.toString(), "day", "2026-01-01", "2026-09-21", CLOCK))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeseriesQuery.parse(null, "day", null, null, CLOCK))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeseriesQuery.parse("not-a-uuid", "day", null, null, CLOCK))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeseriesQuery.parse(RESOURCE.toString(), "day", "yesterday", null, CLOCK))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void percentOfIncludedIsRoundedToOneDecimal() {
        assertThat(UsageQueryService.percentOf(4200, 10_000)).isEqualTo(42.0);
        assertThat(UsageQueryService.percentOf(1, 3)).isEqualTo(33.3);
        assertThat(UsageQueryService.percentOf(5, 0)).isZero();
    }
}
