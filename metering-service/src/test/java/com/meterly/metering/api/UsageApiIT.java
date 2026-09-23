package com.meterly.metering.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.redis.RedisKeys;
import com.meterly.common.security.Role;
import com.meterly.metering.MeteringIntegrationTest;
import com.meterly.testsupport.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;

/**
 * The read API of contract section 3: {@code /v1/usage/current} (Redis first, then the projection, then
 * the ledger, re-seeding Redis on the way) and {@code /v1/usage/timeseries} (always the ledger).
 */
class UsageApiIT extends MeteringIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Test
    void currentUsageIsServedFromRedisAndFallsBackToProjectionThenLedger() {
        TenantFixture tenant = givenTenant("Dashboard Inc");
        publish(usageEvent(tenant, "current-" + UUID.randomUUID(), 4200));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(4200));

        Map<String, Object> fromRedis = getJson("/v1/usage/current", tenant.tenantId());
        assertThat(fromRedis.get("period_start")).isEqualTo(BillingPeriod.current(clock).start().toString());
        assertThat(fromRedis.get("period_end")).isEqualTo(BillingPeriod.current(clock).end().toString());
        assertThat(plan(fromRedis)).containsEntry("code", "STARTER").containsEntry("name", "Starter")
                .containsEntry("included_units", (int) STARTER_INCLUDED_UNITS);
        Map<String, Object> item = items(fromRedis).getFirst();
        assertThat(item).containsEntry("resource_id", tenant.resourceId().toString())
                .containsEntry("resource_name", "API calls")
                .containsEntry("unit_label", "call")
                .containsEntry("total_units", 4200)
                .containsEntry("included_units", (int) STARTER_INCLUDED_UNITS)
                .containsEntry("percent_of_included", 42.0);

        // Redis lost the counter: the transactional projection answers and re-seeds the cache.
        deleteCounterKeys(tenant);
        owner().update("UPDATE usage_counter_state SET total_units = 4300 WHERE tenant_id = ?", tenant.tenantId());
        assertThat(items(getJson("/v1/usage/current", tenant.tenantId())).getFirst())
                .containsEntry("total_units", 4300);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(4300);

        // Redis and the projection are gone: the ledger is the last line of defence.
        deleteCounterKeys(tenant);
        owner().update("DELETE FROM usage_counter_state WHERE tenant_id = ?", tenant.tenantId());
        assertThat(items(getJson("/v1/usage/current", tenant.tenantId())).getFirst())
                .containsEntry("total_units", 4200);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(4200);
    }

    @Test
    void currentUsageListsEveryActiveResourceEvenWithoutUsage() {
        TenantFixture tenant = givenTenant("Quiet Inc");
        UUID second = UUID.randomUUID();
        owner().update("INSERT INTO metered_resource (id, tenant_id, name, unit_label) VALUES (?, ?, 'Webhooks', 'delivery')",
                second, tenant.tenantId());

        List<Map<String, Object>> items = items(getJson("/v1/usage/current", tenant.tenantId()));

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item).containsEntry("total_units", 0)
                .containsEntry("percent_of_included", 0.0));
    }

    @Test
    void timeseriesBucketsTheLedgerInUtcAndValidatesItsRange() {
        TenantFixture tenant = givenTenant("Charts Inc");
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        insertLedgerRow(tenant, "ts-1", 5, hour.minus(Duration.ofHours(2)));
        insertLedgerRow(tenant, "ts-2", 7, hour.minus(Duration.ofHours(2)).plusSeconds(60));
        insertLedgerRow(tenant, "ts-3", 11, hour);

        Map<String, Object> series = getJson("/v1/usage/timeseries?granularity=hour&resource_id=" + tenant.resourceId()
                + "&from=" + hour.minus(Duration.ofHours(3)) + "&to=" + hour.plus(Duration.ofHours(1)),
                tenant.tenantId());

        assertThat(series).containsEntry("resource_id", tenant.resourceId().toString())
                .containsEntry("granularity", "hour");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) series.get("points");
        assertThat(points).hasSize(4);
        assertThat(points.get(0)).containsEntry("units", 0);
        assertThat(points.get(1)).containsEntry("units", 12)
                .containsEntry("bucket_start", hour.minus(Duration.ofHours(2)).toString());
        assertThat(points.get(2)).containsEntry("units", 0);
        assertThat(points.get(3)).containsEntry("units", 11);

        ResponseEntity<Map<String, Object>> tooWide = exchange(
                "/v1/usage/timeseries?granularity=hour&resource_id=" + tenant.resourceId()
                        + "&from=" + hour.minus(Duration.ofDays(30)) + "&to=" + hour, tenant.tenantId());
        assertThat(tooWide.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooWide.getBody()).containsEntry("code", "VALIDATION_FAILED");

        ResponseEntity<Map<String, Object>> unknownGranularity = exchange(
                "/v1/usage/timeseries?granularity=week&resource_id=" + tenant.resourceId(), tenant.tenantId());
        assertThat(unknownGranularity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> foreignResource = exchange(
                "/v1/usage/timeseries?resource_id=" + UUID.randomUUID(), tenant.tenantId());
        assertThat(foreignResource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignResource.getBody()).containsEntry("code", "RESOURCE_NOT_FOUND");
    }

    @Test
    void usageEndpointsRequireAuthentication() {
        ResponseEntity<Map<String, Object>> anonymous = rest.exchange("/v1/usage/current", HttpMethod.GET,
                HttpEntity.EMPTY, JSON_OBJECT);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("code", "UNAUTHENTICATED");
    }

    private void insertLedgerRow(TenantFixture tenant, String key, long quantity, Instant occurredAt) {
        owner().update("""
                        INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                tenant.tenantId(), tenant.resourceId(), key, quantity,
                java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt));
    }

    private void deleteCounterKeys(TenantFixture tenant) {
        String counter = RedisKeys.usageCounter(tenant.tenantId(), tenant.resourceId(), BillingPeriod.current(clock));
        redis.delete(List.of(counter, counter + ":version"));
    }

    private Map<String, Object> getJson(String path, UUID tenantId) {
        ResponseEntity<Map<String, Object>> response = exchange(path, tenantId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> exchange(String path, UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestJwt.bearer(tenantId, UUID.randomUUID(), Role.TENANT_USER));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JSON_OBJECT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plan(Map<String, Object> body) {
        return (Map<String, Object>) body.get("plan");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }
}
