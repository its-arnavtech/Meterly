package com.meterly.metering.api;

import com.meterly.common.tenant.TenantContext;
import com.meterly.metering.api.UsageResponses.CurrentUsage;
import com.meterly.metering.api.UsageResponses.Timeseries;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usage dashboard API for both roles ({@code TENANT_ADMIN}, {@code TENANT_USER}; enforced by the
 * security chain). The tenant comes from the validated JWT, bound by {@code TenantContextAspect}.
 */
@RestController
@RequestMapping("/v1/usage")
public class UsageController {

    private final UsageQueryService queries;
    private final Clock clock;

    public UsageController(UsageQueryService queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/current")
    public CurrentUsage current() {
        return queries.current(TenantContext.require());
    }

    @GetMapping("/timeseries")
    public Timeseries timeseries(@RequestParam(name = "resource_id", required = false) String resourceId,
                                 @RequestParam(name = "granularity", required = false) String granularity,
                                 @RequestParam(name = "from", required = false) String from,
                                 @RequestParam(name = "to", required = false) String to) {
        TimeseriesQuery query = TimeseriesQuery.parse(resourceId, granularity, from, to, clock);
        return queries.timeseries(TenantContext.require(), query);
    }
}
