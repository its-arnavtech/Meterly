package com.meterly.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.crypto.Hashing;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.redis.RedisKeys;
import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.common.web.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/usage-events")
class IngestionController {
    record Event(@NotNull UUID resourceId, @NotBlank @Pattern(regexp="[A-Za-z0-9._:\\-]{1,255}") String idempotencyKey,
                 @NotNull @Min(1) @Max(1000000000) Long quantity, Instant occurredAt) {}
    record Batch(@NotNull @Size(min=1, max=100) List<@NotNull Event> events) {}
    private final TenantClient tenants;
    private final IngestionRateLimiter limiter;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Validator validator;
    private final MeterRegistry metrics;
    private final Duration maxAge, maxSkew;
    IngestionController(TenantClient tenants, IngestionRateLimiter limiter, StringRedisTemplate redis,
            KafkaTemplate<String, String> kafka, ObjectMapper mapper, Clock clock, Validator validator, MeterRegistry metrics,
            @Value("${meterly.ingestion.max-event-age:PT1H}") Duration maxAge,
            @Value("${meterly.ingestion.max-clock-skew:PT5M}") Duration maxSkew) {
        this.tenants=tenants; this.limiter=limiter; this.redis=redis; this.kafka=kafka; this.mapper=mapper;
        this.clock=clock; this.validator=validator; this.metrics=metrics; this.maxAge=maxAge; this.maxSkew=maxSkew;
    }
    @PostMapping
    ResponseEntity<?> single(@RequestHeader(value="X-API-Key", required=false) String key, Authentication auth,
                             @Valid @RequestBody Event event, HttpServletResponse response) {
        var identity = identity(key, auth); rate(identity.tenantId(), 1, response);
        var result = accept(identity, event);
        return ResponseEntity.status("duplicate".equals(result.get("status")) ? 200 : 202).body(result);
    }
    @PostMapping("/batch")
    Map<String,Object> batch(@RequestHeader(value="X-API-Key", required=false) String key, Authentication auth,
                            @Valid @RequestBody Batch batch, HttpServletResponse response) {
        var identity=identity(key, auth); rate(identity.tenantId(), batch.events().size(), response);
        List<Map<String,Object>> results=new ArrayList<>();
        for (Event event:batch.events()) {
            try {
                if (!validator.validate(event).isEmpty()) throw ApiException.badRequest("VALIDATION_FAILED", "Invalid event fields");
                results.add(accept(identity, event));
            } catch (ApiException e) {
                Map<String,Object> rejected=new LinkedHashMap<>(); rejected.put("idempotency_key", event.idempotencyKey());
                rejected.put("status", "rejected"); rejected.put("code", e.code()); rejected.put("detail", e.getMessage()); results.add(rejected);
            }
        }
        return Map.of("results", results);
    }
    private TenantClient.Identity identity(String key, Authentication auth) {
        if (key != null && auth instanceof JwtAuthenticationToken) throw ApiException.badRequest("AMBIGUOUS_CREDENTIALS", "Provide one authentication method");
        if (key != null) return tenants.verify(key);
        if (!(auth instanceof JwtAuthenticationToken jwt)) throw ApiException.unauthorized("UNAUTHENTICATED", "API key or bearer token required");
        CurrentUser user=CurrentUser.from(jwt.getToken());
        if (!user.isAdmin()) throw ApiException.forbidden("FORBIDDEN", "Tenant administrator role required");
        return tenants.resources(user.tenantId());
    }
    private void rate(UUID tenant, int count, HttpServletResponse response) {
        var limit=limiter.consume(tenant, count);
        response.setHeader("X-RateLimit-Limit", ""+limit.limit()); response.setHeader("X-RateLimit-Remaining", ""+limit.remaining());
        if (!limit.allowed()) { response.setHeader("Retry-After", ""+limit.retryAfter()); throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Tenant event rate limit exceeded"); }
    }
    private Map<String,Object> accept(TenantClient.Identity identity, Event event) {
        Instant now=clock.instant(); Instant occurred=event.occurredAt()==null ? now : event.occurredAt();
        if (!identity.resourceIds().contains(event.resourceId())) throw ApiException.unprocessable("UNKNOWN_RESOURCE", "Resource is not active for this tenant");
        String fingerprint=Hashing.sha256Hex(event.resourceId()+"|"+event.quantity()+"|"+(event.occurredAt()==null ? "default" : event.occurredAt()));
        String redisKey=RedisKeys.idempotency(identity.tenantId(),event.idempotencyKey());
        try {
            String previous=redis.opsForValue().get(redisKey);
            if (previous!=null) {
                if (!previous.equals(fingerprint)) throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","Idempotency key already used for another payload");
                return Map.of("status","duplicate","idempotency_key",event.idempotencyKey());
            }
        } catch (org.springframework.dao.DataAccessException e) { metrics.counter("meterly_ingestion_idempotency_cache_unavailable_total").increment(); }
        if (occurred.isBefore(now.minus(maxAge))) throw ApiException.unprocessable("EVENT_TOO_OLD", "Event is outside the accepted age window");
        if (occurred.isAfter(now.plus(maxSkew))) throw ApiException.unprocessable("EVENT_IN_FUTURE", "Event exceeds allowed clock skew");
        var message=new UsageEventMessage(event.idempotencyKey(),identity.tenantId(),event.resourceId(),event.quantity(),occurred,now,CorrelationId.current().orElseGet(()->UUID.randomUUID().toString()));
        try {
            var record=new ProducerRecord<String,String>(Topics.USAGE_EVENTS,identity.tenantId().toString(),mapper.writeValueAsString(message));
            record.headers().add(CorrelationId.KAFKA_HEADER,message.requestId().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10,TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception e) { metrics.counter("meterly_ingestion_publish_failures_total").increment(); throw unavailable(); }
        try { redis.opsForValue().setIfAbsent(redisKey,fingerprint,RedisKeys.IDEMPOTENCY_TTL); }
        catch (org.springframework.dao.DataAccessException e) { metrics.counter("meterly_ingestion_idempotency_cache_unavailable_total").increment(); }
        metrics.counter("meterly_ingestion_events_total").increment();
        return Map.of("status","accepted","idempotency_key",event.idempotencyKey(),"received_at",now);
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"INGESTION_UNAVAILABLE","Usage broker unavailable; retry with the same idempotency key"); }
}
