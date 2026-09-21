package com.meterly.tenant;

import com.meterly.common.security.CurrentUser;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.meterly.tenant.TenantRequests.*;

@RestController
public class TenantController {
    private final TenantService service;
    private final AuthThrottle throttle;
    public TenantController(TenantService service,AuthThrottle throttle) { this.service=service; this.throttle=throttle; }
    @PostMapping("/v1/tenants") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> signup(@Valid @RequestBody Signup input,HttpServletRequest request,HttpServletResponse response) {
        throttle.signup(request.getRemoteAddr(),response); return service.signup(input);
    }
    @GetMapping("/v1/plans") public Map<String,Object> plans() { return Map.of("items",service.plans()); }
    @GetMapping("/v1/me") public Map<String,Object> me(CurrentUser user) { return service.me(user); }
    @GetMapping("/v1/tenant") public Map<String,Object> tenant(CurrentUser user) { return service.getTenant(user); }
    @PatchMapping("/v1/tenant") public Map<String,Object> tenant(CurrentUser user,@Valid @RequestBody TenantPatch input) { return service.updateTenant(user,input); }
    @GetMapping("/v1/subscription") public Map<String,Object> subscription(CurrentUser user) { return service.getSubscription(user); }
    @PutMapping("/v1/subscription") public Map<String,Object> subscription(CurrentUser user,@Valid @RequestBody PlanChange input) { return service.changePlan(user,input); }
    @GetMapping("/v1/resources") public Map<String,Object> resources(CurrentUser user) { return Map.of("items",service.getResources(user)); }
    @PostMapping("/v1/resources") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> resource(CurrentUser user,@Valid @RequestBody ResourceCreate input) { return service.createResource(user,input); }
    @PatchMapping("/v1/resources/{id}") public Map<String,Object> resource(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody ResourcePatch input) { return service.updateResource(user,id,input); }
    @GetMapping("/v1/users") public Map<String,Object> users(CurrentUser user) { return Map.of("items",service.getUsers(user)); }
    @PostMapping("/v1/users") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> user(CurrentUser user,@Valid @RequestBody UserCreate input) { return service.createUser(user,input); }
    @PatchMapping("/v1/users/{id}") public Map<String,Object> user(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody UserPatch input) { return service.updateUser(user,id,input); }
    @GetMapping("/v1/api-keys") public Map<String,Object> keys(CurrentUser user) { return Map.of("items",service.getKeys(user)); }
    @PostMapping("/v1/api-keys") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> key(CurrentUser user,@Valid @RequestBody KeyCreate input,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); return service.createKey(user,input);
    }
    @DeleteMapping("/v1/api-keys/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void key(CurrentUser user,@PathVariable UUID id) { service.revokeKey(user,id); }
    @PostMapping("/internal/v1/api-keys/verify") public Map<String,Object> verify(@Valid @RequestBody KeyVerify input) { return service.verifyKey(input.apiKey()); }
    @GetMapping("/internal/v1/tenants/{id}/resources") public Map<String,Object> internalResources(@PathVariable UUID id) { return service.internalResources(id); }
    @GetMapping("/internal/v1/tenants/{id}/admins") public Map<String,Object> internalAdmins(@PathVariable UUID id) { return Map.of("items",service.internalAdmins(id)); }
}
