package com.meterly.common.autoconfigure;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * JSON conventions shared by every service: snake_case field names, ISO-8601 dates, and
 * {@link BigDecimal} written as a JSON <em>string</em> in plain notation (e.g. {@code "274.00"}) so no
 * client ever parses money into a binary floating-point number.
 */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
@EnableConfigurationProperties(MeterlyProperties.class)
public class MeterlyCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Picked up by Boot's JacksonAutoConfiguration, which registers every {@link Module} bean. */
    @Bean
    public Module meterlyMoneyModule() {
        SimpleModule money = new SimpleModule("meterly-money");
        money.addSerializer(BigDecimal.class, new PlainBigDecimalSerializer());
        return money;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Jackson2ObjectMapperBuilder.class)
    static class JacksonConventions {

        @Bean
        public Jackson2ObjectMapperBuilderCustomizer meterlyJacksonConventions() {
            return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    /** Serializes BigDecimal via {@link BigDecimal#toPlainString()} as a JSON string. */
    static final class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {
        PlainBigDecimalSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
