package com.meterly.metering.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.redis.RedisKeys;
import com.meterly.metering.MeteringIntegrationTest;
import com.meterly.metering.MeteringMetrics;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Reconciliation (PDD 5.4): the nightly job's work, triggered through
 * {@code POST /internal/v1/reconciliation/run}, re-derives the open period from the ledger and repairs
 * both the transactional projection and Redis — even when Redis holds a wrong value under the current
 * version, which the version-guarded projection path would refuse to overwrite.
 */
class ReconciliationIT extends MeteringIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Test
    void repairsDriftedProjectionAndCache() {
        TenantFixture tenant = givenTenant("Drift Inc");
        publish(usageEvent(tenant, "drift-" + UUID.randomUUID(), 120));
        publish(usageEvent(tenant, "drift-" + UUID.randomUUID(), 30));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(150));

        String counterKey = RedisKeys.usageCounter(tenant.tenantId(), tenant.resourceId(), BillingPeriod.current(clock));
        owner().update("UPDATE usage_counter_state SET total_units = 999 WHERE tenant_id = ?", tenant.tenantId());
        // Wrong value under the *current* version: only the unconditional repair can fix this.
        redis.opsForValue().set(counterKey, "4711");

        double projectionDriftBefore = counterValue(MeteringMetrics.RECONCILIATION_DRIFT, "store", "projection");
        double cacheDriftBefore = counterValue(MeteringMetrics.RECONCILIATION_DRIFT, "store", "redis");

        ResponseEntity<Map<String, Object>> response = run(INTERNAL_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("tenants_checked", "counters_checked", "counters_corrected");
        assertThat((Integer) response.getBody().get("tenants_checked")).isPositive();
        assertThat((Integer) response.getBody().get("counters_corrected")).isEqualTo(1);
        assertThat(projectedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(150);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(150);
        assertThat(counterValue(MeteringMetrics.RECONCILIATION_DRIFT, "store", "projection"))
                .isEqualTo(projectionDriftBefore + 1);
        assertThat(counterValue(MeteringMetrics.RECONCILIATION_DRIFT, "store", "redis"))
                .isEqualTo(cacheDriftBefore + 1);
    }

    @Test
    void leavesHealthyCountersUntouched() {
        TenantFixture tenant = givenTenant("Healthy Inc");
        publish(usageEvent(tenant, "healthy-" + UUID.randomUUID(), 42));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(42));

        Map<String, Object> summary = run(INTERNAL_TOKEN).getBody();

        assertThat((Integer) summary.get("counters_checked")).isPositive();
        assertThat((Integer) summary.get("counters_corrected")).isZero();
    }

    @Test
    void internalEndpointRejectsCallsWithoutTheSharedToken() {
        assertThat(run(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run("wrong-token-wrong-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map<String, Object>> run(String internalToken) {
        HttpHeaders headers = new HttpHeaders();
        if (internalToken != null) {
            headers.set("X-Internal-Token", internalToken);
        }
        return rest.exchange("/internal/v1/reconciliation/run", HttpMethod.POST, new HttpEntity<>(headers),
                JSON_OBJECT);
    }
}
