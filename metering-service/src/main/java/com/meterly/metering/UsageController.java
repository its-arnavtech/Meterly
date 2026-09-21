package com.meterly.metering;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/usage")
class UsageController {
    private final UsageRepository repository;
    private final UsageCache cache;
    private final TransactionTemplate tx;
    private final Clock clock;
    UsageController(UsageRepository repository,UsageCache cache,TransactionTemplate tx,Clock clock) { this.repository=repository; this.cache=cache; this.tx=tx; this.clock=clock; }
    @GetMapping("/current")
    Map<String,Object> current() {
        UUID tenant=TenantContext.require(); BillingPeriod period=BillingPeriod.current(clock);
        return tx.execute(s->{
            var plan=repository.plan(period); List<Map<String,Object>> items=new ArrayList<>();
            for (var resource:repository.resources(true)) {
                Long units=cache.read(tenant,resource.id(),period);
                if (units==null) {
                    var snapshot=repository.snapshot(resource.id(),period); units=snapshot.units();
                    cache.project(tenant,resource.id(),period,snapshot,null);
                }
                items.add(Map.of("resource_id",resource.id(),"resource_name",resource.name(),"unit_label",resource.unitLabel(),"total_units",units,
                        "included_units",plan.includedUnits(),"percent_of_included",plan.includedUnits()==0 ? 0.0 : units*100.0/plan.includedUnits()));
            }
            return Map.of("period_start",period.start(),"period_end",period.end(),"plan",plan,"items",items);
        });
    }
    @GetMapping("/timeseries")
    Map<String,Object> timeseries(@RequestParam("resource_id") UUID resource,
            @RequestParam(defaultValue="day") String granularity,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        if (!Set.of("hour","day").contains(granularity)) throw ApiException.badRequest("INVALID_GRANULARITY","Granularity must be hour or day");
        var period=BillingPeriod.current(clock);
        Instant start=from==null ? period.startInstant() : from;
        Instant end=to==null ? period.endInstant() : to;
        if (!start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(granularity.equals("hour")?7:93))>0)
            throw ApiException.badRequest("INVALID_RANGE","Range must be positive and at most 7 days for hours or 93 days for days");
        return tx.execute(s->{
            if (repository.resources(false).stream().noneMatch(r->r.id().equals(resource))) throw ApiException.notFound("RESOURCE_NOT_FOUND","Resource not found");
            return Map.of("resource_id",resource,"granularity",granularity,"from",start,"to",end,"points",repository.timeseries(resource,granularity,start,end));
        });
    }
}
