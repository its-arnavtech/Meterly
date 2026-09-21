package com.meterly.metering;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.event.Topics;
import com.meterly.common.security.*;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor="PT30M")
class MeteringConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, MeterlyJwtAuthenticationConverter converter,
            ProblemDetailsSecurityHandlers handlers, MeterlyProperties properties) throws Exception {
        return http.csrf(c -> c.disable()).cors(c -> {}).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/ws/usage").permitAll()
                        .requestMatchers("/internal/**").hasRole("INTERNAL")
                        .requestMatchers("/v1/usage/**").hasAnyRole("TENANT_ADMIN", "TENANT_USER").anyRequest().denyAll())
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint()).accessDeniedHandler(handlers.accessDeniedHandler()))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(handlers.entryPoint())).build();
    }
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String,String> template) {
        var recoverer=new DeadLetterPublishingRecoverer(template, (record, ex) -> new TopicPartition(Topics.USAGE_EVENTS_DLT, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var backoff=new ExponentialBackOff(500,2); backoff.setMaxInterval(10000); backoff.setMaxElapsedTime(60000);
        var handler=new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, DataIntegrityViolationException.class);
        return handler;
    }
}
