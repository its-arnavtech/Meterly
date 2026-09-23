package com.meterly.ingestion.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings owned by ingestion-service, under {@code meterly.ingestion.*}. Every value is overridable by
 * an environment variable and has a safe local default.
 *
 * <pre>
 * meterly.ingestion.tenant-service-url                  base URL of tenant-service (TENANT_SERVICE_URL)
 * meterly.ingestion.tenant-service-connect-timeout       connect timeout for the internal HTTP client
 * meterly.ingestion.tenant-service-read-timeout          read timeout for the internal HTTP client
 * meterly.ingestion.max-event-age                        how far in the past occurred_at may be
 * meterly.ingestion.max-clock-skew                       how far in the future occurred_at may be
 * meterly.ingestion.auth-cache.positive-ttl              API-key verification cache TTL for a valid key
 * meterly.ingestion.auth-cache.negative-ttl              API-key verification cache TTL for an invalid key
 * meterly.ingestion.resource-cache-ttl                   JWT-admin tenant-resource cache TTL
 * meterly.ingestion.rate-limit.capacity                  token-bucket capacity (burst size) per tenant
 * meterly.ingestion.rate-limit.refill-per-second          token-bucket refill rate per tenant
 * meterly.ingestion.publish-timeout                      how long to wait for the Kafka broker ack
 * </pre>
 */
@ConfigurationProperties("meterly.ingestion")
public record IngestionProperties(
        @DefaultValue("http://localhost:8081") URI tenantServiceUrl,
        @DefaultValue("PT2S") Duration tenantServiceConnectTimeout,
        @DefaultValue("PT3S") Duration tenantServiceReadTimeout,
        @DefaultValue("PT1H") Duration maxEventAge,
        @DefaultValue("PT5M") Duration maxClockSkew,
        @DefaultValue AuthCache authCache,
        @DefaultValue("PT60S") Duration resourceCacheTtl,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("PT5S") Duration publishTimeout) {

    /** Positive results (a verified key) are cached longer than negative ones (an unknown/revoked key). */
    public record AuthCache(@DefaultValue("PT60S") Duration positiveTtl, @DefaultValue("PT10S") Duration negativeTtl) {
    }

    /** A tenant may burst up to {@code capacity} events, refilling at {@code refillPerSecond}. */
    public record RateLimit(@DefaultValue("500") long capacity, @DefaultValue("200") long refillPerSecond) {
    }
}
