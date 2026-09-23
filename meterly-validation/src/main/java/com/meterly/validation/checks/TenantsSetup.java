package com.meterly.validation.checks;

import com.meterly.validation.http.ApiClient;
import com.meterly.validation.http.ApiResponse;
import com.meterly.validation.http.Auth;
import com.meterly.validation.http.HarnessException;
import com.meterly.validation.model.TenantModels.ApiKeyCreated;
import com.meterly.validation.model.TenantModels.LoginResponse;
import com.meterly.validation.model.TenantModels.ResourceInfo;
import com.meterly.validation.model.TenantModels.ResourcesResponse;
import com.meterly.validation.model.TenantModels.SignupResponse;
import java.util.List;

/**
 * Brings up the three organizations contract §9 item 1 requires: the two seeded demo tenants
 * (Acme, Globex — meterly-db/src/main/resources/db/demo/R__demo_seed.sql) plus one created fresh
 * through the public signup endpoint, each with a freshly minted API key the harness controls (the
 * seed's own demo keys are stored only as salted hashes, so the harness cannot reuse them).
 */
public final class TenantsSetup {

    private record LoginRequest(String email, String password) {
    }

    private record SignupRequest(String tenantName, String adminEmail, String adminPassword, String planCode) {
    }

    private record ApiKeyRequest(String name) {
    }

    private record ResourceCreate(String name, String unitLabel) {
    }

    private final ApiClient api;

    public TenantsSetup(ApiClient api) {
        this.api = api;
    }

    public Organization loginSeeded(String label, String email, String password) {
        ApiResponse<LoginResponse> login = api.post("/v1/auth/login", Auth.NONE,
                new LoginRequest(email, password), LoginResponse.class);
        require(login.status() == 200, "login as " + email, login);
        String jwt = login.body().accessToken();
        var tenantId = login.body().user().tenantId();
        List<ResourceInfo> resources = fetchResources(jwt);
        String apiKey = mintApiKey(jwt, label + " validation harness key");
        return new Organization(label, tenantId, email, jwt, apiKey, resources);
    }

    public Organization signupFresh(String label) {
        long unique = System.currentTimeMillis();
        String tenantName = "Validation Harness " + unique;
        String email = "harness+" + unique + "@meterly.test";
        String password = "Harness!" + unique + "Valid";

        ApiResponse<SignupResponse> signup = api.post("/v1/tenants", Auth.NONE,
                new SignupRequest(tenantName, email, password, "STARTER"), SignupResponse.class);
        require(signup.status() == 201, "signup fresh tenant", signup);

        ApiResponse<LoginResponse> login = api.post("/v1/auth/login", Auth.NONE,
                new LoginRequest(email, password), LoginResponse.class);
        require(login.status() == 200, "login fresh tenant", login);
        String jwt = login.body().accessToken();
        var tenantId = login.body().user().tenantId();

        // Extra resources so the idempotent-metering, rate-limit and STOMP-latency checks each get a
        // clean, independent ledger to assert exact counts against, without touching one another.
        createResource(jwt, "Rate Limit Test", "call");
        createResource(jwt, "Stomp Latency Test", "call");

        List<ResourceInfo> resources = fetchResources(jwt);
        String apiKey = mintApiKey(jwt, "harness key");
        return new Organization(label, tenantId, email, jwt, apiKey, resources);
    }

    private List<ResourceInfo> fetchResources(String jwt) {
        ApiResponse<ResourcesResponse> resources = api.get("/v1/resources", Auth.bearer(jwt), ResourcesResponse.class);
        require(resources.status() == 200, "list resources", resources);
        return resources.body().items();
    }

    private String mintApiKey(String jwt, String name) {
        ApiResponse<ApiKeyCreated> created = api.post("/v1/api-keys", Auth.bearer(jwt),
                new ApiKeyRequest(name), ApiKeyCreated.class);
        require(created.status() == 201, "create API key", created);
        return created.body().key();
    }

    private void createResource(String jwt, String name, String unitLabel) {
        ApiResponse<ResourceInfo> created = api.post("/v1/resources", Auth.bearer(jwt),
                new ResourceCreate(name, unitLabel), ResourceInfo.class);
        require(created.status() == 201, "create resource " + name, created);
    }

    private void require(boolean condition, String action, ApiResponse<?> response) {
        if (!condition) {
            throw new HarnessException("Setup failed: " + action + " -> HTTP " + response.status()
                    + " " + response.rawBody());
        }
    }
}
