package com.meterly.metering;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReconciliationController {
    private final ReconciliationService service;
    ReconciliationController(ReconciliationService service) { this.service=service; }
    @PostMapping("/internal/v1/reconciliation/run")
    ReconciliationService.Result run() { return service.run(); }
}
