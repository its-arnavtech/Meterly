package com.meterly.tenant.resource;

import com.meterly.common.security.CurrentUser;
import com.meterly.tenant.support.Items;
import com.meterly.tenant.support.Text;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/resources")
public class ResourceController {

    private final ResourceService resources;

    public ResourceController(ResourceService resources) {
        this.resources = resources;
    }

    public record CreateResourceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String unitLabel) {

        public CreateResourceRequest {
            name = Text.strip(name);
            unitLabel = Text.strip(unitLabel);
        }
    }

    /** Partial update: omitted (null) fields are left unchanged. */
    public record UpdateResourceRequest(
            @Size(min = 1, max = 100) String name,
            @Size(min = 1, max = 30) String unitLabel,
            @Pattern(regexp = "ACTIVE|ARCHIVED", message = "must be ACTIVE or ARCHIVED") String status) {

        public UpdateResourceRequest {
            name = Text.strip(name);
            unitLabel = Text.strip(unitLabel);
        }

        Resource.ResourceStatus statusValue() {
            return status == null ? null : Resource.ResourceStatus.valueOf(status);
        }
    }

    /** Any role: the usage dashboard lists resources. */
    @GetMapping
    public Items<Resource> list(CurrentUser caller) {
        return Items.of(resources.list(caller));
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Resource> create(CurrentUser caller, @Valid @RequestBody CreateResourceRequest request) {
        Resource created = resources.create(caller, request.name(), request.unitLabel());
        return ResponseEntity.created(URI.create("/v1/resources/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Resource update(CurrentUser caller, @PathVariable UUID id,
                           @Valid @RequestBody UpdateResourceRequest request) {
        return resources.update(caller, id, request.name(), request.unitLabel(), request.statusValue());
    }
}
