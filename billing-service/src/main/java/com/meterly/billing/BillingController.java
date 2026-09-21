package com.meterly.billing;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class BillingController {
    public record RunRequest(LocalDate periodStart) {}
    public record Items<T>(List<T> items) {}
    private final BillingService service;
    private final BillingJobs jobs;
    public BillingController(BillingService service, BillingJobs jobs) { this.service = service; this.jobs = jobs; }

    @PostMapping("/v1/billing/run")
    public ResponseEntity<BillingService.Invoice> run(@RequestBody(required = false) RunRequest request) {
        var result = service.run(request == null ? null : request.periodStart());
        return result.created() ? ResponseEntity.created(URI.create("/v1/invoices/" + result.invoice().id())).body(result.invoice())
                : ResponseEntity.ok(result.invoice());
    }
    @GetMapping("/v1/invoices") public Items<BillingService.Invoice> list() { return new Items<>(service.list()); }
    @GetMapping("/v1/invoices/{id}") public BillingService.Invoice detail(@PathVariable UUID id) { return service.detail(id); }
    @GetMapping("/v1/billing/preview") public BillingService.Invoice preview() { return service.preview(); }
    @PostMapping("/internal/v1/billing/run-all")
    public BillingJobs.RunSummary runAll(@RequestBody(required = false) RunRequest request) {
        return jobs.runAll(request == null ? null : request.periodStart());
    }
}
