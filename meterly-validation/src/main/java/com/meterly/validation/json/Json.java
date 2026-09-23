package com.meterly.validation.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one {@link ObjectMapper} the harness uses everywhere: snake_case field names (matching every
 * Meterly service's wire format, see docs/api-contract.md §0) and ISO-8601 instants/dates, mirroring
 * {@code com.meterly.common.autoconfigure.MeterlyCoreAutoConfiguration} without depending on Spring.
 *
 * <p>Money fields arrive as JSON strings (e.g. {@code "278.69"}); Jackson's built-in
 * {@code BigDecimal} deserializer already accepts a JSON string token, so no custom deserializer is
 * needed on the read side. The harness never serializes {@code BigDecimal} itself.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
