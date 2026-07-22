package com.meterly.billing.metering;

import com.meterly.billing.auth.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/usage")
public class UsageQueryController {
    private final UsageMeteringService meteringService;

    public UsageQueryController(UsageMeteringService meteringService) {
        this.meteringService = meteringService;
    }

    @GetMapping("/current")
    public Map<String, Object> current(
            Authentication authentication,
            @RequestParam("resource_id") UUID resourceId
    ) {
        UUID tenantId = ((AuthPrincipal) authentication.getPrincipal()).tenantId();
        String period = YearMonth.now(ZoneOffset.UTC).toString();
        return Map.of(
                "tenant_id", tenantId,
                "resource_id", resourceId,
                "period", period,
                "total_units", meteringService.currentUsage(tenantId, resourceId, period)
        );
    }
}
