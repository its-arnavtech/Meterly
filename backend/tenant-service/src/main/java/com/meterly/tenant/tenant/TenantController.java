package com.meterly.tenant.tenant;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class TenantController {
    private final TenantManagementService managementService;
    private final TenantRepository repository;

    public TenantController(TenantManagementService managementService, TenantRepository repository) {
        this.managementService = managementService;
        this.repository = repository;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantManagementService.OnboardingResponse onboard(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        return managementService.onboard(
                request.name(), request.adminEmail(), request.adminPassword(), request.planId());
    }

    @GetMapping("/tenants/current")
    public TenantRepository.TenantView currentTenant() {
        return repository.currentTenant();
    }

    @PatchMapping("/tenants/current")
    public TenantRepository.TenantView updateTenant(@Valid @RequestBody UpdateTenantRequest request) {
        return repository.updateCurrent(request.name());
    }

    @DeleteMapping("/tenants/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendTenant() {
        repository.suspendCurrent();
    }

    @GetMapping("/resources")
    public List<TenantRepository.ResourceView> resources() {
        return repository.listResources();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantRepository.UserView createUser(@Valid @RequestBody CreateUserRequest request) {
        return managementService.createUser(request.email(), request.password(), request.role());
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantRepository.ApiKeyView issueKey(@Valid @RequestBody IssueApiKeyRequest request) {
        return managementService.issueApiKey(request.label());
    }

    @GetMapping("/plans/current")
    public TenantRepository.PlanView currentPlan() {
        return repository.currentPlan();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() {
        return Map.of("error", "A tenant-scoped record with that unique value already exists");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateTenantRequest(
            @NotBlank @Size(max = 160) String name,
            @Email @NotBlank String adminEmail,
            @NotBlank @Size(min = 10, max = 100) String adminPassword,
            @NotNull UUID planId
    ) {
    }

    public record UpdateTenantRequest(@NotBlank @Size(max = 160) String name) {
    }

    public record CreateUserRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 10, max = 100) String password,
            @NotBlank String role
    ) {
    }

    public record IssueApiKeyRequest(@NotBlank @Size(max = 120) String label) {
    }
}
