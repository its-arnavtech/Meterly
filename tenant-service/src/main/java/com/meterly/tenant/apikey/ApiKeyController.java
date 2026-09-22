package com.meterly.tenant.apikey;

import com.meterly.common.security.CurrentUser;
import com.meterly.tenant.support.Items;
import com.meterly.tenant.support.Text;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api-keys")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name) {

        public CreateApiKeyRequest {
            name = Text.strip(name);
        }
    }

    @GetMapping
    public Items<ApiKey> list(CurrentUser caller) {
        return Items.of(apiKeys.list(caller));
    }

    /** The only response that ever contains the raw key, so it must not be cached anywhere. */
    @PostMapping
    public ResponseEntity<ApiKey.Created> create(CurrentUser caller, @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKey.Created created = apiKeys.create(caller, request.name());
        return ResponseEntity.created(URI.create("/v1/api-keys/" + created.apiKey().id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(CurrentUser caller, @PathVariable UUID id) {
        apiKeys.revoke(caller, id);
        return ResponseEntity.noContent().build();
    }
}
