package com.meterly.metering.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Scheduled jobs (outbox relay, nightly reconciliation) run on a dedicated scheduler, separate from the
 * STOMP broker's heartbeat scheduler, and are made cluster-singletons with ShedLock on the
 * {@code shedlock} table (database time, so replica clock skew does not matter).
 *
 * <p>{@code meterly.metering.scheduling.enabled=false} disables all scheduled jobs (tests).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "meterly.metering.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfiguration implements SchedulingConfigurer {

    private final ThreadPoolTaskScheduler meteringJobScheduler;

    public SchedulingConfiguration() {
        this.meteringJobScheduler = new ThreadPoolTaskScheduler();
        this.meteringJobScheduler.setPoolSize(2);
        this.meteringJobScheduler.setThreadNamePrefix("metering-job-");
        this.meteringJobScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.meteringJobScheduler.setAwaitTerminationSeconds(20);
    }

    @Bean
    ThreadPoolTaskScheduler meteringJobScheduler() {
        return meteringJobScheduler;
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(meteringJobScheduler);
    }
}
