package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Plan changes take effect at the next period boundary; re-selecting the current plan cancels them. */
class SubscriptionIT extends AbstractTenantServiceIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate NEXT_PERIOD = TODAY.withDayOfMonth(1).plusMonths(1);

    private ResponseEntity<JsonNode> changePlan(Session admin, String planCode) {
        return put("/v1/subscription", Map.of("plan_code", planCode), bearer(admin));
    }

    @Test
    void signupStartsAnOpenEndedSubscriptionToday() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> subscription = get("/v1/subscription", bearer(admin));

        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(subscription.getBody().at("/current/plan/code").asText()).isEqualTo("STARTER");
        assertThat(subscription.getBody().at("/current/start_date").asText()).isEqualTo(TODAY.toString());
        assertThat(subscription.getBody().at("/current/end_date").isNull()).isTrue();
        assertThat(subscription.getBody().at("/current/status").asText()).isEqualTo("ACTIVE");
        assertThat(subscription.getBody().get("scheduled").isNull()).isTrue();
    }

    @Test
    void changingThePlanSchedulesItForTheNextPeriodAndCanBeCancelled() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> scheduled = changePlan(admin, "GROWTH");
        assertThat(scheduled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scheduled.getBody().at("/current/plan/code").asText()).isEqualTo("STARTER");
        assertThat(scheduled.getBody().at("/current/end_date").asText()).isEqualTo(NEXT_PERIOD.toString());
        assertThat(scheduled.getBody().at("/scheduled/plan/code").asText()).isEqualTo("GROWTH");
        assertThat(scheduled.getBody().at("/scheduled/start_date").asText()).isEqualTo(NEXT_PERIOD.toString());
        assertThat(scheduled.getBody().at("/scheduled/end_date").isNull()).isTrue();

        // Repeating the same change is idempotent.
        assertThat(changePlan(admin, "GROWTH").getBody().at("/scheduled/start_date").asText())
                .isEqualTo(NEXT_PERIOD.toString());

        // Switching the pending plan replaces it instead of stacking a second one.
        ResponseEntity<JsonNode> replaced = changePlan(admin, "SCALE");
        assertThat(replaced.getBody().at("/scheduled/plan/code").asText()).isEqualTo("SCALE");

        // Re-selecting the current plan cancels the pending change and re-opens the current subscription.
        ResponseEntity<JsonNode> cancelled = changePlan(admin, "STARTER");
        assertThat(cancelled.getBody().get("scheduled").isNull()).isTrue();
        assertThat(cancelled.getBody().at("/current/plan/code").asText()).isEqualTo("STARTER");
        assertThat(cancelled.getBody().at("/current/end_date").isNull()).isTrue();
        assertThat(get("/v1/subscription", bearer(admin)).getBody().get("scheduled").isNull()).isTrue();
    }

    @Test
    void unknownPlansAndNonAdminsAreRejected() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        Session viewer = createUser(admin, "viewer@acme.test", "TENANT_USER");

        ResponseEntity<JsonNode> unknown = changePlan(admin, "PLATINUM");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(unknown)).isEqualTo("UNKNOWN_PLAN");

        ResponseEntity<JsonNode> malformed = changePlan(admin, "growth");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(malformed)).isEqualTo("VALIDATION_FAILED");

        assertThat(changePlan(viewer, "GROWTH").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/subscription", bearer(admin)).getBody().get("scheduled").isNull()).isTrue();
    }

    @Test
    void eachTenantSchedulesItsOwnPlanIndependently() {
        Session acme = signup("Acme Corp", "admin@acme.test");
        Session globex = signup("Globex", "admin@globex.test");

        changePlan(acme, "SCALE");

        assertThat(get("/v1/subscription", bearer(globex)).getBody().get("scheduled").isNull()).isTrue();
        assertThat(get("/v1/subscription", bearer(globex)).getBody().at("/current/plan/code").asText())
                .isEqualTo("STARTER");
        assertThat(get("/v1/subscription", bearer(acme)).getBody().at("/scheduled/plan/code").asText())
                .isEqualTo("SCALE");
    }
}
