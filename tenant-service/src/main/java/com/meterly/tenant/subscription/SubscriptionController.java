package com.meterly.tenant.subscription;

import com.meterly.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/subscription")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    public record ChangePlanRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "must be a plan code such as GROWTH")
            String planCode) {
    }

    @GetMapping
    public Subscription.Overview get(CurrentUser caller) {
        return subscriptions.overview(caller);
    }

    @PutMapping
    public Subscription.Overview change(CurrentUser caller, @Valid @RequestBody ChangePlanRequest request) {
        return subscriptions.changePlan(caller, request.planCode());
    }
}
