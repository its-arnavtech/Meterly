package com.meterly.common.autoconfigure;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/** Refuses unsafe inherited development settings when the production profile is selected. */
public final class ProductionConfigurationGuard implements SmartInitializingSingleton {
    private final MeterlyProperties properties;
    private final Environment environment;

    public ProductionConfigurationGuard(MeterlyProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        requireSecret(properties.security().internalToken(), "meterly.security.internal-token");
        if (environment.containsProperty("spring.datasource.password")) {
            requireSecret(environment.getProperty("spring.datasource.password"), "spring.datasource.password");
        }
        List<String> origins = properties.cors().allowedOrigins();
        if (origins == null || origins.isEmpty()) throw unsafe("meterly.cors.allowed-origins");
        for (String origin : origins) {
            URI uri;
            try { uri = URI.create(origin); } catch (IllegalArgumentException e) { throw unsafe("CORS origin"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || origin.contains("*")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw unsafe("meterly.cors.allowed-origins (explicit HTTPS origins required)");
            }
        }
    }

    private static void requireSecret(String value, String property) {
        if (value == null || value.length() < 32 || value.toLowerCase(java.util.Locale.ROOT).contains("change-me")
                || value.toLowerCase(java.util.Locale.ROOT).contains("changeme")) throw unsafe(property);
    }

    private static IllegalStateException unsafe(String property) {
        return new IllegalStateException("Unsafe production configuration: " + property);
    }
}
